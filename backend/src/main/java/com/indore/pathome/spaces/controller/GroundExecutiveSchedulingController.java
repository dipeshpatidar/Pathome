package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.GroundExecutiveCoverageCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveSchedulingActiveCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveSchedulingView;
import com.indore.pathome.spaces.dto.GroundExecutiveShiftCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveUnavailabilityCommand;
import com.indore.pathome.spaces.service.GroundExecutiveSchedulingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/operations/ground-executives/{groundExecutiveUserId}/scheduling")
public class GroundExecutiveSchedulingController {
    private final GroundExecutiveSchedulingService scheduling;

    public GroundExecutiveSchedulingController(GroundExecutiveSchedulingService scheduling) {
        this.scheduling = scheduling;
    }

    @GetMapping
    public GroundExecutiveSchedulingView get(Authentication authentication,
            @PathVariable Long groundExecutiveUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowEnd) {
        return scheduling.get(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, windowStart, windowEnd);
    }

    @PutMapping("/active")
    public GroundExecutiveSchedulingView setActive(Authentication authentication,
            @PathVariable Long groundExecutiveUserId,
            @RequestBody GroundExecutiveSchedulingActiveCommand command) {
        return scheduling.setSchedulingActive(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, command);
    }

    @PostMapping("/shifts")
    public GroundExecutiveSchedulingView addShift(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @RequestBody GroundExecutiveShiftCommand command) {
        return scheduling.addShift(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, command);
    }

    @PutMapping("/shifts/{shiftId}")
    public GroundExecutiveSchedulingView updateShift(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @PathVariable Long shiftId,
            @RequestBody GroundExecutiveShiftCommand command) {
        return scheduling.updateShift(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, shiftId, command);
    }

    @DeleteMapping("/shifts/{shiftId}")
    public GroundExecutiveSchedulingView removeShift(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @PathVariable Long shiftId,
            @RequestParam Long expectedVersion) {
        return scheduling.removeShift(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, shiftId, expectedVersion);
    }

    @PostMapping("/unavailability")
    public GroundExecutiveSchedulingView addUnavailability(Authentication authentication,
            @PathVariable Long groundExecutiveUserId,
            @RequestBody GroundExecutiveUnavailabilityCommand command) {
        return scheduling.addUnavailability(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, command);
    }

    @PutMapping("/unavailability/{intervalId}")
    public GroundExecutiveSchedulingView updateUnavailability(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @PathVariable Long intervalId,
            @RequestBody GroundExecutiveUnavailabilityCommand command) {
        return scheduling.updateUnavailability(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, intervalId, command);
    }

    @DeleteMapping("/unavailability/{intervalId}")
    public GroundExecutiveSchedulingView removeUnavailability(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @PathVariable Long intervalId,
            @RequestParam Long expectedVersion) {
        return scheduling.removeUnavailability(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, intervalId, expectedVersion);
    }

    @PutMapping("/coverage")
    public GroundExecutiveSchedulingView replaceCoverage(Authentication authentication,
            @PathVariable Long groundExecutiveUserId, @RequestBody GroundExecutiveCoverageCommand command) {
        return scheduling.replaceCoverage(VisitOperationsController.authenticatedUserId(authentication),
                groundExecutiveUserId, command);
    }
}
