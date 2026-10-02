package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.service.TenantVisitAvailabilityService;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationIdentity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/visit-requests")
public class TenantVisitAvailabilityController {
    private final UserRepository users;
    private final TenantVisitAvailabilityService availability;

    public TenantVisitAvailabilityController(UserRepository users, TenantVisitAvailabilityService availability) {
        this.users = users;
        this.availability = availability;
    }

    @PutMapping("/{requestId}/availability")
    public TenantVisitAvailabilityView update(Authentication authentication, @PathVariable Long requestId,
                                              @RequestBody TenantVisitAvailabilityCommand command) {
        Long userId = PathomeAuthenticationIdentity.requireUserId(authentication);
        User tenant = users.findById(userId).orElseThrow(() -> new AccessDeniedException("Tenant account is unavailable"));
        if (tenant.getRole() != Role.ROLE_TENANT)
            throw new AccessDeniedException("Tenant capability required");
        return availability.update(tenant.getId(), requestId, command);
    }
}
