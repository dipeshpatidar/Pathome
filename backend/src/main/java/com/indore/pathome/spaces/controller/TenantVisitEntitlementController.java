package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.TenantVisitEntitlementView;
import com.indore.pathome.spaces.service.TenantVisitEntitlementService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/visit-entitlement")
public class TenantVisitEntitlementController {
    private final TenantVisitEntitlementService entitlements;

    public TenantVisitEntitlementController(TenantVisitEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping
    public TenantVisitEntitlementView getMine(Authentication authentication) {
        return entitlements.getMine(VisitOperationsController.authenticatedUserId(authentication));
    }
}
