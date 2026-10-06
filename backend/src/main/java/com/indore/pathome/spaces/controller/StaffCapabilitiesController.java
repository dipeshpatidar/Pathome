package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.service.StaffAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
public class StaffCapabilitiesController {
    private final StaffAccessService staffAccessService;

    public StaffCapabilitiesController(StaffAccessService staffAccessService) {
        this.staffAccessService = staffAccessService;
    }

    @GetMapping("/me/capabilities")
    public StaffCapabilitiesResponse currentCapabilities(Authentication authentication) {
        return staffAccessService.currentCapabilities(StaffAuthentication.userId(authentication));
    }
}
