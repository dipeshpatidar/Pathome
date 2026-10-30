package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityCreateCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityResponseCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityRevealCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityView;
import com.indore.pathome.spaces.service.LeadLessorAvailabilityService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class LeadLessorAvailabilityController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final LeadLessorAvailabilityService availability;

    public LeadLessorAvailabilityController(LeadLessorAvailabilityService availability) {
        this.availability = availability;
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/availability-enquiries")
    public ResponseEntity<AvailabilityView> create(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody AvailabilityCreateCommand command) {
        AvailabilityView result = availability.create(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), operationKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/availability-enquiries/{enquiryId}/lessor-contact")
    public ResponseEntity<OperationsLessorContactView> reveal(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String enquiryId,
            @RequestBody AvailabilityRevealCommand command) {
        OperationsLessorContactView result = availability.reveal(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(enquiryId, "enquiryId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/availability-enquiries/{enquiryId}/responses")
    public ResponseEntity<AvailabilityView> response(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String enquiryId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody AvailabilityResponseCommand command) {
        AvailabilityView result = availability.recordResponse(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(enquiryId, "enquiryId"), operationKey, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/availability-enquiries/{enquiryId}")
    public ResponseEntity<AvailabilityView> get(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String enquiryId) {
        AvailabilityView result = availability.get(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), uuid(enquiryId, "enquiryId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/lead-assistance-commands/{operationId}")
    public ResponseEntity<AssistanceCommandStatusView> status(
            Authentication authentication,
            @PathVariable String operationId,
            @RequestParam String action) {
        AssistanceCommandStatusView result = availability.commandStatus(
                StaffAuthentication.userId(authentication), uuid(operationId, "operationId"),
                action == null ? null : action.trim());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    private static long positiveLong(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }
}
