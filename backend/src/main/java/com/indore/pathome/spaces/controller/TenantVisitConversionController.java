package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssociationClaimView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssociationDecisionCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.CreateOwnVisitRequestCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.OwnVisitRequestView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitCustomerResponseCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitCustomerResponseView;
import com.indore.pathome.spaces.security.PathomeAuthenticationIdentity;
import com.indore.pathome.spaces.service.LeadVisitAcceptanceService;
import com.indore.pathome.spaces.service.LeadVisitAssociationService;
import com.indore.pathome.spaces.service.PropertyVisitRequestApplicationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TenantVisitConversionController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final LeadVisitAcceptanceService acceptance;
    private final LeadVisitAssociationService associations;
    private final PropertyVisitRequestApplicationService requestApplication;

    public TenantVisitConversionController(LeadVisitAcceptanceService acceptance,
                                           LeadVisitAssociationService associations,
                                           PropertyVisitRequestApplicationService requestApplication) {
        this.acceptance = acceptance;
        this.associations = associations;
        this.requestApplication = requestApplication;
    }

    @PostMapping("/api/v1/tenant/property-visit-commands")
    public ResponseEntity<OwnVisitRequestView> requestVisit(
            Authentication authentication,
            @CookieValue(name = LeadVisitHandoffController.COOKIE_NAME, required = false)
            String continuationToken,
            @RequestBody CreateOwnVisitRequestCommand command) {
        OwnVisitRequestView result = acceptance.requestVisit(
                PathomeAuthenticationIdentity.requireUserId(authentication),
                continuationToken, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/tenant/property-visit-commands/{operationId}")
    public ResponseEntity<AssistanceCommandStatusView> commandStatus(
            Authentication authentication,
            @PathVariable String operationId) {
        AssistanceCommandStatusView result = requestApplication.commandStatus(
                PathomeAuthenticationIdentity.requireUserId(authentication),
                uuid(operationId, "operationId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/tenant/lead-visit-proposals/{contextId}/responses")
    public ResponseEntity<VisitCustomerResponseView> respond(
            Authentication authentication,
            @PathVariable String contextId,
            @CookieValue(name = LeadVisitHandoffController.COOKIE_NAME, required = false)
            String continuationToken,
            @RequestBody VisitCustomerResponseCommand command) {
        VisitCustomerResponseView result = associations.recordCustomerResponse(
                PathomeAuthenticationIdentity.requireUserId(authentication),
                uuid(contextId, "contextId"), continuationToken, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/tenant/lead-visit-proposals/{contextId}/association-claims/{claimId}/withdraw")
    public ResponseEntity<AssociationClaimView> withdraw(
            Authentication authentication,
            @PathVariable String contextId,
            @PathVariable String claimId,
            @CookieValue(name = LeadVisitHandoffController.COOKIE_NAME, required = false)
            String continuationToken,
            @RequestHeader("Idempotency-Key") String operationKey) {
        AssociationClaimView result = associations.withdraw(
                PathomeAuthenticationIdentity.requireUserId(authentication),
                uuid(contextId, "contextId"), continuationToken,
                uuid(claimId, "claimId"), operationKey);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/visit-proposals/{proposalId}/association-claims/{claimId}/decision")
    public ResponseEntity<AssociationClaimView> decide(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String proposalId,
            @PathVariable String claimId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody AssociationDecisionCommand command) {
        AssociationClaimView result = associations.decide(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(proposalId, "proposalId"), uuid(claimId, "claimId"),
                operationKey, command);
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
