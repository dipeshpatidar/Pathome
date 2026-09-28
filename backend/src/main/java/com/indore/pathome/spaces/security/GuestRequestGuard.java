package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.MediaStagingConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application-level security gate for guest draft endpoints.
 *
 * <p>Every guest endpoint calls {@link #check(HttpServletRequest)} before delegating to the
 * service layer. An allowed origin is either:
 * <ol>
 *   <li>An origin in the explicitly configured {@code pathome.guest.allowed-origins} list
 *       (required in production, optional in development).</li>
 *   <li>A legitimate local-development origin as determined by {@link DevOriginPolicy}
 *       (only evaluated when <em>not</em> in production).</li>
 * </ol>
 *
 * <p>Origin-presence enforcement: mutating requests (anything other than GET/HEAD) without an
 * {@code Origin} header are rejected. This guards against direct server-to-server calls that
 * bypass the browser same-origin mechanism.
 *
 * <p>Production safety: when the {@code prod} or {@code production} profile is active, only
 * explicitly configured origins are accepted. LAN/local development patterns are never applied.
 */
@Component
public class GuestRequestGuard {

    private final Set<String> origins;
    private final boolean production;

    public GuestRequestGuard(Environment env,
            @Value("${pathome.guest.allowed-origins:}") String configuredOrigins) {
        production = MediaStagingConfig.isProductionEnvironment(env);
        if (production && configuredOrigins.isBlank()) {
            throw new IllegalStateException("Configure pathome.guest.allowed-origins for guest onboarding");
        }
        String defaults = "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080";
        origins = Arrays.stream((configuredOrigins.isBlank() ? defaults : configuredOrigins).split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public void check(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        boolean mutating = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
        if (origin == null && mutating) {
            throw new AccessDeniedException("Guest request origin not allowed");
        }
        if (origin != null && !isAllowedOrigin(origin)) {
            throw new AccessDeniedException("Guest request origin not allowed");
        }
    }

    public boolean isAllowedOrigin(String origin) {
        if (origins.contains(origin)) {
            return true;
        }
        if (!production) {
            return DevOriginPolicy.isLegitimateDevOrigin(origin);
        }
        return false;
    }

    public boolean secureCookie(HttpServletRequest request) { return production || request.isSecure(); }
}
