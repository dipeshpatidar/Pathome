package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalCreateCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalIssuedView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.service.LeadVisitProposalService;
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
public class LeadVisitProposalController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final LeadVisitProposalService proposals;

    public LeadVisitProposalController(LeadVisitProposalService proposals) {
        this.proposals = proposals;
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/visit-proposals")
    public ResponseEntity<VisitProposalIssuedView> issue(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestBody VisitProposalCreateCommand command) {
        VisitProposalIssuedView result = proposals.issue(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                operationKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/visit-proposals/{proposalId}")
    public ResponseEntity<VisitProposalView> get(Authentication authentication,
                                                 @PathVariable String leadId,
                                                 @PathVariable String proposalId) {
        VisitProposalView result = proposals.get(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), uuid(proposalId, "proposalId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/api/v1/operations/leads/{leadId}/visit-proposals/commands/{operationId}")
    public ResponseEntity<AssistanceCommandStatusView> commandStatus(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String operationId,
            @RequestParam String action) {
        AssistanceCommandStatusView result = proposals.commandStatus(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(operationId, "operationId"), action.trim());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/api/v1/operations/leads/{leadId}/visit-proposals/{proposalId}/revoke")
    public ResponseEntity<VisitProposalView> revoke(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String proposalId,
            @RequestHeader("Idempotency-Key") String operationKey) {
        VisitProposalView result = proposals.revoke(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                uuid(proposalId, "proposalId"), operationKey);
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
