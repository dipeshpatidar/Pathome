package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.MediaStagingConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Guest cookies are accepted only from the configured browser origin or legitimate local-dev origins. */
@Component
public class GuestRequestGuard {
    private static final Pattern PRIVATE_OR_LOOPBACK_IP = Pattern.compile(
            "^(127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|::1|\\[::1\\])$");
    private static final Pattern LOCAL_HOSTNAME = Pattern.compile("^[a-zA-Z0-9.-]+\\.local$", Pattern.CASE_INSENSITIVE);

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
            return isLegitimateDevOrigin(origin);
        }
        return false;
    }

    public static boolean isLegitimateDevOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return true;
            }
            if (LOCAL_HOSTNAME.matcher(host).matches()) {
                return true;
            }
            if (PRIVATE_OR_LOOPBACK_IP.matcher(host).matches()) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    public boolean secureCookie(HttpServletRequest request) { return production || request.isSecure(); }
}
