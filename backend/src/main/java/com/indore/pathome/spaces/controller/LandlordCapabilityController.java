package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.service.LandlordCapabilityService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/capability")
@PreAuthorize("isAuthenticated()")
public class LandlordCapabilityController {
    private final LandlordCapabilityService capabilities;

    public LandlordCapabilityController(LandlordCapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping
    public LandlordCapabilityService.Capability get(Authentication authentication) {
        return capabilities.getCapability(authentication.getName());
    }

    @PostMapping
    public LandlordCapabilityService.Capability activate(Authentication authentication) {
        return capabilities.activate(authentication.getName());
    }
}
