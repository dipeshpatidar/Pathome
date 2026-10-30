package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.ProposalPropertyView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitHandoffExchangeCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitHandoffView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalCreateCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalIssuedView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssociationClaimReviewView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadShortlist;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.LeadVisitContinuation;
import com.indore.pathome.spaces.entity.LeadVisitProposal;
import com.indore.pathome.spaces.entity.LeadVisitProposalState;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import com.indore.pathome.spaces.repository.LeadVisitContinuationRepository;
import com.indore.pathome.spaces.repository.LeadVisitAssociationClaimRepository;
import com.indore.pathome.spaces.repository.LeadVisitProposalRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class LeadVisitProposalService {
    private static final Duration PROPOSAL_LIFETIME = Duration.ofMinutes(30);
    private static final String CREATE_ACTION = "LEAD_VISIT_PROPOSAL_CREATE";
    private static final String REVOKE_ACTION = "LEAD_VISIT_PROPOSAL_REVOKE";

    private final LeadRoutingQueueRepository leads;
    private final LeadShortlistRepository shortlists;
    private final LeadShortlistItemRepository shortlistItems;
    private final LeadContactPreferenceRepository preferences;
    private final LeadVisitProposalRepository proposals;
    private final LeadVisitContinuationRepository continuations;
    private final LeadVisitAssociationClaimRepository associationClaims;
    private final LeadActivityRepository activities;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final LeadPublicPropertyService publicProperties;
    private final LeadAssistanceSecretService secrets;
    private final LeadDataProtection protection;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final String configuredOrigin;

    public LeadVisitProposalService(
            LeadRoutingQueueRepository leads,
            LeadShortlistRepository shortlists,
            LeadShortlistItemRepository shortlistItems,
            LeadContactPreferenceRepository preferences,
            LeadVisitProposalRepository proposals,
            LeadVisitContinuationRepository continuations,
            LeadVisitAssociationClaimRepository associationClaims,
            LeadActivityRepository activities,
            LeadAssistanceCommandReceiptRepository receipts,
            LeadAccessService leadAccess,
            StaffAccessService staffAccess,
            OperationalSecurityGuards guards,
            OperationalAuditService audit,
            TransactionalCommandBounds commandBounds,
            DatabaseClock databaseClock,
            LeadPublicPropertyService publicProperties,
            LeadAssistanceSecretService secrets,
            LeadDataProtection protection,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            @Value("${pathome.crm.public-origin:}") String configuredOrigin) {
        this.leads = leads;
        this.shortlists = shortlists;
        this.shortlistItems = shortlistItems;
        this.preferences = preferences;
        this.proposals = proposals;
        this.continuations = continuations;
        this.associationClaims = associationClaims;
        this.activities = activities;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.publicProperties = publicProperties;
        this.secrets = secrets;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.configuredOrigin = configuredOrigin;
    }

    @Transactional
    public VisitProposalIssuedView issue(Long actorId, Long leadId, String operationKey,
                                         VisitProposalCreateCommand command) {
        validateIssue(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "command", command));
        lockOperation(actorId, CREATE_ACTION, operationId);
        LeadVisitProposal replay = requireReceipt(actorId, leadId, operationId,
                CREATE_ACTION, payloadHash);
        if (replay != null) {
            requireCurrentOwner(actorId, replay.getLead());
            return issuedView(replay, null);
        }

        commandBounds.apply();
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(LeadVisitProposalService::hiddenLead);
        // A receipt miss must conceal foreign/missing ownership before lifecycle or version checks.
        requireCurrentOwner(actorId, lead);
        requireWorkingLead(lead);
        requireVersion(lead, command.expectedLeadVersion());
        LeadShortlist shortlist = shortlists.findLockedById(command.shortlistId())
                .orElseThrow(LeadVisitProposalService::hidden);
        if (!shortlist.getLead().getId().equals(leadId)
                || !Objects.equals(shortlist.getVersion(), command.expectedShortlistVersion())) {
            throw new VisitOperationsConflictException(
                    "The saved shortlist changed while the invitation was being prepared");
        }
        requireShortlistMembership(shortlist, command.listingId());
        acquireGuards(actorId, lead);
        requireCurrentOwner(actorId, lead);
        var eligible = publicProperties.requireCurrentPublicPropertiesForUpdate(lead,
                List.of(command.listingId()));
        if (eligible.size() != 1
                || !Objects.equals(eligible.get(0).listingVersion(), command.expectedListingVersion())) {
            throw new VisitOperationsConflictException(
                    "The property changed while the invitation was being prepared");
        }
        LeadContactPreference preference = preferences
                .findFirstByLead_IdOrderByRevisionDesc(leadId)
                .filter(value -> value.getRevision().equals(command.contactPreferenceRevision()))
                .filter(value -> value.getConsentStatus() == LeadConsentStatus.GRANTED)
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "Current customer consent is required before preparing a Visit invitation"));

        Instant now = databaseClock.now();
        for (LeadVisitProposal previous : proposals
                .findAllByLead_IdAndListingIdAndStateOrderByCreatedAtDesc(leadId,
                        command.listingId(), LeadVisitProposalState.OPEN)) {
            previous.supersede(now);
            proposals.save(previous);
        }
        String token = secrets.opaqueToken();
        LeadVisitProposal proposal = new LeadVisitProposal(UUID.randomUUID(), lead,
                command.shortlistId(), command.listingId(), preference.getRevision(),
                lead.getVersion(), shortlist.getVersion(), command.expectedListingVersion(),
                secrets.visitProposalTokenHash(token), staffAccess.requireAttributableUser(actorId),
                now, now.plus(PROPOSAL_LIFETIME));
        proposal = proposals.saveAndFlush(proposal);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_VISIT_PROPOSAL_CREATED", null, now, null));
        audit.recordUserEvent(actor, "LEAD_VISIT_PROPOSAL_CREATED", "LEAD", leadId,
                "EXACT_PROPERTY_CUSTOMER_ACCEPTANCE", lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("listingId", command.listingId()));
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, CREATE_ACTION,
                payloadHash, proposal.getId().toString(), now));
        return issuedView(proposal, handoffUrl(token, proposal.getListingId()));
    }

    @Transactional
    public VisitProposalView get(Long actorId, Long leadId, UUID proposalId) {
        LeadVisitProposal proposal = proposals.findByIdAndLead_Id(proposalId, leadId)
                .orElseThrow(LeadVisitProposalService::hidden);
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireReadable(context, proposal.getLead(), actorId);
        expireIfNeeded(proposal, databaseClock.now());
        return view(proposal);
    }

    @Transactional
    public VisitProposalView revoke(Long actorId, Long leadId, UUID proposalId,
                                    String operationKey) {
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "proposalId", proposalId,
                "action", "REVOKE"));
        lockOperation(actorId, REVOKE_ACTION, operationId);
        LeadVisitProposal replay = requireReceipt(actorId, leadId, operationId,
                REVOKE_ACTION, payloadHash);
        if (replay != null) {
            requireCurrentOwner(actorId, replay.getLead());
            return view(replay);
        }
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(LeadVisitProposalService::hidden);
        LeadVisitProposal proposal = proposals.findLockedById(proposalId)
                .orElseThrow(LeadVisitProposalService::hidden);
        if (!proposal.getLead().getId().equals(leadId)) throw hidden();
        acquireGuards(actorId, lead);
        requireCurrentOwner(actorId, lead);
        Instant now = databaseClock.now();
        proposal.revoke(now);
        proposals.saveAndFlush(proposal);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_VISIT_PROPOSAL_REVOKED", null, now, null));
        audit.recordUserEvent(actor, "LEAD_VISIT_PROPOSAL_REVOKED", "LEAD", leadId,
                "OPERATOR_REVOKED_INVITATION", lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("listingId", proposal.getListingId()));
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, REVOKE_ACTION,
                payloadHash, proposalId.toString(), now));
        return view(proposal);
    }

    @Transactional(readOnly = true)
    public AssistanceCommandStatusView commandStatus(Long actorId, Long leadId,
                                                      UUID operationId, String action) {
        if (action == null || (!CREATE_ACTION.equals(action) && !REVOKE_ACTION.equals(action))) {
            throw new IllegalArgumentException("A supported Visit-invitation action is required");
        }
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) {
            return new AssistanceCommandStatusView("NOT_RECORDED", action, null, null);
        }
        LeadVisitProposal proposal = requireReceipt(actorId, leadId, operationId, action,
                receipt.getPayloadHash());
        if (proposal == null || !leadId.equals(proposal.getLead().getId())) throw hidden();
        requireCurrentOwner(actorId, proposal.getLead());
        return new AssistanceCommandStatusView("RECORDED", action,
                receipt.getResultReference(), receipt.getCompletedAt());
    }

    @Transactional
    public HandoffExchange exchange(VisitHandoffExchangeCommand command) {
        if (command == null) throw new IllegalArgumentException("Visit invitation token is required");
        String tokenHash;
        try {
            tokenHash = secrets.visitProposalTokenHash(command.token());
        } catch (IllegalArgumentException exception) {
            throw hidden();
        }
        LeadVisitProposal proposal = proposals.findByTokenHash(tokenHash).orElseThrow(
                LeadVisitProposalService::hidden);
        Instant now = databaseClock.now();
        expireIfNeeded(proposal, now);
        if (proposal.getState() != LeadVisitProposalState.OPEN) throw hidden();
        LeadShortlist currentShortlist = shortlists.findLockedById(proposal.getShortlistId())
                .orElseThrow(LeadVisitProposalService::hidden);
        if (!currentShortlist.getLead().getId().equals(proposal.getLead().getId())
                || !Objects.equals(currentShortlist.getVersion(), proposal.getShortlistVersion())) {
            throw hidden();
        }
        requireShortlistMembership(currentShortlist, proposal.getListingId());
        ProposalPropertyView property = publicProperty(proposal, now);
        String continuationToken = secrets.opaqueToken();
        LeadVisitContinuation continuation = continuations.saveAndFlush(
                new LeadVisitContinuation(UUID.randomUUID(), proposal,
                        secrets.visitContinuationTokenHash(continuationToken), now,
                        min(proposal.getExpiresAt(), now.plus(PROPOSAL_LIFETIME))));
        return new HandoffExchange(continuationToken,
                new VisitHandoffView(continuation.getId(), proposal.getId(),
                        continuation.getExpiresAt(), property));
    }

    @Transactional
    public VisitHandoffView current(String continuationToken) {
        LeadVisitContinuation context = requireContinuation(continuationToken, null, null);
        Instant now = databaseClock.now();
        if (now.isAfter(context.getExpiresAt())
                || context.getProposal().getState() != LeadVisitProposalState.OPEN
                || now.isAfter(context.getProposal().getExpiresAt())) {
            throw hidden();
        }
        context.seen(now);
        continuations.saveAndFlush(context);
        return new VisitHandoffView(context.getId(), context.getProposal().getId(),
                context.getExpiresAt(), publicProperty(context.getProposal(), now));
    }

    @Transactional(readOnly = true)
    public LeadVisitContinuation requireContinuation(String continuationToken, UUID contextId,
                                                      Long listingId) {
        if (continuationToken == null || continuationToken.isBlank()) {
            throw new VisitOperationsConflictException(
                    "The Visit invitation context is unavailable. Open a fresh invitation or request from the property page.");
        }
        String hash;
        try {
            hash = secrets.visitContinuationTokenHash(continuationToken);
        } catch (IllegalArgumentException exception) {
            throw hidden();
        }
        LeadVisitContinuation continuation = continuations.findBySecretHash(hash)
                .orElseThrow(LeadVisitProposalService::hidden);
        Instant now = databaseClock.now();
        if ((contextId != null && !contextId.equals(continuation.getId()))
                || continuation.getRevokedAt() != null
                || now.isAfter(continuation.getExpiresAt())
                || continuation.getProposal().getState() != LeadVisitProposalState.OPEN
                || now.isAfter(continuation.getProposal().getExpiresAt())
                || (listingId != null
                    && !listingId.equals(continuation.getProposal().getListingId()))) {
            throw hidden();
        }
        return continuation;
    }

    private ProposalPropertyView publicProperty(LeadVisitProposal proposal, Instant now) {
        LeadVisitProposal active = proposal;
        if (now.isAfter(active.getExpiresAt()) && active.getState() == LeadVisitProposalState.OPEN) {
            active.expire(now);
            proposals.saveAndFlush(active);
        }
        var properties = publicProperties.currentPublicPropertiesById(active.getLead(),
                List.of(active.getListingId()), true);
        var property = properties.get(active.getListingId());
        if (property == null) throw hidden();
        return new ProposalPropertyView(property.listingId(), property.title(),
                property.location(), property.bhk(), property.monthlyRent(),
                property.listingVersion());
    }

    private void expireIfNeeded(LeadVisitProposal proposal, Instant now) {
        if (proposal.getState() == LeadVisitProposalState.OPEN
                && now.isAfter(proposal.getExpiresAt())) {
            proposal.expire(now);
            proposals.saveAndFlush(proposal);
        }
    }

    private void requireCurrentOwner(Long actorId, LeadRoutingQueue lead) {
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireContactOwner(context, lead, actorId);
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null
                || !guards.cityIsActive(lead.getSupportedCity().getId())
                || !guards.teamIsActive(lead.getOperatingTeam().getId())
                || !Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())) {
            throw hidden();
        }
    }

    private void acquireGuards(Long actorId, LeadRoutingQueue lead) {
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null) throw hidden();
        guards.acquire(List.of(lead.getSupportedCity().getId()),
                List.of(lead.getOperatingTeam().getId()), List.of(actorId));
    }

    private LeadVisitProposal requireReceipt(Long actorId, Long leadId, UUID operationId,
                                              String action,
                                              String payloadHash) {
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) return null;
        if (!CREATE_ACTION.equals(receipt.getAction())
                && !REVOKE_ACTION.equals(receipt.getAction())) throw hidden();
        UUID proposalId = LeadService.parseStoredUuidReference(receipt.getResultReference());
        LeadVisitProposal proposal = proposals.findById(proposalId)
                .orElseThrow(LeadVisitProposalService::hidden);
        if (!Objects.equals(proposal.getLead().getId(), leadId)) throw hidden();
        requireCurrentOwner(actorId, proposal.getLead());
        if (!receipt.getPayloadHash().equals(payloadHash)) {
            throw new VisitOperationsConflictException(
                    "This idempotency key was already used with a different Visit invitation command");
        }
        return proposal;
    }

    private void requireShortlistMembership(LeadShortlist shortlist, Long listingId) {
        boolean present = shortlistItems.findAllLockedByShortlist_IdOrderByPositionAsc(
                        shortlist.getId()).stream()
                .anyMatch(item -> Objects.equals(item.getListingId(), listingId));
        if (!present) {
            throw new VisitOperationsConflictException(
                    "This property is not in the exact saved shortlist. Refresh before preparing an invitation.");
        }
    }

    private static void requireWorkingLead(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status != LeadStatus.NEW && status != LeadStatus.QUALIFYING
                && status != LeadStatus.QUALIFIED) {
            throw new VisitOperationsConflictException(
                    "A Visit invitation cannot be prepared for this Lead state");
        }
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (expected == null || !expected.equals(lead.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while the invitation was being prepared");
        }
    }

    private static VisitProposalIssuedView issuedView(LeadVisitProposal proposal, String url) {
        return new VisitProposalIssuedView(proposal.getId(), proposal.getLead().getId(),
                proposal.getListingId(), proposal.getState(), proposal.getExpiresAt(),
                proposal.getVersion(), url);
    }

    private VisitProposalView view(LeadVisitProposal proposal) {
        var claims = associationClaims.findAllByProposal_IdOrderByCreatedAtAscIdAsc(
                proposal.getId()).stream().map(claim -> new AssociationClaimReviewView(
                        claim.getId(), claim.getRequestId(), claim.getState(), claim.getVersion(),
                        claim.getCreatedAt())).toList();
        return new VisitProposalView(proposal.getId(), proposal.getLead().getId(),
                proposal.getListingId(), proposal.getState(), proposal.getAssociationStatus(),
                proposal.getCreatedAt(), proposal.getExpiresAt(), proposal.getVersion(), claims);
    }

    private String handoffUrl(String token, Long listingId) {
        return publicOrigin() + "/property/" + listingId + "#visit=" + token;
    }

    private String publicOrigin() {
        if (configuredOrigin == null || configuredOrigin.isBlank()) {
            throw new IllegalStateException("A canonical public origin is not configured");
        }
        try {
            URI uri = URI.create(configuredOrigin.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("The configured public origin is invalid");
        }
    }

    private void lockOperation(Long actorId, String action, UUID operationId) {
        String key = actorId + ":" + action + ":" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, key), resultSet -> null);
    }

    private String payloadHash(Object value) {
        try {
            return protection.keyedDigest("LEAD_VISIT_PROPOSAL_PAYLOAD",
                    objectMapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Visit proposal command could not be fingerprinted");
        }
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

    private static void validateIssue(VisitProposalCreateCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.shortlistId() == null || command.listingId() == null
                || command.expectedShortlistVersion() == null
                || command.expectedListingVersion() == null
                || command.contactPreferenceRevision() == null) {
            throw new IllegalArgumentException(
                    "Current Lead, shortlist, property and consent versions are required");
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException("Visit invitation not found");
    }

    private static EntityNotFoundException hiddenLead() {
        return new EntityNotFoundException("Lead not found");
    }

    public record HandoffExchange(String continuationToken, VisitHandoffView view) {}
}
