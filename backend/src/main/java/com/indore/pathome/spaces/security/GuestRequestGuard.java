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

/** Guest cookies are accepted only from the configured browser origin. */
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
        if (origin == null && mutating || origin != null && !origins.contains(origin)) {
            throw new AccessDeniedException("Guest request origin not allowed");
        }
    }

    public boolean secureCookie(HttpServletRequest request) { return production || request.isSecure(); }
}
