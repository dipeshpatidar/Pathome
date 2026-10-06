package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

final class StaffAuthentication {
    private StaffAuthentication() {}

    static Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof PathomeAuthenticationDetails details)
                || details.getUserId() == null || details.getUserId() <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        return details.getUserId();
    }
}
