package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.OperationsLessorContactStatus;
import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityCreateCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityResponseCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityRevealCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadLessorAvailabilityEnquiry;
import com.indore.pathome.spaces.entity.LeadLessorAvailabilityOutcome;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadLessorAvailabilityEnquiryRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class LeadLessorAvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(LeadLessorAvailabilityService.class);
    private static final Duration CONTEXT_LIFETIME = Duration.ofHours(24);
    private static final Duration PROOF_LIFETIME = Duration.ofHours(24);
    private static final String CREATE_ACTION = "LEAD_AVAILABILITY_CREATE";
    private static final String RESPONSE_ACTION = "LEAD_AVAILABILITY_RESPONSE";

    private final LeadRoutingQueueRepository leads;
    private final LeadShortlistRepository shortlists;
    private final LeadShortlistItemRepository shortlistItems;
    private final LeadSourceEventRepository sourceEvents;
    private final LeadLessorAvailabilityEnquiryRepository enquiries;
    private final LeadActivityRepository activities;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final LeadPhoneVerificationService phoneVerification;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final LeadDataProtection protection;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public LeadLessorAvailabilityService(
            LeadRoutingQueueRepository leads,
            LeadShortlistRepository shortlists,
            LeadShortlistItemRepository shortlistItems,
            LeadSourceEventRepository sourceEvents,
            LeadLessorAvailabilityEnquiryRepository enquiries,
            LeadActivityRepository activities,
            LeadAssistanceCommandReceiptRepository receipts,
            LeadPhoneVerificationService phoneVerification,
            LeadAccessService leadAccess,
            StaffAccessService staffAccess,
            OperationalSecurityGuards guards,
            OperationalAuditService audit,
            TransactionalCommandBounds commandBounds,
            DatabaseClock databaseClock,
            LeadDataProtection protection,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.leads = leads;
        this.shortlists = shortlists;
        this.shortlistItems = shortlistItems;
        this.sourceEvents = sourceEvents;
        this.enquiries = enquiries;
        this.activities = activities;
        this.receipts = receipts;
        this.phoneVerification = phoneVerification;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.protection = protection;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public AvailabilityView create(Long actorId, Long leadId, String operationKey,
                                   AvailabilityCreateCommand command) {
        validateCreate(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "command", command));
        lockOperation(actorId, CREATE_ACTION, operationId);
        LeadLessorAvailabilityEnquiry replay = requireReceipt(actorId, leadId, operationId,
                CREATE_ACTION, payloadHash);
        if (replay != null) return view(actorId, replay, databaseClock.now(), true);

        commandBounds.apply();
        LockedAvailabilityContext context = lockCurrentContext(actorId, leadId, command.shortlistId(),
                command.listingId(), command.sourceEventId(), command.expectedLeadVersion(),
                command.expectedShortlistVersion(), command.expectedListingVersion());
        LeadLessorAvailabilityEnquiry existing = enquiries
                .findFirstByLead_IdAndShortlistIdAndListingIdAndSourceEvent_IdAndOutcomeOrderByCreatedAtDesc(
                        leadId, command.shortlistId(), command.listingId(), command.sourceEventId(),
                        LeadLessorAvailabilityOutcome.PENDING)
                .filter(value -> value.getExpiresAt().isAfter(context.now()))
                .orElse(null);
        if (existing != null) {
            return view(actorId, existing, context.now(), true);
        }

        LeadLessorAvailabilityEnquiry enquiry = new LeadLessorAvailabilityEnquiry(UUID.randomUUID(),
                context.lead(), command.shortlistId(), command.listingId(), context.source(),
                context.actor(), context.lead().getSupportedCity(), context.lead().getOperatingTeam(),
                context.lead().getVersion(), context.shortlist().getVersion(),
                context.listing().getVersion(), context.now(),
                context.now().plus(CONTEXT_LIFETIME));
        enquiry = enquiries.saveAndFlush(enquiry);
        activities.save(new LeadActivity(context.lead(), context.actor(),
                "LESSOR_AVAILABILITY_ENQUIRY_CREATED", context.source(), context.now(), null));
        audit.recordUserEvent(context.actor(), "LEAD_AVAILABILITY_ENQUIRY_CREATED",
                "LEAD_AVAILABILITY", enquiry.getId().getMostSignificantBits() & Long.MAX_VALUE,
                "VIEWING_AVAILABILITY", context.lead().getSupportedCity(),
                context.lead().getOperatingTeam(), details(context.listing(), null));
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(context.actor(), operationId,
                CREATE_ACTION, payloadHash, enquiry.getId().toString(), context.now()));
        return view(actorId, enquiry, context.now(), true);
    }

    @Transactional
    public AvailabilityView get(Long actorId, Long leadId, UUID enquiryId) {
        LeadLessorAvailabilityEnquiry enquiry = enquiries.findByIdAndLead_Id(enquiryId, leadId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        LeadAccessContext access = leadAccess.context(actorId);
        leadAccess.requireReadable(access, enquiry.getLead(), actorId);
        return view(actorId, enquiry, databaseClock.now(), false);
    }

    @Transactional
    public OperationsLessorContactView reveal(Long actorId, Long leadId, UUID enquiryId,
                                              AvailabilityRevealCommand command) {
        validateReveal(command);
        commandBounds.apply();
        leads.findLockedById(leadId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        LeadLessorAvailabilityEnquiry enquirySeed = enquiries.findByIdAndLead_Id(enquiryId, leadId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        LockedAvailabilityContext context = lockCurrentContext(actorId, leadId,
                enquirySeed.getShortlistId(), enquirySeed.getListingId(),
                enquirySeed.getSourceEvent().getId(), command.expectedLeadVersion(),
                command.expectedShortlistVersion(), command.expectedListingVersion());
        LeadLessorAvailabilityEnquiry enquiry = enquiries.findLockedById(enquiryId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        if (!enquiry.getLead().getId().equals(leadId)
                || !Objects.equals(enquiry.getShortlistId(), context.shortlist().getId())
                || !Objects.equals(enquiry.getListingId(), context.listing().getId())
                || !Objects.equals(enquiry.getSourceEvent().getId(), context.source().getId())
                || !Objects.equals(enquiry.getLeadVersion(), context.lead().getVersion())
                || !Objects.equals(enquiry.getShortlistVersion(), context.shortlist().getVersion())
                || !Objects.equals(enquiry.getListingVersion(), context.listing().getVersion())) {
            throw hidden();
        }
        requireEnquiryVersion(enquiry, command.expectedEnquiryVersion());
        requireLiveEnquiry(enquiry, context.now());
        requireCurrentPhoneProof(context.lead(), context.source(), command.expectedContactPhoneRevision(),
                context.now());
        if (!command.genuineEnquiryAttested()) {
            throw new IllegalArgumentException(
                    "Confirm that this is a current genuine viewing-availability enquiry");
        }

        LessorProfile profile = context.profile();
        OperationsLessorContactView contact = OperationsLessorContactResolver.resolve(
                context.listing().getId(), context.listing().getOwnerUserId(), profile);
        if (contact.status() == OperationsLessorContactStatus.CONTACT_UNAVAILABLE) {
            LeadActivity activity = activities.saveAndFlush(new LeadActivity(context.lead(),
                    context.actor(), "LESSOR_CONTACT_UNAVAILABLE", context.source(), context.now(),
                    null));
            enquiry.recordOutcome(LeadLessorAvailabilityOutcome.CONTACT_UNUSABLE,
                    activity.getId(), context.actor(), context.now());
            enquiries.saveAndFlush(enquiry);
            audit.recordUserEvent(context.actor(), "LEAD_LESSOR_CONTACT_UNAVAILABLE",
                    "LEAD_AVAILABILITY", positiveAuditTarget(enquiry.getId()),
                    "VIEWING_AVAILABILITY", context.lead().getSupportedCity(),
                    context.lead().getOperatingTeam(), details(context.listing(),
                            LeadLessorAvailabilityOutcome.CONTACT_UNUSABLE));
            return contact;
        }

        audit.recordUserEvent(context.actor(), "LEAD_LESSOR_CONTACT_REVEALED",
                "LEAD_AVAILABILITY", positiveAuditTarget(enquiry.getId()), "VIEWING_AVAILABILITY",
                context.lead().getSupportedCity(), context.lead().getOperatingTeam(),
                details(context.listing(), null));
        flagHighVolumeWithoutBlocking(context.actor().getId(), context.now());
        return contact;
    }

    @Transactional
    public AvailabilityView recordResponse(Long actorId, Long leadId, UUID enquiryId,
                                           String operationKey, AvailabilityResponseCommand command) {
        validateResponse(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "enquiryId", enquiryId,
                "command", command));
        lockOperation(actorId, RESPONSE_ACTION, operationId);
        LeadLessorAvailabilityEnquiry replay = requireReceipt(actorId, leadId, operationId,
                RESPONSE_ACTION, payloadHash);
        if (replay != null) return view(actorId, replay, databaseClock.now(), true);

        commandBounds.apply();
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(LeadLessorAvailabilityService::hiddenLead);
        LeadLessorAvailabilityEnquiry seed = enquiries.findByIdAndLead_Id(enquiryId, leadId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        LeadSourceEvent source = sourceEvents.findLockedById(seed.getSourceEvent().getId())
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        LeadLessorAvailabilityEnquiry enquiry = enquiries.findLockedById(enquiryId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        acquireGuards(actorId, lead);
        requireCurrentOwner(actorId, lead);
        requireWorkingLead(lead);
        requireVersion(lead, command.expectedLeadVersion());
        requireEnquiryVersion(enquiry, command.expectedEnquiryVersion());
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        String note = LeadContract.text(command.note(), 4000, "Availability note", false);
        LeadActivity activity = activities.saveAndFlush(new LeadActivity(lead, actor,
                "LESSOR_AVAILABILITY_" + command.outcome().name(), source, now, note));
        enquiry.recordOutcome(command.outcome(), activity.getId(), actor, now);
        enquiry = enquiries.saveAndFlush(enquiry);
        audit.recordUserEvent(actor, "LEAD_AVAILABILITY_OUTCOME_RECORDED",
                "LEAD_AVAILABILITY", positiveAuditTarget(enquiry.getId()), "VIEWING_AVAILABILITY",
                lead.getSupportedCity(), lead.getOperatingTeam(),
                details(null, command.outcome()));
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, RESPONSE_ACTION,
                payloadHash, enquiry.getId().toString(), now));
        return view(actorId, enquiry, now, true);
    }

    @Transactional(readOnly = true)
    public AssistanceCommandStatusView commandStatus(Long actorId, UUID operationId, String action) {
        if (action == null || action.isBlank()
                || (!CREATE_ACTION.equals(action) && !RESPONSE_ACTION.equals(action))) {
            throw new IllegalArgumentException("A supported availability action is required");
        }
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) {
            return new AssistanceCommandStatusView("NOT_RECORDED", action, null, null);
        }
        LeadLessorAvailabilityEnquiry enquiry = storedEnquiry(receipt);
        requireCurrentOwner(actorId, enquiry.getLead());
        return new AssistanceCommandStatusView("RECORDED", action,
                receipt.getResultReference(), receipt.getCompletedAt());
    }

    private LockedAvailabilityContext lockCurrentContext(
            Long actorId, Long leadId, Long shortlistId, Long listingId, UUID sourceEventId,
            Long expectedLeadVersion, Long expectedShortlistVersion, Long expectedListingVersion) {
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(LeadLessorAvailabilityService::hiddenLead);
        // Establish record ownership before any lifecycle/version outcome can reveal that a foreign
        // Lead exists. The guarded ownership check below remains the definitive admission decision.
        requireCurrentOwner(actorId, lead);
        requireWorkingLead(lead);
        requireVersion(lead, expectedLeadVersion);
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null) {
            throw new VisitOperationsConflictException(
                    "A current City/Team-owned Lead is required");
        }
        LeadShortlistContext shortlist = lockShortlist(leadId, shortlistId,
                expectedShortlistVersion);
        if (!shortlist.listingIds().contains(listingId)) {
            throw new VisitOperationsConflictException(
                    "This property is not in the saved shortlist. Refresh before contacting the lessor.");
        }
        Listing listing = entityManager.find(Listing.class, listingId, LockModeType.PESSIMISTIC_WRITE);
        if (listing == null) throw hidden();
        requireVersion(listing.getVersion(), expectedListingVersion,
                "The property changed while the enquiry was being reviewed");
        requireEligibleRental(lead, listing);
        Locality locality = entityManager.find(Locality.class, listing.getCanonicalLocalityId(),
                LockModeType.PESSIMISTIC_READ);
        if (locality == null) throw hidden();
        if (locality.getSupportedCity() == null
                || !lead.getSupportedCity().getId().equals(locality.getSupportedCity().getId())) {
            throw new VisitOperationsConflictException(
                    "This property is no longer in the Lead's canonical City");
        }
        LessorProfile profile = listing.getLessorProfileId() == null ? null
                : entityManager.find(LessorProfile.class, listing.getLessorProfileId(),
                        LockModeType.PESSIMISTIC_READ);
        LeadSourceEvent source = sourceEvents.findLockedById(sourceEventId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        requireProcessedSource(lead, source);
        acquireGuards(actorId, lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        requireCurrentOwner(actorId, lead);
        return new LockedAvailabilityContext(lead, source, shortlist.shortlist(), listing,
                locality, profile, actor, databaseClock.now());
    }

    private LeadShortlistContext lockShortlist(Long leadId, Long shortlistId,
                                               Long expectedVersion) {
        var shortlist = shortlists.findLockedById(shortlistId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
        if (!shortlist.getLead().getId().equals(leadId)) throw hidden();
        if (expectedVersion == null || !expectedVersion.equals(shortlist.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The shortlist changed while the enquiry was being reviewed");
        }
        List<Long> ids = shortlistItems.findAllByShortlist_IdOrderByPositionAsc(shortlistId).stream()
                .map(item -> item.getListingId()).toList();
        return new LeadShortlistContext(shortlist, ids);
    }

    private void requireEligibleRental(LeadRoutingQueue lead, Listing listing) {
        if (listing.getStatus() != ListingStatus.ACTIVE
                || listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED
                || listing.getListingType() != ListingType.RENT
                || !(listing instanceof RentalDetails rental)
                || rental.getMonthlyRent() == null
                || listing.getCanonicalLocalityId() == null
                || listing.getLessorProfileId() == null) {
            throw new VisitOperationsConflictException(
                    "This property is no longer eligible for lessor availability follow-up");
        }
    }

    private void requireCurrentPhoneProof(LeadRoutingQueue lead, LeadSourceEvent source,
                                          Long expectedPhoneRevision, Instant now) {
        if (expectedPhoneRevision == null
                || !expectedPhoneRevision.equals(lead.getContactPhoneRevision())) {
            throw new VisitOperationsConflictException(
                    "The enquiry phone changed. Verify the current number before contact disclosure");
        }
        if (!phoneVerification.hasCurrentProof(lead, source.getId(), now)) {
            throw new VisitOperationsConflictException(
                    "Current enquiry-phone possession proof is required before revealing lessor contact");
        }
    }

    private void requireLiveEnquiry(LeadLessorAvailabilityEnquiry enquiry, Instant now) {
        if (!now.isBefore(enquiry.getExpiresAt())) {
            throw new VisitOperationsConflictException(
                    "This availability enquiry expired. Create a fresh enquiry after reconfirming the customer.");
        }
        if (enquiry.getOutcome() == LeadLessorAvailabilityOutcome.CUSTOMER_WITHDREW
                || enquiry.getOutcome() == LeadLessorAvailabilityOutcome.CUSTOMER_DISPUTED
                || enquiry.getOutcome() == LeadLessorAvailabilityOutcome.PROPERTY_DECLINED) {
            throw new VisitOperationsConflictException(
                    "This availability enquiry is closed and cannot reveal contact");
        }
    }

    private void acquireGuards(Long actorId, LeadRoutingQueue lead) {
        guards.acquire(List.of(lead.getSupportedCity().getId()),
                List.of(lead.getOperatingTeam().getId()), List.of(actorId));
    }

    private void requireCurrentOwner(Long actorId, LeadRoutingQueue lead) {
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireContactOwner(context, lead, actorId);
        if (!guards.cityIsActive(lead.getSupportedCity().getId())
                || !guards.teamIsActive(lead.getOperatingTeam().getId())
                || !Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())) {
            throw hidden();
        }
    }

    private static void requireProcessedSource(LeadRoutingQueue lead, LeadSourceEvent source) {
        if (source.getLead() == null || !lead.getId().equals(source.getLead().getId())
                || source.getProcessingState() != LeadSourceEventState.PROCESSED) {
            throw new VisitOperationsConflictException(
                    "A currently processed source attached to this Lead is required");
        }
    }

    private static void requireWorkingLead(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status != LeadStatus.NEW && status != LeadStatus.QUALIFYING
                && status != LeadStatus.QUALIFIED) {
            throw new VisitOperationsConflictException(
                    "Lessor availability follow-up is unavailable for this Lead state");
        }
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (expected == null || !expected.equals(lead.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress");
        }
    }

    private static void requireVersion(Long actual, Long expected, String message) {
        if (expected == null || !expected.equals(actual)) {
            throw new VisitOperationsConflictException(message);
        }
    }

    private static void requireEnquiryVersion(LeadLessorAvailabilityEnquiry enquiry, Long expected) {
        if (expected == null || !expected.equals(enquiry.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The availability enquiry changed while it was being reviewed");
        }
    }

    private AvailabilityView view(Long actorId, LeadLessorAvailabilityEnquiry enquiry,
                                  Instant now, boolean requireOwner) {
        LeadAccessContext access = leadAccess.context(actorId);
        if (requireOwner) {
            leadAccess.requireContactOwner(access, enquiry.getLead(), actorId);
        } else {
            leadAccess.requireReadable(access, enquiry.getLead(), actorId);
        }
        boolean currentProof = phoneVerification.hasCurrentProof(enquiry.getLead(),
                enquiry.getSourceEvent().getId(), now);
        boolean eligible = requireOwner && currentProof
                && now.isBefore(enquiry.getExpiresAt())
                && enquiry.getOutcome() != LeadLessorAvailabilityOutcome.CUSTOMER_WITHDREW
                && enquiry.getOutcome() != LeadLessorAvailabilityOutcome.CUSTOMER_DISPUTED
                && enquiry.getOutcome() != LeadLessorAvailabilityOutcome.PROPERTY_DECLINED;
        String phoneState = currentProof ? "VERIFIED" : "PENDING";
        String message = switch (enquiry.getOutcome()) {
            case PENDING -> currentProof
                    ? "The pending enquiry is saved. Lessor contact may be requested."
                    : "The pending enquiry is saved. Current phone verification is required before contact.";
            case LESSOR_REPORTED_AVAILABLE ->
                    "Lessor reported availability. This is not a booking or confirmed appointment.";
            case LESSOR_REPORTED_UNAVAILABLE -> "Lessor reported the property unavailable.";
            case NO_ANSWER -> "No answer was recorded. The enquiry remains in Lead history.";
            case CALLBACK_REQUESTED -> "A lessor callback was requested. No appointment is confirmed.";
            case CONTACT_UNUSABLE -> "Lessor contact was unavailable. The enquiry remains saved.";
            case CUSTOMER_WITHDREW -> "The customer withdrew this availability enquiry.";
            case CUSTOMER_DISPUTED -> "The customer disputed this availability report.";
            case PROPERTY_DECLINED -> "The property was declined. No visit is confirmed.";
        };
        return new AvailabilityView(enquiry.getId(), enquiry.getLead().getId(),
                enquiry.getShortlistId(), enquiry.getListingId(), enquiry.getSourceEvent().getId(),
                enquiry.getOutcome(), enquiry.getCreatedAt(), enquiry.getExpiresAt(),
                enquiry.getReportedAt(), enquiry.getVersion(), enquiry.getLead().getVersion(),
                enquiry.getShortlistVersion(), enquiry.getListingVersion(),
                enquiry.getLead().getContactPhoneRevision(), eligible, phoneState, message);
    }

    private LeadLessorAvailabilityEnquiry requireReceipt(Long actorId, Long leadId,
                                                          UUID operationId,
                                                          String action, String payloadHash) {
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) return null;
        LeadLessorAvailabilityEnquiry enquiry = storedEnquiry(receipt);
        if (!Objects.equals(enquiry.getLead().getId(), leadId)) throw hidden();
        requireCurrentOwner(actorId, enquiry.getLead());
        if (!receipt.getPayloadHash().equals(payloadHash)) {
            throw new VisitOperationsConflictException(
                    "This idempotency key was already used with a different availability command");
        }
        return enquiry;
    }

    private LeadLessorAvailabilityEnquiry storedEnquiry(LeadAssistanceCommandReceipt receipt) {
        if (!CREATE_ACTION.equals(receipt.getAction())
                && !RESPONSE_ACTION.equals(receipt.getAction())) throw hidden();
        UUID enquiryId = LeadService.parseStoredUuidReference(receipt.getResultReference());
        return enquiries.findById(enquiryId)
                .orElseThrow(LeadLessorAvailabilityService::hidden);
    }

    private void flagHighVolumeWithoutBlocking(Long actorId, Instant now) {
        Integer count = jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where actor_user_id = ? and action_code = 'LEAD_LESSOR_CONTACT_REVEALED'
                  and occurred_at >= ?
                """, Integer.class, actorId, java.sql.Timestamp.from(now.minus(Duration.ofHours(24))));
        if (count != null && count >= 80) {
            log.warn("Lead lessor-contact volume requires scoped metadata review; action was not denied");
        }
    }

    private static Map<String, Object> details(Listing listing,
                                               LeadLessorAvailabilityOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (listing != null) {
            details.put("listingId", listing.getId());
            if (listing.getLessorProfileId() != null) details.put("lessorProfileId",
                    listing.getLessorProfileId());
        }
        if (outcome != null) details.put("availabilityOutcome", outcome.name());
        return details;
    }

    private static long positiveAuditTarget(UUID id) {
        return id.getLeastSignificantBits() & Long.MAX_VALUE;
    }

    private void lockOperation(Long actorId, String action, UUID operationId) {
        String key = actorId + ":" + action + ":" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, key), resultSet -> null);
    }

    private String payloadHash(Object value) {
        try {
            return protection.keyedDigest("LEAD_AVAILABILITY_PAYLOAD",
                    objectMapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Availability command could not be fingerprinted");
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

    private static void validateCreate(AvailabilityCreateCommand command) {
        if (command == null || command.shortlistId() == null || command.listingId() == null
                || command.sourceEventId() == null || command.expectedLeadVersion() == null
                || command.expectedShortlistVersion() == null
                || command.expectedListingVersion() == null) {
            throw new IllegalArgumentException(
                    "Current source, shortlist, listing and version facts are required");
        }
        if (!command.genuineEnquiryAttested()) {
            throw new IllegalArgumentException(
                    "Confirm that this is a current genuine viewing-availability enquiry");
        }
    }

    private static void validateReveal(AvailabilityRevealCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.expectedShortlistVersion() == null
                || command.expectedListingVersion() == null
                || command.expectedEnquiryVersion() == null
                || command.expectedContactPhoneRevision() == null) {
            throw new IllegalArgumentException(
                    "Current Lead, shortlist, listing, enquiry and phone versions are required");
        }
    }

    private static void validateResponse(AvailabilityResponseCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.expectedEnquiryVersion() == null || command.outcome() == null
                || command.outcome() == LeadLessorAvailabilityOutcome.PENDING) {
            throw new IllegalArgumentException(
                    "Current versions and a reported availability outcome are required");
        }
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException("Availability enquiry not found");
    }

    private static EntityNotFoundException hiddenLead() {
        return new EntityNotFoundException("Lead not found");
    }

    private record LeadShortlistContext(
            com.indore.pathome.spaces.entity.LeadShortlist shortlist,
            List<Long> listingIds) {}

    private record LockedAvailabilityContext(
            LeadRoutingQueue lead,
            LeadSourceEvent source,
            com.indore.pathome.spaces.entity.LeadShortlist shortlist,
            Listing listing,
            Locality locality,
            LessorProfile profile,
            User actor,
            Instant now) {}
}
