package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.StaffGrantCommand;
import com.indore.pathome.spaces.dto.StaffGrantView;
import com.indore.pathome.spaces.dto.StaffStateCommand;
import com.indore.pathome.spaces.dto.StaffUserAccessState;
import com.indore.pathome.spaces.service.StaffAdministrationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/admin")
public class StaffAdminController {
    private final StaffAdministrationService staffAdministration;

    public StaffAdminController(StaffAdministrationService staffAdministration) {
        this.staffAdministration = staffAdministration;
    }

    @GetMapping("/users/{userId}")
    public StaffUserAccessState inspectUser(Authentication authentication, @PathVariable Long userId) {
        return staffAdministration.inspectUser(StaffAuthentication.userId(authentication), userId);
    }

    @PostMapping("/users/{userId}/activate")
    public StaffUserAccessState activate(Authentication authentication, @PathVariable Long userId,
                                         @RequestBody StaffStateCommand command) {
        return staffAdministration.activateStaff(StaffAuthentication.userId(authentication), userId, command);
    }

    @PostMapping("/users/{userId}/deactivate")
    public StaffUserAccessState deactivate(Authentication authentication, @PathVariable Long userId,
                                           @RequestBody StaffStateCommand command) {
        return staffAdministration.deactivateStaff(StaffAuthentication.userId(authentication), userId, command);
    }

    @PostMapping("/users/{userId}/grants")
    public StaffGrantView createGrant(Authentication authentication, @PathVariable Long userId,
                                      @RequestBody StaffGrantCommand command) {
        return staffAdministration.createGrant(StaffAuthentication.userId(authentication), userId, command);
    }

    @PostMapping("/grants/{grantId}/revoke")
    public StaffGrantView revokeGrant(Authentication authentication, @PathVariable Long grantId,
                                      @RequestBody com.indore.pathome.spaces.dto.StaffRevocationCommand command) {
        if (command == null) throw new IllegalArgumentException("A revocation reason is required");
        return staffAdministration.revokeGrant(StaffAuthentication.userId(authentication), grantId,
                command.reasonCode());
    }
}
