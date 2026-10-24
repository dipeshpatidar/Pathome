package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadShortlistCommand;
import com.indore.pathome.spaces.dto.crm.LeadShortlistView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadShortlist;
import com.indore.pathome.spaces.entity.LeadShortlistItem;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LeadShortlistService {
    private final LeadRoutingQueueRepository leads;
    private final LeadShortlistRepository shortlists;
    private final LeadShortlistItemRepository items;
    private final LeadActivityRepository activities;
    private final LeadCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final LeadPublicPropertyService publicProperties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LeadShortlistService(LeadRoutingQueueRepository leads,
                                LeadShortlistRepository shortlists,
                                LeadShortlistItemRepository items,
                                LeadActivityRepository activities,
                                LeadCommandReceiptRepository receipts,
                                LeadAccessService leadAccess,
                                StaffAccessService staffAccess,
                                OperationalSecurityGuards guards,
                                OperationalAuditService audit,
                                DatabaseClock databaseClock,
                                LeadPublicPropertyService publicProperties,
                                JdbcTemplate jdbc,
                                ObjectMapper objectMapper) {
        this.leads = leads;
        this.shortlists = shortlists;
        this.items = items;
        this.activities = activities;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.publicProperties = publicProperties;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LeadShortlistView create(Long actorId, Long leadId, String operationKey,
                                    LeadShortlistCommand command) {
        validate(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(leadId, command);
        lockOperation(actorId, operationId);
        LeadCommandReceipt existingReceipt = receipts.findByActorIdAndOperationIdAndAction(
                actorId, operationId, "LEAD_SHORTLIST_CREATE").orElse(null);
        if (existingReceipt != null) {
            if (!existingReceipt.getPayloadHash().equals(payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different shortlist request");
            }
            return get(actorId, leadId, Long.parseLong(existingReceipt.getResultReference()));
        }
        LeadRoutingQueue lead = lockAndAuthorize(actorId, leadId);
        requireVersion(lead, command.expectedLeadVersion());
        requireActiveLifecycle(lead);
        List<Long> listingIds = exactListingIds(command);
        publicProperties.requireCurrentPublicProperties(lead, listingIds);

        Instant now = databaseClock.now();
        LeadShortlist shortlist = shortlists.saveAndFlush(new LeadShortlist(lead, now));
        replaceItems(shortlist, listingIds);
        lead.touchCrmState();
        lead = leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "PROPERTY_SHORTLIST_CREATED",
                null, now, null));
        audit.recordUserEvent(actor, "LEAD_SHORTLIST_CREATED", "LEAD", leadId,
                "PUBLIC_PROPERTY_SHORTLIST", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, "LEAD_SHORTLIST_CREATE",
                payloadHash, shortlist.getId().toString(), now));
        return view(lead, shortlist, listingIds);
    }

    @Transactional
    public LeadShortlistView update(Long actorId, Long leadId, Long shortlistId,
                                    LeadShortlistCommand command) {
        validate(command);
        if (shortlistId == null || shortlistId <= 0) {
            throw new IllegalArgumentException("Shortlist ID is required");
        }
        LeadRoutingQueue lead = lockAndAuthorize(actorId, leadId);
        LeadShortlist shortlist = shortlists.findLockedById(shortlistId)
                .orElseThrow(() -> new EntityNotFoundException("Shortlist not found"));
        if (!shortlist.getLead().getId().equals(leadId)) {
            throw new EntityNotFoundException("Shortlist not found");
        }
        requireVersion(lead, command.expectedLeadVersion());
        if (command.expectedShortlistVersion() == null
                || !command.expectedShortlistVersion().equals(shortlist.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The shortlist changed while it was being edited. Refresh and compare before retrying.");
        }
        requireActiveLifecycle(lead);
        List<Long> listingIds = exactListingIds(command);
        publicProperties.requireCurrentPublicProperties(lead, listingIds);

        Instant now = databaseClock.now();
        items.deleteAllByShortlist_Id(shortlistId);
        items.flush();
        replaceItems(shortlist, listingIds);
        shortlist.touch(now);
        shortlist = shortlists.saveAndFlush(shortlist);
        lead.touchCrmState();
        lead = leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "PROPERTY_SHORTLIST_UPDATED",
                null, now, null));
        audit.recordUserEvent(actor, "LEAD_SHORTLIST_UPDATED", "LEAD", leadId,
                "PUBLIC_PROPERTY_SHORTLIST", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        return view(lead, shortlist, listingIds);
    }

    @Transactional(readOnly = true)
    public LeadShortlistView get(Long actorId, Long leadId, Long shortlistId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        LeadRoutingQueue lead = leads.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireReadable(context, lead, actorId);
        LeadShortlist shortlist = shortlists.findById(shortlistId)
                .filter(value -> value.getLead().getId().equals(leadId))
                .orElseThrow(() -> new EntityNotFoundException("Shortlist not found"));
        List<Long> listingIds = items.findAllByShortlist_IdOrderByPositionAsc(shortlistId).stream()
                .map(LeadShortlistItem::getListingId).toList();
        return view(lead, shortlist, listingIds);
    }

    private void replaceItems(LeadShortlist shortlist, List<Long> listingIds) {
        for (int index = 0; index < listingIds.size(); index++) {
            items.save(new LeadShortlistItem(shortlist, listingIds.get(index), index));
        }
        items.flush();
    }

    private LeadRoutingQueue lockAndAuthorize(Long actorId, Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(actorId));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireEditable(context, lead, actorId);
        return lead;
    }

    private static void validate(LeadShortlistCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.listingIds() == null) {
            throw new IllegalArgumentException(
                    "Expected Lead version and an ordered listingIds array are required");
        }
    }

    private static List<Long> exactListingIds(LeadShortlistCommand command) {
        List<Long> listingIds = LeadContract.exactPositiveIds(command.listingIds(), "listingIds");
        if (listingIds.size() != command.listingIds().size()) {
            throw new IllegalArgumentException("listingIds must not contain duplicate properties");
        }
        return listingIds;
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (lead.getVersion() != null && !lead.getVersion().equals(expected)) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress. Refresh and compare before retrying.");
        }
    }

    private static void requireActiveLifecycle(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status == LeadStatus.CLOSED || status == LeadStatus.ASSIGNED
                || status == LeadStatus.UNMAPPED || status == LeadStatus.CONTACTED
                || status == LeadStatus.STALE) {
            throw new VisitOperationsConflictException(
                    "A shortlist cannot change for this Lead state");
        }
    }

    private static LeadShortlistView view(LeadRoutingQueue lead, LeadShortlist shortlist,
                                          List<Long> listingIds) {
        return new LeadShortlistView(shortlist.getId(), shortlist.getVersion(), lead.getVersion(),
                listingIds, shortlist.getCreatedAt(), shortlist.getUpdatedAt());
    }

    private void lockOperation(Long actorId, UUID operationId) {
        String lockKey = actorId + ":LEAD_SHORTLIST_CREATE:" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, lockKey), resultSet -> null);
    }

    private String payloadHash(Object command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(command)));
        } catch (Exception exception) {
            throw new IllegalStateException("Shortlist command could not be fingerprinted");
        }
    }

    private String payloadHash(Long leadId, Object command) {
        return payloadHash(Map.of("leadId", leadId, "command", command));
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
}
