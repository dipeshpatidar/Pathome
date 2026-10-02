package com.indore.pathome.spaces.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

public final class PathomeAuthenticationIdentity {
    private PathomeAuthenticationIdentity() {}

    public static Long requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())
                || !(authentication.getDetails() instanceof PathomeAuthenticationDetails details)
                || details.getUserId() == null || details.getUserId() <= 0) {
            throw new AccessDeniedException("Authenticated user identity unavailable");
        }
        return details.getUserId();
    }
}
