package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationExchangeCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneIssueCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneResendCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneVerificationView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.service.LeadPhoneVerificationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
public class LeadPhoneVerificationController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final LeadPhoneVerificationService verification;

    public LeadPhoneVerificationController(LeadPhoneVerificationService verification) {
        this.verification = verification;
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/phone-verifications")
    public ResponseEntity<PhoneVerificationView> issue(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody PhoneIssueCommand command) {
        PhoneVerificationView result = verification.issue(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), operationKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/phone-verifications/latest")
    public ResponseEntity<PhoneVerificationView> latest(Authentication authentication,
                                                        @PathVariable String leadId) {
        PhoneVerificationView result = verification.latest(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/phone-verifications/{challengeId}")
    public ResponseEntity<PhoneVerificationView> get(Authentication authentication,
                                                      @PathVariable String leadId,
                                                      @PathVariable String challengeId) {
        PhoneVerificationView result = verification.get(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(challengeId, "challengeId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/phone-verifications/commands/{operationId}")
    public ResponseEntity<AssistanceCommandStatusView> commandStatus(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String operationId,
            @RequestParam String action) {
        AssistanceCommandStatusView result = verification.commandStatus(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(operationId, "operationId"), action.trim());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/phone-verifications/{challengeId}/resend")
    public ResponseEntity<PhoneVerificationView> resend(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String challengeId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody PhoneResendCommand command) {
        PhoneVerificationView result = verification.resend(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(challengeId, "challengeId"), operationKey, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/phone-verifications/{challengeId}/confirm")
    public ResponseEntity<PhoneVerificationView> confirmOperator(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String challengeId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody PhoneConfirmCommand command) {
        PhoneVerificationView result = verification.confirmFromOperator(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(challengeId, "challengeId"), operationKey, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/lead-phone-verifications/exchange")
    public ResponseEntity<PhoneConfirmationView> exchange(
            @RequestBody PhoneConfirmationExchangeCommand command) {
        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(NO_STORE)
                .body(verification.exchange(command));
    }

    @PostMapping("/api/v1/lead-phone-verifications/confirm")
    public ResponseEntity<PhoneConfirmationView> confirmCustomer(
            @RequestBody PhoneConfirmationConfirmCommand command) {
        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(NO_STORE)
                .body(verification.confirmCustomer(command));
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
