package com.indore.pathome.spaces.service;

import java.net.URI;
import java.util.Locale;

/** Canonicalizes the trusted public frontend origin without trusting request-host headers. */
public final class PublicPropertyOrigin {
    private PublicPropertyOrigin() {}

    public static String canonical(String configuredOrigin) {
        if (configuredOrigin == null || configuredOrigin.isBlank()) {
            throw new IllegalArgumentException("A canonical public property origin is not configured");
        }
        URI uri = URI.create(configuredOrigin.trim());
        int port = uri.getPort();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || port == 0 || port > 65_535
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("The configured public property origin is invalid");
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (port > 0 ? ":" + port : "");
    }
}
