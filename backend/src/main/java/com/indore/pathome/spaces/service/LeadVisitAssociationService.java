package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssociationClaimView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssociationDecisionCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitCustomerResponseCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitCustomerResponseView;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadVisitAssociationClaim;
import com.indore.pathome.spaces.entity.LeadVisitAssociationStatus;
import com.indore.pathome.spaces.entity.LeadVisitContinuation;
import com.indore.pathome.spaces.entity.LeadVisitCustomerResponse;
import com.indore.pathome.spaces.entity.LeadVisitCustomerResponseState;
import com.indore.pathome.spaces.entity.LeadVisitLink;
import com.indore.pathome.spaces.entity.LeadVisitProposal;
import com.indore.pathome.spaces.entity.LeadVisitProposalState;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadVisitAssociationClaimRepository;
import com.indore.pathome.spaces.repository.LeadVisitCustomerResponseRepository;
import com.indore.pathome.spaces.repository.LeadVisitLinkRepository;
import com.indore.pathome.spaces.repository.LeadVisitProposalRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class LeadVisitAssociationService {
    private static final String DECISION_ACTION = "LEAD_VISIT_ASSOCIATION_DECISION";
    private static final String WITHDRAW_ACTION = "LEAD_VISIT_ASSOCIATION_WITHDRAW";

    private final LeadVisitProposalService proposals;
    private final LeadVisitProposalRepository proposalRepository;
    private final LeadVisitCustomerResponseRepository responses;
    private final LeadVisitAssociationClaimRepository claims;
    private final LeadVisitLinkRepository links;
    private final LeadRoutingQueueRepository leads;
    private final UserRepository users;
    private final PropertyVisitRequestApplicationService requestApplication;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final JdbcTemplate jdbc;

    public LeadVisitAssociationService(
            LeadVisitProposalService proposals,
            LeadVisitProposalRepository proposalRepository,
            LeadVisitCustomerResponseRepository responses,
            LeadVisitAssociationClaimRepository claims,
            LeadVisitLinkRepository links,
            LeadRoutingQueueRepository leads,
            UserRepository users,
            PropertyVisitRequestApplicationService requestApplication,
            LeadAssistanceCommandReceiptRepository receipts,
            LeadAccessService leadAccess,
            OperationalSecurityGuards guards,
            OperationalAuditService audit,
            DatabaseClock databaseClock,
            JdbcTemplate jdbc) {
        this.proposals = proposals;
        this.proposalRepository = proposalRepository;
        this.responses = responses;
        this.claims = claims;
        this.links = links;
        this.leads = leads;
        this.users = users;
        this.requestApplication = requestApplication;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.guards = guards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.jdbc = jdbc;
    }

    @Transactional
    public VisitCustomerResponseView recordCustomerResponse(
            Long tenantId, UUID contextId, String continuationToken,
            VisitCustomerResponseCommand command) {
        validateResponse(command);
        LeadVisitContinuation continuation = proposals.requireContinuation(
                continuationToken, contextId, null);
        LeadVisitProposal proposal = proposalRepository.findLockedById(
                continuation.getProposal().getId())
                .orElseThrow(LeadVisitAssociationService::hidden);
        User tenant = requireTenant(tenantId);
        Instant lockedNow = databaseClock.now();
        if (proposal.getState() != LeadVisitProposalState.OPEN
                || !lockedNow.isBefore(proposal.getExpiresAt())) {
            throw hidden();
        }
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        proposal.getId() + ":VISIT_RESPONSE:" + tenantId),
                resultSet -> null);
        Instant now = lockedNow;
        PropertyVisitRequest request = null;
        if (command.response() == LeadVisitCustomerResponseState.REQUEST_RECORDED) {
            if (command.requestId() == null) {
                throw new IllegalArgumentException("The customer's exact Visit Request is required");
            }
            request = requestApplication.requireOwnRequest(tenantId, command.requestId());
            if (!proposal.getListingId().equals(request.getListing().getId())) {
                throw hidden();
            }
        }
        LeadVisitCustomerResponse response = responses
                .findByProposal_IdAndUser_Id(proposal.getId(), tenantId).orElse(null);
        if (response == null) {
            response = responses.saveAndFlush(new LeadVisitCustomerResponse(UUID.randomUUID(),
                    proposal, tenant, command.response(), request == null ? null : request.getId(),
                    now));
        } else {
            if (command.expectedResponseVersion() != null) {
                requireResponseVersion(response, command.expectedResponseVersion());
            }
            if (response.getResponseState() == LeadVisitCustomerResponseState.REQUEST_RECORDED
                    && command.response() == LeadVisitCustomerResponseState.DECLINED) {
                throw new VisitOperationsConflictException(
                        "A recorded Visit Request response cannot be changed to declined here");
            }
            boolean sameResponse = response.getResponseState() == command.response()
                    && Objects.equals(response.getRequestId(),
                    request == null ? null : request.getId());
            if (!sameResponse) {
                response.update(command.response(),
                        request == null ? null : request.getId(), now);
                response = responses.saveAndFlush(response);
            }
        }

        LeadVisitAssociationClaim claim = null;
        if (command.response() == LeadVisitCustomerResponseState.DECLINED) {
            var ownClaim = claims.findByProposal_IdAndUser_Id(proposal.getId(), tenantId)
                    .filter(existing -> existing.getState() == LeadVisitAssociationStatus.PENDING);
            if (ownClaim.isPresent()) {
                        var existing = claims.findLockedById(ownClaim.get().getId())
                                .orElseThrow(LeadVisitAssociationService::hidden);
                        if (existing.getState() != LeadVisitAssociationStatus.PENDING) {
                            throw new VisitOperationsConflictException(
                                    "The association claim changed while your response was being saved");
                        }
                        existing.withdraw(now);
                        claims.saveAndFlush(existing);
            }
            if (proposal.getAssociationStatus() == LeadVisitAssociationStatus.PENDING
                    && claims.countByProposal_IdAndState(proposal.getId(),
                        LeadVisitAssociationStatus.PENDING) == 0) {
                proposal.association(LeadVisitAssociationStatus.WITHDRAWN);
                proposalRepository.saveAndFlush(proposal);
            }
        } else if (command.associateEnquiry()) {
            if (!command.explicitEnquiryOwnershipConsent()
                    || command.consentWordingVersion() == null
                    || command.consentWordingVersion().isBlank()
                    || command.consentWordingVersion().length() > 64) {
                throw new IllegalArgumentException(
                        "Explicit enquiry-ownership consent and its wording version are required");
            }
            claim = claims.findByProposal_IdAndUser_Id(proposal.getId(), tenantId).orElse(null);
            if (claim == null) {
                claim = claims.saveAndFlush(new LeadVisitAssociationClaim(UUID.randomUUID(),
                        proposal, response, tenant, request.getId(),
                        command.consentWordingVersion().trim(), now));
                proposal.markAssociationPending();
                proposalRepository.saveAndFlush(proposal);
            } else if (claim.getState() != LeadVisitAssociationStatus.PENDING
                    && claim.getState() != LeadVisitAssociationStatus.CONFIRMED) {
                throw new VisitOperationsConflictException(
                        "This enquiry association cannot be reactivated from its current state");
            } else {
                claim = claims.findLockedById(claim.getId())
                        .orElseThrow(LeadVisitAssociationService::hidden);
            }
        }
        Map<String, Object> responseDetails = new LinkedHashMap<>();
        if (request != null) responseDetails.put("requestId", request.getId());
        responseDetails.put("associationState", claim == null
                ? LeadVisitAssociationStatus.UNAVAILABLE.name()
                : claim.getState().name());
        audit.recordUserEvent(tenant, "LEAD_VISIT_CUSTOMER_RESPONSE_RECORDED",
                "LEAD_VISIT_PROPOSAL", positiveAuditTarget(proposal.getId()),
                "CUSTOMER_OWN_ACCOUNT_ACTION", proposal.getLead().getSupportedCity(),
                proposal.getLead().getOperatingTeam(),
                responseDetails);
        return new VisitCustomerResponseView(response.getId(), proposal.getId(),
                response.getResponseState(), response.getRequestId(),
                claim == null ? proposal.getAssociationStatus() : claim.getState(),
                response.getRecordedAt(), response.getVersion(),
                claim == null
                        ? "Your own response was recorded. No Lead association was requested."
                        : "Your Visit Request is recorded. An authorized OE must review the optional enquiry association.");
    }

    @Transactional
    public VisitCustomerResponseView recordOwnRequest(Long tenantId, UUID contextId,
                                                      String continuationToken, Long requestId,
                                                      boolean associateEnquiry,
                                                      String consentWordingVersion,
                                                      Long expectedResponseVersion) {
        return recordCustomerResponse(tenantId, contextId, continuationToken,
                new VisitCustomerResponseCommand(
                        LeadVisitCustomerResponseState.REQUEST_RECORDED, requestId,
                        expectedResponseVersion, associateEnquiry, associateEnquiry,
                        consentWordingVersion));
    }

    @Transactional
    public AssociationClaimView withdraw(Long tenantId, UUID contextId, String continuationToken,
                                         UUID claimId, String operationKey) {
        UUID operationId = operationId(operationKey);
        LeadVisitContinuation continuation = proposals.requireContinuation(
                continuationToken, contextId, null);
        LeadVisitAssociationClaim seed = claims.findById(claimId).orElseThrow(
                LeadVisitAssociationService::hidden);
        var lead = leads.findLockedById(seed.getProposal().getLead().getId())
                .orElseThrow(LeadVisitAssociationService::hidden);
        var proposalLocked = proposalRepository.findLockedById(seed.getProposal().getId())
                .orElseThrow(LeadVisitAssociationService::hidden);
        LeadVisitAssociationClaim claim = claims.findLockedById(claimId).orElseThrow(
                LeadVisitAssociationService::hidden);
        if (!claim.getProposal().getId().equals(continuation.getProposal().getId())
                || !tenantId.equals(claim.getUser().getId())
                || !lead.getId().equals(proposalLocked.getLead().getId())) {
            throw hidden();
        }
        LeadAssistanceCommandReceipt replay = receipts
                .findByActor_IdAndOperationIdAndAction(tenantId, operationId, WITHDRAW_ACTION)
                .orElse(null);
        if (replay != null) {
            if (!claimId.equals(LeadService.parseStoredUuidReference(
                    replay.getResultReference()))) throw hidden();
            return claimView(claim, "Association claim was already withdrawn.");
        }
        if (claim.getState() != LeadVisitAssociationStatus.PENDING) {
            throw new VisitOperationsConflictException(
                    "Only a pending association claim can be withdrawn");
        }
        Instant now = databaseClock.now();
        claim.withdraw(now);
        claims.saveAndFlush(claim);
        LeadVisitProposal proposal = claim.getProposal();
        proposal.association(LeadVisitAssociationStatus.WITHDRAWN);
        proposalRepository.saveAndFlush(proposal);
        User tenant = requireTenant(tenantId);
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(tenant, operationId,
                WITHDRAW_ACTION, claimId.toString(), claimId.toString(), now));
        audit.recordUserEvent(tenant, "LEAD_VISIT_ASSOCIATION_WITHDRAWN",
                "LEAD_VISIT_PROPOSAL", positiveAuditTarget(proposal.getId()),
                "CUSTOMER_WITHDREW_ASSOCIATION", proposal.getLead().getSupportedCity(),
                proposal.getLead().getOperatingTeam(),
                Map.of("associationState", LeadVisitAssociationStatus.WITHDRAWN.name()));
        return claimView(claim, "The optional enquiry association was withdrawn. Your Visit Request remains recorded.");
    }

    @Transactional
    public AssociationClaimView decide(Long actorId, Long leadId, UUID proposalId,
                                       UUID claimId, String operationKey,
                                       AssociationDecisionCommand command) {
        if (command == null || command.expectedClaimVersion() == null
                || command.decision() == null) {
            throw new IllegalArgumentException("Decision and current claim version are required");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        UUID operationId = operationId(operationKey);
        LeadAssistanceCommandReceipt replay = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, DECISION_ACTION)
                .orElse(null);
        if (replay != null) {
            LeadVisitAssociationClaim existing = claims.findById(
                            LeadService.parseStoredUuidReference(replay.getResultReference()))
                    .orElseThrow(LeadVisitAssociationService::hidden);
            if (!existing.getId().equals(claimId)
                    || !existing.getProposal().getId().equals(proposalId)) throw hidden();
            requireCurrentOwner(actorId, leadId, existing.getProposal());
            String expectedHash = payloadHash(leadId + "|" + proposalId + "|" + claimId
                    + "|" + command.decision() + "|" + command.expectedClaimVersion()
                    + "|" + command.reasonCode());
            if (!expectedHash.equals(replay.getPayloadHash())) throw hidden();
            return claimView(existing, "Association decision was already recorded.");
        }

        var lead = leads.findLockedById(leadId).orElseThrow(LeadVisitAssociationService::hidden);
        LeadVisitProposal proposal = proposalRepository.findLockedById(proposalId)
                .orElseThrow(LeadVisitAssociationService::hidden);
        LeadVisitAssociationClaim claim = claims.findLockedById(claimId)
                .orElseThrow(LeadVisitAssociationService::hidden);
        if (!proposal.getLead().getId().equals(leadId)
                || !claim.getProposal().getId().equals(proposalId)) throw hidden();
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null) throw hidden();
        guards.acquire(java.util.List.of(lead.getSupportedCity().getId()),
                java.util.List.of(lead.getOperatingTeam().getId()),
                java.util.List.of(actorId, claim.getUser().getId()));
        requireCurrentOwner(actorId, leadId, proposal);
        if (lead.getStatus() != com.indore.pathome.spaces.entity.LeadStatus.NEW
                && lead.getStatus() != com.indore.pathome.spaces.entity.LeadStatus.QUALIFYING
                && lead.getStatus() != com.indore.pathome.spaces.entity.LeadStatus.QUALIFIED) {
            throw new VisitOperationsConflictException(
                    "An association decision is unavailable for this Lead state");
        }
        if (!Objects.equals(claim.getVersion(), command.expectedClaimVersion())) {
            throw new VisitOperationsConflictException(
                    "The association claim changed while it was being reviewed");
        }
        if (claim.getState() != LeadVisitAssociationStatus.PENDING) {
            throw new VisitOperationsConflictException(
                    "This association claim is no longer pending");
        }
        String decision = command.decision().trim().toUpperCase(java.util.Locale.ROOT);
        Instant now = databaseClock.now();
        User actor = users.findById(actorId).orElseThrow(LeadVisitAssociationService::hidden);
        LeadVisitAssociationStatus next;
        if ("CONFIRM".equals(decision)) {
            if (lead.getLinkedUser() != null
                    && !lead.getLinkedUser().getId().equals(claim.getUser().getId())) {
                throw new VisitOperationsConflictException(
                        "This Lead is already linked to a different User and cannot be overwritten");
            }
            PropertyVisitRequest request = requestApplication.requireOwnRequest(
                    claim.getUser().getId(), claim.getRequestId());
            if (!proposal.getListingId().equals(request.getListing().getId())) throw hidden();
            var existingLinks = links.findAllByLead_IdOrderByConfirmedAtAscIdAsc(leadId);
            if (existingLinks.stream().anyMatch(link ->
                    !Objects.equals(link.getUser().getId(), claim.getUser().getId()))) {
                throw new VisitOperationsConflictException(
                        "This Lead is already linked to a different User and cannot be overwritten");
            }
            boolean sameRequestLinked = existingLinks.stream().anyMatch(link ->
                    Objects.equals(link.getRequestId(), claim.getRequestId()));
            boolean samePropertyLinked = existingLinks.stream().anyMatch(link ->
                    Objects.equals(link.getListingId(), proposal.getListingId()));
            if (sameRequestLinked != samePropertyLinked) {
                throw new VisitOperationsConflictException(
                        "This Lead already has inconsistent association history for the property");
            }
            if (samePropertyLinked) {
                LeadVisitLink link = existingLinks.stream()
                        .filter(value -> Objects.equals(value.getListingId(),
                                proposal.getListingId()))
                        .findFirst().orElseThrow(LeadVisitAssociationService::hidden);
                if (!Objects.equals(link.getRequestId(), claim.getRequestId())
                        || !Objects.equals(link.getProposal().getId(), proposal.getId())) {
                    throw new VisitOperationsConflictException(
                            "This property already has a different confirmed association");
                }
            }
            lead.setLinkedUser(claim.getUser());
            leads.saveAndFlush(lead);
            if (!samePropertyLinked) {
                links.saveAndFlush(new LeadVisitLink(UUID.randomUUID(), lead, claim.getUser(),
                        proposal, request.getId(), now));
            }
            next = LeadVisitAssociationStatus.CONFIRMED;
        } else if ("DISPUTE".equals(decision)) {
            next = LeadVisitAssociationStatus.DISPUTED;
        } else {
            throw new IllegalArgumentException("Decision must be CONFIRM or DISPUTE");
        }
        claim.decide(next, actor, command.reasonCode(), now);
        claims.saveAndFlush(claim);
        proposal.association(next);
        proposalRepository.saveAndFlush(proposal);
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, DECISION_ACTION,
                payloadHash(leadId + "|" + proposalId + "|" + claimId + "|"
                        + command.decision() + "|" + command.expectedClaimVersion()
                        + "|" + command.reasonCode()),
                claimId.toString(), now));
        audit.recordUserEvent(actor, "LEAD_VISIT_ASSOCIATION_DECIDED", "LEAD", leadId,
                command.reasonCode(), lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("associationState", next.name(),
                        "requestId", claim.getRequestId()));
        String message = next == LeadVisitAssociationStatus.CONFIRMED
                ? "The customer's own Visit Request was associated with this Lead. The Request status is unchanged."
                : "The association was disputed. The customer's Visit Request remains recorded.";
        return claimView(claim, message);
    }

    private void requireCurrentOwner(Long actorId, Long leadId, LeadVisitProposal proposal) {
        var lead = leads.findById(leadId).orElseThrow(LeadVisitAssociationService::hidden);
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireContactOwner(context, lead, actorId);
        if (!proposal.getLead().getId().equals(leadId)
                || lead.getSupportedCity() == null || lead.getOperatingTeam() == null
                || !guards.cityIsActive(lead.getSupportedCity().getId())
                || !guards.teamIsActive(lead.getOperatingTeam().getId())
                || !Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())) {
            throw hidden();
        }
    }

    private User requireTenant(Long tenantId) {
        return users.findById(tenantId)
                .filter(user -> user.getRole() == com.indore.pathome.spaces.entity.Role.ROLE_TENANT)
                .orElseThrow(() -> new EntityNotFoundException("Tenant account not found"));
    }

    private static void requireResponseVersion(LeadVisitCustomerResponse response, Long expected) {
        if (expected == null || !expected.equals(response.getVersion())) {
            throw new VisitOperationsConflictException(
                    "Your earlier invitation response changed. Refresh before retrying");
        }
    }

    private static void validateResponse(VisitCustomerResponseCommand command) {
        if (command == null || command.response() == null) {
            throw new IllegalArgumentException("A customer response is required");
        }
        if (command.response() == LeadVisitCustomerResponseState.REQUEST_RECORDED
                && command.requestId() == null) {
            throw new IllegalArgumentException("A recorded Visit Request is required");
        }
    }

    private static AssociationClaimView claimView(LeadVisitAssociationClaim claim, String message) {
        return new AssociationClaimView(claim.getId(), claim.getProposal().getId(),
                claim.getRequestId(), claim.getState(), claim.getCreatedAt(),
                claim.getDecidedAt(), claim.getVersion(), message);
    }

    private static long positiveAuditTarget(UUID id) {
        return id.getLeastSignificantBits() & Long.MAX_VALUE;
    }

    private static UUID operationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID");
        }
    }

    private static String payloadHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Association command could not be fingerprinted");
        }
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException("Visit invitation not found");
    }
}
