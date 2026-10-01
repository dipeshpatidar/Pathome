package com.indore.pathome.spaces.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/** Carries the stable user identity from the verified JWT alongside request details. */
public final class PathomeAuthenticationDetails extends WebAuthenticationDetails {
    private final Long userId;

    public PathomeAuthenticationDetails(HttpServletRequest request, Long userId) {
        super(request);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
