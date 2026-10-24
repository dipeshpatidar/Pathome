package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadActivityPage;
import com.indore.pathome.spaces.dto.crm.LeadActivityCommand;
import com.indore.pathome.spaces.dto.crm.LeadActivityView;
import com.indore.pathome.spaces.dto.crm.LeadAssignmentCommand;
import com.indore.pathome.spaces.dto.crm.LeadCaptureOutcomeView;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.dto.crm.LeadCommandReceiptView;
import com.indore.pathome.spaces.dto.crm.LeadContactView;
import com.indore.pathome.spaces.dto.crm.LeadDetailView;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidateResponse;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidateView;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidatesCommand;
import com.indore.pathome.spaces.dto.crm.LeadLifecycleCommand;
import com.indore.pathome.spaces.dto.crm.LeadManualEnquiryView;
import com.indore.pathome.spaces.dto.crm.LeadPage;
import com.indore.pathome.spaces.dto.crm.LeadPatchCommand;
import com.indore.pathome.spaces.dto.crm.LeadRoutingCommand;
import com.indore.pathome.spaces.dto.crm.LeadSourceAttachCommand;
import com.indore.pathome.spaces.dto.crm.LeadSourceAttributionView;
import com.indore.pathome.spaces.dto.crm.LeadSummaryView;
import com.indore.pathome.spaces.dto.crm.LeadTeamTransferCommand;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.LeadLegacyProvenance;
import com.indore.pathome.spaces.entity.LeadPreferredLocality;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.exception.LocationLookupRateLimitException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadPreferredLocalityRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class LeadService {
    private static final String HIDDEN = "Lead not found";
    private static final String MANUAL_ATTACHED_FINGERPRINT = "MANUAL_ATTACHED_V1";
    private static final String MANUAL_DISTINCT_FINGERPRINT = "MANUAL_DISTINCT_V1";
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEAD_REFERENCE =
            Pattern.compile("(?i)^(?:ld-|lead\\s*#?)(\\d{1,18})$");
    private static final Pattern NUMERIC_REFERENCE = Pattern.compile("^\\d{1,18}$");
    private static final Pattern PHONE_SUFFIX = Pattern.compile("^\\d{4}$");
    private static final Set<String> PATCH_FIELDS = Set.of(
            "customerName", "contactPhone", "customerSaid", "intent", "propertyType", "bhk",
            "budgetMin", "budgetMax", "currency", "targetMoveInDate", "furnishing", "tenantProfile",
            "parkingRequired", "petsAllowed", "liftRequired", "floorRequirement",
            "preferredLocalityIds", "followUpAt", "followUpZoneId");

    private final LeadRoutingQueueRepository leads;
    private final LeadPreferredLocalityRepository preferredLocalities;
    private final LeadSourceEventRepository sourceEvents;
    private final LeadActivityRepository activities;
    private final LeadCommandReceiptRepository receipts;
    private final LeadContactPreferenceRepository contactPreferences;
    private final LeadDataProtection protection;
    private final LocalityRepository localities;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final StaffAccessService staffAccess;
    private final LeadAccessService leadAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserRepository users;

    public LeadService(LeadRoutingQueueRepository leads,
                       LeadPreferredLocalityRepository preferredLocalities,
                       LeadSourceEventRepository sourceEvents,
                       LeadActivityRepository activities,
                       LeadCommandReceiptRepository receipts,
                       LeadContactPreferenceRepository contactPreferences,
                       LeadDataProtection protection,
                       LocalityRepository localities,
                       SupportedCityRepository cities,
                       OperatingTeamRepository teams,
                       StaffAccessService staffAccess,
                       LeadAccessService leadAccess,
                       OperationalSecurityGuards guards,
                       OperationalAuditService audit,
                       TransactionalCommandBounds commandBounds,
                       DatabaseClock databaseClock,
                       EntityManager entityManager,
                       JdbcTemplate jdbc,
                       ObjectMapper objectMapper,
                       UserRepository users) {
        this.leads = leads;
        this.preferredLocalities = preferredLocalities;
        this.sourceEvents = sourceEvents;
        this.activities = activities;
        this.receipts = receipts;
        this.contactPreferences = contactPreferences;
        this.protection = protection;
        this.localities = localities;
        this.cities = cities;
        this.teams = teams;
        this.staffAccess = staffAccess;
        this.leadAccess = leadAccess;
        this.guards = guards;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public LeadPage list(Long actorId, Long cityId, Long teamId, LeadStatus status,
                         boolean ownerMe, Instant followUpBefore, int page, int size) {
        return list(actorId, cityId, teamId, status, ownerMe, followUpBefore,
                null, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public LeadPage list(Long actorId, Long cityId, Long teamId, LeadStatus status,
                         boolean ownerMe, Instant legacyFollowUpBefore, String query,
                         LeadFollowUpFilter followUp, String followUpZone,
                         int page, int size) {
        requirePage(page, size);
        if (followUp != null && legacyFollowUpBefore != null) {
            throw new IllegalArgumentException(
                    "followUp and followUpBefore cannot be used together");
        }
        String normalizedQuery = normalizeQueueQuery(query);
        String queryPattern = normalizedQuery == null
                ? null : "%" + escapeLikePattern(normalizedQuery.toLowerCase(Locale.ROOT)) + "%";
        Long queryLeadId = leadReference(normalizedQuery);
        String phoneSuffix = normalizedQuery != null && PHONE_SUFFIX.matcher(normalizedQuery).matches()
                ? "%" + normalizedQuery : null;

        boolean overdue = followUp == LeadFollowUpFilter.OVERDUE;
        boolean today = followUp == LeadFollowUpFilter.TODAY;
        boolean noFollowUp = followUp == LeadFollowUpFilter.NONE;
        FollowUpWindow window = followUpWindow(followUp, followUpZone,
                followUp == null ? null : databaseClock.now());
        LeadAccessContext context = leadAccess.context(actorId);
        PageRequest request = PageRequest.of(page, size);
        Page<LeadRoutingQueue> result = leads.findVisibleLeadPage(actorId,
                idsOrImpossible(context.visibleUnassignedCityIds()), idsOrImpossible(context.ownedTeamIds()),
                idsOrImpossible(context.supervisedTeamIds()), cityId, teamId, status, ownerMe,
                queryPattern, queryLeadId, phoneSuffix, overdue, today, noFollowUp,
                window.now(), window.todayStart(), window.todayEnd(),
                legacyFollowUpBefore, request);
        List<Long> leadIds = result.getContent().stream().map(LeadRoutingQueue::getId).toList();
        Map<Long, Instant> lastActivity = leadIds.isEmpty() ? Map.of()
                : activities.findLatestActivityByLeadIds(leadIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Instant) row[1]));
        List<LeadSummaryView> items = result.getContent().stream()
                .map(lead -> summary(lead, lastActivity.get(lead.getId()))).toList();
        return new LeadPage(items, result.getTotalElements(), page, size, result.getTotalPages());
    }

    static String normalizeQueueQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = WHITESPACE.matcher(value.trim()).replaceAll(" ");
        if (normalized.length() > 80) {
            throw new IllegalArgumentException("q must be at most 80 characters");
        }
        return normalized;
    }

    private static Long leadReference(String query) {
        if (query == null) return null;
        if (!NUMERIC_REFERENCE.matcher(query).matches()) {
            var matcher = LEAD_REFERENCE.matcher(query);
            if (!matcher.matches()) return null;
            query = matcher.group(1);
        }
        try {
            long value = Long.parseLong(query);
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static FollowUpWindow followUpWindow(LeadFollowUpFilter filter, String followUpZone,
                                         Instant capturedNow) {
        if (filter == null) return new FollowUpWindow(null, null, null);
        if (followUpZone == null || followUpZone.isBlank()) {
            throw new IllegalArgumentException("followUpZone is required with followUp");
        }
        final ZoneId zone;
        try {
            zone = ZoneId.of(followUpZone.trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("followUpZone must be a valid IANA timezone");
        }
        Instant now = Objects.requireNonNull(capturedNow, "capturedNow");
        if (filter != LeadFollowUpFilter.TODAY) return new FollowUpWindow(now, null, null);
        LocalDate localDay = now.atZone(zone).toLocalDate();
        return new FollowUpWindow(now,
                localDay.atStartOfDay(zone).toInstant(),
                localDay.plusDays(1).atStartOfDay(zone).toInstant());
    }

    record FollowUpWindow(Instant now, Instant todayStart, Instant todayEnd) {}

    private static String escapeLikePattern(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    @Transactional
    public LeadDuplicateCandidateResponse duplicateCandidates(
            Long actorId, LeadDuplicateCandidatesCommand command) {
        if (command == null || command.cityId() == null || command.cityId() <= 0
                || command.contactPhone() == null || command.contactPhone().isBlank()) {
            throw new IllegalArgumentException("A canonical cityId and contactPhone are required");
        }
        staffAccess.requireAttributableUser(actorId);
        DuplicateGuardScope initialScope =
                duplicateGuardScope(leadAccess.contextForDiagnostics(actorId), command.cityId());
        String normalized = LeadContract.normalizePhone(command.contactPhone());

        commandBounds.apply();
        guards.acquireDuplicateLookup(initialScope.cityIds(), initialScope.teamIds(), actorId);

        // Native row locks and the fresh queries below must not reuse entities loaded before a winning
        // authority or active-state writer committed.
        entityManager.clear();
        staffAccess.requireAttributableUser(actorId);
        LeadAccessContext context = leadAccess.contextForDiagnostics(actorId);
        DuplicateGuardScope freshScope = duplicateGuardScope(context, command.cityId());
        if (!initialScope.cityIds().containsAll(freshScope.cityIds())
                || !initialScope.teamIds().containsAll(freshScope.teamIds())) {
            throw new VisitOperationsConflictException(
                    "Lead lookup authority changed; retry the request from current state");
        }

        Instant now = databaseClock.now();
        Integer recent = jdbc.queryForObject(
                "select count(*) from lead_lookup_events where actor_user_id = ? "
                        + "and purpose = 'DUPLICATE_CANDIDATE' and occurred_at > ?",
                Integer.class, actorId, Timestamp.from(now.minusSeconds(3600)));
        if (recent != null && recent >= 60) {
            throw new LocationLookupRateLimitException();
        }
        jdbc.update("insert into lead_lookup_events(actor_user_id,purpose,occurred_at) "
                        + "values (?, 'DUPLICATE_CANDIDATE', ?)",
                actorId, Timestamp.from(now));
        List<LeadRoutingQueue> rows = leads.findDuplicateCandidates(normalized, command.cityId(),
                actorId, idsOrImpossible(context.globalAdmin()
                                ? freshScope.cityIds() : context.visibleUnassignedCityIds()),
                idsOrImpossible(context.globalAdmin()
                        ? List.of() : context.ownedTeamIds()),
                idsOrImpossible(context.globalAdmin()
                        ? freshScope.teamIds() : context.supervisedTeamIds()),
                PageRequest.of(0, 20));
        List<LeadDuplicateCandidateView> candidates = rows.stream()
                .map(lead -> new LeadDuplicateCandidateView(lead.getId(), lead.getVersion(),
                        mask(lead.getContactPhoneNormalized()), lead.getCustomerName(), lead.getStatus(),
                        lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId(),
                        lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId(),
                        lead.getOwner() != null && actorId.equals(lead.getOwner().getId())))
                .toList();
        return new LeadDuplicateCandidateResponse(candidates,
                users.existsByMobileNumberNormalized(normalized));
    }

    @Transactional(readOnly = true)
    public LeadDetailView detail(Long actorId, Long leadId) {
        LeadAccessContext context = leadAccess.context(actorId);
        LeadRoutingQueue lead = visibleLead(actorId, context, leadId);
        leadAccess.requireReadable(context, lead, actorId);
        return detail(lead);
    }

    /**
     * Applies the existing current City/Team/ownership visibility query before a dependent read.
     * Inaccessible and unknown Leads intentionally share the same hidden result.
     */
    LeadRoutingQueue requireReadableLead(Long actorId, Long leadId) {
        LeadAccessContext context = leadAccess.context(actorId);
        LeadRoutingQueue lead = visibleLead(actorId, context, leadId);
        leadAccess.requireReadable(context, lead, actorId);
        return lead;
    }

    @Transactional
    public LeadDetailView capture(Long actorId, String operationKey, LeadCaptureCommand command) {
        if (command == null) throw new IllegalArgumentException("Lead capture body is required");
        validateCaptureResolution(command);
        UUID operationId = parseOperationId(operationKey);
        String payloadHash = capturePayloadHash(command);
        lockOperation(actorId, operationId, "LEAD_CAPTURE");

        LeadCommandReceipt existing = receipts.findByActorIdAndOperationIdAndAction(
                        actorId, operationId, "LEAD_CAPTURE")
                .orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different Lead payload");
            }
            LeadRoutingQueue replay = leads.findById(parseLeadReference(existing.getResultReference()))
                    .orElseThrow(LeadService::hidden);
            LeadAccessContext replayContext = leadAccess.context(actorId);
            leadAccess.requireReadable(replayContext, replay, actorId);
            LeadSourceEvent replayEvent = sourceEvents
                    .findFirstByProviderAndProviderAccountIdAndExternalEventIdOrderByReceivedAtDesc(
                            "MANUAL", String.valueOf(actorId), operationId.toString())
                    .orElse(null);
            return detail(replay, captureOutcome(replay, replayEvent, command));
        }

        if (command.duplicateDecision() == LeadCaptureCommand.DuplicateDecision.ATTACH_ENQUIRY) {
            return attachManualEnquiry(actorId, operationId, payloadHash, command);
        }
        return createManualLead(actorId, operationId, payloadHash, command,
                command.duplicateDecision() == LeadCaptureCommand.DuplicateDecision.KEEP_DISTINCT);
    }

    private LeadDetailView attachManualEnquiry(Long actorId, UUID operationId, String payloadHash,
                                               LeadCaptureCommand command) {
        LeadRoutingQueue lead = lockLead(command.existingLeadId());
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
        requireVerified(lead);
        requireVersion(lead, command.expectedExistingLeadVersion());
        requireActiveLifecycle(lead);
        requireEnquiryCityAndTeamMatch(command, lead);

        LeadContract.validateBudget(command.budgetMin(), command.budgetMax());
        String currency = normalizedCurrency(command.currency());
        LeadContract.validateCurrency(currency);
        LeadContract.validateFollowUp(command.followUpAt(), command.followUpZoneId());
        List<Locality> localityRows = resolveLocalities(lead.getSupportedCity(),
                LeadContract.exactPositiveIds(command.preferredLocalityIds(), "preferredLocalityIds"));
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        LeadDataProtection.ProtectedValue snapshot = protectManualEnquirySnapshot(
                command, actorId, now, "ATTACHED_TO_EXISTING");

        LeadSourceEvent sourceEvent = new LeadSourceEvent(UUID.randomUUID(), "MANUAL",
                String.valueOf(actor.getId()), operationId.toString(), LeadSourceEventKind.MANUAL_PHONE,
                payloadHash, MANUAL_ATTACHED_FINGERPRINT, now,
                LeadSourceEventState.PROCESSED, null);
        sourceEvent.setSourceAttribution(
                LeadContract.text(command.sourceChannel(), 64, "Source channel", false),
                LeadContract.text(command.campaignRef(), 200, "Campaign reference", false),
                LeadContract.text(command.referralRef(), 200, "Referral reference", false));
        sourceEvent.setExtractedPayload(snapshot.ciphertext(), snapshot.iv(), snapshot.keyId());
        sourceEvent.attachTo(lead);
        sourceEvent.resolveAgainst(lead);
        sourceEvent = sourceEvents.saveAndFlush(sourceEvent);

        // Newly entered requirements remain evidence in the protected snapshot. The existing Lead's
        // authoritative requirements, contact, owner, consent and shortlists are deliberately unchanged.
        lead.touchCrmState();
        lead = leads.saveAndFlush(lead);
        String reason = LeadContract.text(command.reasonCode(), 64, "Reason", false);
        activities.save(new LeadActivity(lead, actor, "MANUAL_ENQUIRY_ATTACHED", sourceEvent,
                now, reason));
        audit.recordUserEvent(actor, "LEAD_MANUAL_ENQUIRY_ATTACHED", "LEAD", lead.getId(),
                reason == null ? "DUPLICATE_REVIEW_ATTACH" : command.reasonCode(),
                lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("previousVersion", command.expectedExistingLeadVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion()));
        receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, "LEAD_CAPTURE",
                payloadHash, lead.getId().toString(), now));
        LeadCaptureOutcomeView outcome = captureOutcome(lead, sourceEvent, command);
        return detail(lead, outcome);
    }

    private LeadDetailView createManualLead(Long actorId, UUID operationId, String payloadHash,
                                            LeadCaptureCommand command, boolean keptDistinct) {
        SupportedCity city = resolveCity(command.cityId());
        OperatingTeam team = resolveTeam(command.teamId());
        if (team != null) {
            if (city == null) city = team.getCity();
            if (city == null || !Objects.equals(team.getCity().getId(), city.getId())) {
                throw new IllegalArgumentException("Lead Team must belong to the selected canonical City");
            }
        }
        guards.acquire(city == null ? List.of() : List.of(city.getId()),
                team == null ? List.of() : List.of(team.getId()), List.of(actorId));

        // The initial reads only identify which guards are required. City/Team activity and the complete
        // User/profile/grant authority are re-read from persisted facts after those guards are held.
        if (city != null) {
            city = cities.findByIdAndActiveTrue(city.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The canonical City is no longer active"));
        }
        if (team != null) {
            team = teams.findByIdAndActiveTrue(team.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The Operating Team is no longer active"));
            Long guardedCityId = guards.teamCityId(team.getId());
            if (city == null || !city.getId().equals(guardedCityId)) {
                throw new IllegalArgumentException(
                        "Lead Team must belong to the selected canonical City");
            }
        }
        User actor = staffAccess.requireAttributableUser(actorId);
        LeadAccessContext context = leadAccess.context(actorId);
        requireCaptureScope(context, city, team);
        LeadRoutingQueue distinctFrom = null;
        if (keptDistinct && command.existingLeadId() != null) {
            distinctFrom = visibleLead(actorId, context, command.existingLeadId());
            requireVersion(distinctFrom, command.expectedExistingLeadVersion());
        }

        LeadContract.validateBudget(command.budgetMin(), command.budgetMax());
        String currency = normalizedCurrency(command.currency());
        LeadContract.validateCurrency(currency);
        LeadContract.validateFollowUp(command.followUpAt(), command.followUpZoneId());
        List<Locality> localityRows = resolveLocalities(city,
                LeadContract.exactPositiveIds(command.preferredLocalityIds(), "preferredLocalityIds"));
        Instant now = databaseClock.now();
        String resolution = keptDistinct ? "KEPT_DISTINCT" : "CREATED";
        LeadDataProtection.ProtectedValue snapshot = keptDistinct
                ? protectManualEnquirySnapshot(command, actorId, now, resolution) : null;

        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(city);
        lead.setOperatingTeam(team);
        lead.setOwner(team == null ? null : actor);
        lead.setCustomerName(LeadContract.text(command.customerName(), 160, "Customer name", false));
        lead.setContactPhoneNormalized(LeadContract.normalizePhone(command.contactPhone()));
        lead.setPhoneNumber(lead.getContactPhoneNormalized());
        lead.setCustomerSaid(LeadContract.text(command.customerSaid(), 4000, "Customer statement", false));
        lead.setIntent(command.intent() == null ? LeadIntent.UNSPECIFIED : command.intent());
        lead.setPropertyType(LeadContract.text(command.propertyType(), 32, "Property type", false));
        lead.setBhk(LeadContract.text(command.bhk(), 32, "BHK", false));
        lead.setBudgetMin(command.budgetMin());
        lead.setBudgetMax(command.budgetMax());
        lead.setBudget(command.budgetMax() != null ? command.budgetMax() : command.budgetMin());
        lead.setCurrency(currency);
        lead.setTargetMoveInDate(command.targetMoveInDate());
        lead.setFurnishing(LeadContract.text(command.furnishing(), 40, "Furnishing", false));
        lead.setTenantProfile(LeadContract.text(command.tenantProfile(), 64, "Tenant profile", false));
        lead.setParkingRequired(command.parkingRequired());
        lead.setPetsAllowed(command.petsAllowed());
        lead.setLiftRequired(command.liftRequired());
        lead.setFloorRequirement(LeadContract.text(command.floorRequirement(), 80, "Floor requirement", false));
        lead.setFollowUpAt(command.followUpAt());
        lead.setFollowUpZoneId(command.followUpZoneId());
        lead.setStatus(LeadStatus.NEW);
        lead.setLegacyProvenance(LeadLegacyProvenance.VERIFIED_MANUAL);
        lead = leads.saveAndFlush(lead);

        LeadSourceEvent sourceEvent = new LeadSourceEvent(UUID.randomUUID(), "MANUAL",
                String.valueOf(actor.getId()), operationId.toString(), LeadSourceEventKind.MANUAL_PHONE,
                payloadHash, keptDistinct ? MANUAL_DISTINCT_FINGERPRINT : "MANUAL_CANONICAL_V1",
                now, LeadSourceEventState.PROCESSED, null);
        sourceEvent.setSourceAttribution(
                LeadContract.text(command.sourceChannel(), 64, "Source channel", false),
                LeadContract.text(command.campaignRef(), 200, "Campaign reference", false),
                LeadContract.text(command.referralRef(), 200, "Referral reference", false));
        if (keptDistinct) {
            sourceEvent.setExtractedPayload(snapshot.ciphertext(), snapshot.iv(), snapshot.keyId());
            if (distinctFrom != null) sourceEvent.resolveAgainst(distinctFrom);
        }
        sourceEvent.attachTo(lead);
        sourceEvent = sourceEvents.saveAndFlush(sourceEvent);
        savePreferredLocalities(lead, localityRows);
        activities.save(new LeadActivity(lead, actor,
                keptDistinct ? "MANUAL_ENQUIRY_KEPT_DISTINCT" : "LEAD_CAPTURED",
                sourceEvent, now, keptDistinct ? command.reasonCode() : null));
        audit.recordUserEvent(actor,
                keptDistinct ? "LEAD_MANUAL_ENQUIRY_KEPT_DISTINCT" : "LEAD_CAPTURED",
                "LEAD", lead.getId(), keptDistinct ? command.reasonCode() : "MANUAL_INTAKE",
                city, team, Map.of());
        receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, "LEAD_CAPTURE",
                payloadHash, lead.getId().toString(), now));
        return detail(lead, captureOutcome(lead, sourceEvent, command));
    }

    private static void validateCaptureResolution(LeadCaptureCommand command) {
        boolean targetFieldsPresent = command.existingLeadId() != null
                || command.expectedExistingLeadVersion() != null
                || command.reasonCode() != null;
        if (command.duplicateDecision() == null) {
            if (targetFieldsPresent) {
                throw new IllegalArgumentException(
                        "Duplicate-resolution fields require an explicit duplicate decision");
            }
            return;
        }
        if (command.reasonCode() != null) {
            LeadContract.requireReasonCode(command.reasonCode());
        }
        if (command.duplicateDecision() == LeadCaptureCommand.DuplicateDecision.ATTACH_ENQUIRY) {
            if (command.existingLeadId() == null || command.existingLeadId() <= 0
                    || command.expectedExistingLeadVersion() == null
                    || command.expectedExistingLeadVersion() < 0) {
                throw new IllegalArgumentException(
                        "Attach enquiry requires existingLeadId and expectedExistingLeadVersion");
            }
            return;
        }
        if ((command.existingLeadId() == null) !=
                (command.expectedExistingLeadVersion() == null)
                || (command.existingLeadId() != null && command.existingLeadId() <= 0)
                || (command.expectedExistingLeadVersion() != null
                    && command.expectedExistingLeadVersion() < 0)) {
            throw new IllegalArgumentException(
                    "Keep distinct requires an existing Lead ID and version together when supplied");
        }
        LeadContract.requireReasonCode(command.reasonCode());
    }

    String capturePayloadHash(LeadCaptureCommand command) {
        LegacyCaptureFingerprint legacy = LegacyCaptureFingerprint.from(command);
        if (command.duplicateDecision() == null) return payloadHash(legacy);
        return payloadHash(new ExtendedCaptureFingerprint(
                "LEAD_CAPTURE_CANONICAL_V2", legacy, command.duplicateDecision(),
                command.existingLeadId(), command.expectedExistingLeadVersion(), command.reasonCode()));
    }

    private static void requireEnquiryCityAndTeamMatch(LeadCaptureCommand command,
                                                       LeadRoutingQueue lead) {
        if (command.cityId() == null) {
            throw new IllegalArgumentException("A canonical City is required for Lead attachment");
        }
        Long targetCityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        if (!command.cityId().equals(targetCityId)) {
            throw new IllegalArgumentException(
                    "The selected enquiry City does not match the target Lead's canonical City");
        }
        if (command.teamId() != null) {
            Long targetTeamId = lead.getOperatingTeam() == null
                    ? null : lead.getOperatingTeam().getId();
            if (!command.teamId().equals(targetTeamId)) {
                throw new IllegalArgumentException(
                        "The selected enquiry Team does not match the target Lead's Team");
            }
        }
    }

    private static String normalizedCurrency(String value) {
        return value == null || value.isBlank() ? "INR" : value.trim().toUpperCase(Locale.ROOT);
    }

    private LeadDataProtection.ProtectedValue protectManualEnquirySnapshot(
            LeadCaptureCommand command, Long actorId, Instant capturedAt, String resolution) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("schemaVersion", "MANUAL_ENQUIRY_SNAPSHOT_V1");
        snapshot.put("capturedAt", capturedAt.toString());
        snapshot.put("actorUserId", actorId);
        snapshot.put("resolution", resolution);
        snapshot.put("targetLeadId", command.existingLeadId());
        snapshot.put("cityId", command.cityId());
        snapshot.put("teamId", command.teamId());
        snapshot.put("customerName", command.customerName());
        snapshot.put("contactPhone", command.contactPhone());
        snapshot.put("customerSaid", command.customerSaid());
        snapshot.put("intent", command.intent());
        snapshot.put("propertyType", command.propertyType());
        snapshot.put("bhk", command.bhk());
        snapshot.put("budgetMin", command.budgetMin());
        snapshot.put("budgetMax", command.budgetMax());
        snapshot.put("currency", normalizedCurrency(command.currency()));
        snapshot.put("targetMoveInDate", command.targetMoveInDate());
        snapshot.put("furnishing", command.furnishing());
        snapshot.put("tenantProfile", command.tenantProfile());
        snapshot.put("parkingRequired", command.parkingRequired());
        snapshot.put("petsAllowed", command.petsAllowed());
        snapshot.put("liftRequired", command.liftRequired());
        snapshot.put("floorRequirement", command.floorRequirement());
        snapshot.put("preferredLocalityIds", command.preferredLocalityIds());
        snapshot.put("followUpAt", command.followUpAt());
        snapshot.put("followUpZoneId", command.followUpZoneId());
        snapshot.put("sourceChannel", command.sourceChannel());
        snapshot.put("campaignRef", command.campaignRef());
        snapshot.put("referralRef", command.referralRef());
        snapshot.put("reasonCode", command.reasonCode());
        try {
            return protection.protect(objectMapper.writeValueAsString(snapshot));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Manual enquiry snapshot could not be serialized");
        }
    }

    private static LeadCaptureOutcomeView captureOutcome(LeadRoutingQueue lead,
                                                         LeadSourceEvent sourceEvent,
                                                         LeadCaptureCommand command) {
        String resolution = command.duplicateDecision()
                == LeadCaptureCommand.DuplicateDecision.ATTACH_ENQUIRY
                ? "ATTACHED_TO_EXISTING"
                : command.duplicateDecision()
                    == LeadCaptureCommand.DuplicateDecision.KEEP_DISTINCT
                    ? "KEPT_DISTINCT"
                    : "CREATED";
        if (sourceEvent != null && MANUAL_ATTACHED_FINGERPRINT.equals(
                sourceEvent.getFingerprintVersion())) {
            resolution = "ATTACHED_TO_EXISTING";
        } else if (sourceEvent != null && MANUAL_DISTINCT_FINGERPRINT.equals(
                sourceEvent.getFingerprintVersion())) {
            resolution = "KEPT_DISTINCT";
        }
        return new LeadCaptureOutcomeView(resolution, lead.getId(), lead.getVersion(),
                command.reasonCode(), manualEnquiryView(sourceEvent));
    }

    private static LeadManualEnquiryView manualEnquiryView(LeadSourceEvent event) {
        if (event == null || !"MANUAL".equals(event.getProvider())
                || !event.hasProtectedExtractedPayload()) return null;
        return new LeadManualEnquiryView(event.getEventKind(), event.getSourceChannel(),
                event.getReceivedAt(), event.getProcessingState(), event.getFingerprintVersion(), true);
    }

    @Transactional
    public LeadDetailView patch(Long actorId, Long leadId, LeadPatchCommand command) {
        if (command == null || command.changes() == null) {
            throw new IllegalArgumentException("Lead changes are required");
        }
        if (command.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        for (String field : command.changes().keySet()) {
            if (!PATCH_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsupported Lead field: " + field);
            }
        }
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
        requireVerified(lead);
        requireVersion(lead, command.expectedVersion());
        requireActiveLifecycle(lead);
        applyPatch(lead, command.changes());
        leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_UPDATED", null, databaseClock.now(), null));
        audit.recordUserEvent(actor, "LEAD_UPDATED", "LEAD", lead.getId(), "LEAD_EDIT",
                lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("previousVersion", command.expectedVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion()));
        return detail(lead);
    }

    @Transactional
    public LeadDetailView transition(Long actorId, Long leadId, LeadLifecycleCommand command) {
        if (command == null || command.status() == null || command.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion and lifecycle status are required");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
        requireVerified(lead);
        requireVersion(lead, command.expectedVersion());
        LeadStatus previous = lead.getStatus();
        LeadContract.requireTransition(previous, command.status());
        lead.setStatus(command.status());
        leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LIFECYCLE_" + command.status().name(), null,
                databaseClock.now(), command.reasonCode()));
        audit.recordUserEvent(actor, "LEAD_LIFECYCLE_CHANGED", "LEAD", lead.getId(),
                command.reasonCode(), lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("fromStatus", previous.name(), "toStatus", command.status().name(),
                        "previousVersion", command.expectedVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion()));
        return detail(lead);
    }

    @Transactional
    public LeadDetailView attachSource(Long actorId, Long leadId, String operationKey,
                                       LeadSourceAttachCommand command) {
        if (command == null || command.expectedVersion() == null || command.eventId() == null
                || command.decision() == null) {
            throw new IllegalArgumentException(
                    "expectedVersion, eventId and duplicate-resolution decision are required");
        }
        if (command.decision() == LeadSourceAttachCommand.Decision.KEEP_DISTINCT) {
            LeadContract.requireReasonCode(command.reasonCode());
        }
        UUID operationId = parseOperationId(operationKey);
        String payloadHash = payloadHash(leadId, command);
        lockOperation(actorId, operationId, "LEAD_SOURCE_ATTACH");
        LeadCommandReceipt existingReceipt = receipts.findByActorIdAndOperationIdAndAction(
                actorId, operationId, "LEAD_SOURCE_ATTACH").orElse(null);
        if (existingReceipt != null) {
            if (!existingReceipt.getPayloadHash().equals(payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different source decision");
            }
            LeadRoutingQueue replay = leads.findById(parseLeadReference(
                    existingReceipt.getResultReference())).orElseThrow(LeadService::hidden);
            LeadAccessContext replayContext = leadAccess.context(actorId);
            leadAccess.requireReadable(replayContext, replay, actorId);
            return detail(replay);
        }
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
        requireVersion(lead, command.expectedVersion());
        requireActiveLifecycle(lead);
        LeadSourceEvent event = sourceEvents.findLockedById(command.eventId())
                .orElseThrow(LeadService::hidden);
        LeadRoutingQueue attached = event.getLead();
        if (attached != null && !attached.getId().equals(leadId)) {
            throw hidden();
        }
        if (event.getEventKind() != LeadSourceEventKind.META_LEADGEN) {
            throw new VisitOperationsConflictException(
                    "This source event is not eligible for duplicate resolution");
        }
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        String activityType;
        if (command.decision() == LeadSourceAttachCommand.Decision.ATTACH_ENQUIRY) {
            event.attachTo(lead);
            event.resolveAgainst(lead);
            event.setProcessingState(LeadSourceEventState.PROCESSED);
            event.setErrorCode(null);
            activityType = "SOURCE_ENQUIRY_ATTACHED";
        } else {
            event.resolveAgainst(lead);
            event.setProcessingState(LeadSourceEventState.PROCESSED);
            event.setErrorCode("KEPT_DISTINCT_FROM_LEAD");
            activityType = "SOURCE_ENQUIRY_KEPT_DISTINCT";
        }
        event.touch(now);
        sourceEvents.saveAndFlush(event);
        lead.touchCrmState();
        lead = leads.saveAndFlush(lead);
        activities.save(new LeadActivity(lead, actor, activityType, null, now,
                LeadContract.text(command.reasonCode(), 64, "Reason", false)));
        audit.recordUserEvent(actor, activityType, "LEAD", lead.getId(), "DUPLICATE_RESOLUTION",
                lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, "LEAD_SOURCE_ATTACH",
                payloadHash, lead.getId().toString(), now));
        return detail(lead);
    }

    @Transactional
    public LeadActivityView logActivity(Long actorId, Long leadId, LeadActivityCommand command) {
        if (command == null || command.expectedVersion() == null || command.type() == null) {
            throw new IllegalArgumentException("expectedVersion and activity type are required");
        }
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
        requireVersion(lead, command.expectedVersion());
        requireActiveLifecycle(lead);
        String note = LeadContract.text(command.note(), 4000, "Activity note",
                command.type() == LeadActivityCommand.Type.NOTE_ADDED);
        LeadContract.validateFollowUp(command.followUpAt(), command.followUpZoneId());
        if (command.followUpAt() != null) {
            lead.setFollowUpAt(command.followUpAt());
            lead.setFollowUpZoneId(command.followUpZoneId());
        }
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        lead.touchCrmState();
        leads.saveAndFlush(lead);
        LeadActivity activity = activities.saveAndFlush(
                new LeadActivity(lead, actor, command.type().name(), null, now, note));
        audit.recordUserEvent(actor, "LEAD_ACTIVITY_LOGGED", "LEAD", leadId,
                command.type().name(), lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        return new LeadActivityView(activity.getId(), activity.getActivityType(),
                activity.getOccurredAt(), activity.getNote());
    }

    @Transactional
    public LeadDetailView route(Long actorId, Long leadId, LeadRoutingCommand command) {
        if (command == null || command.expectedVersion() == null || command.cityId() == null
                || command.teamId() == null || command.expectedCityVersion() == null
                || command.expectedTeamVersion() == null) {
            throw new IllegalArgumentException("Lead routing requires current City/Team versions");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        LeadRoutingQueue lead = lockLead(leadId);
        Long leadCityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long leadTeamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        List<Long> cityGuards = java.util.stream.Stream.of(leadCityId, command.cityId())
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<Long> teamGuards = java.util.stream.Stream.of(leadTeamId, command.teamId())
                .filter(Objects::nonNull).distinct().sorted().toList();
        guards.acquire(cityGuards, teamGuards, List.of(actorId));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireReadable(context, lead, actorId);
        requireVerified(lead);
        requireActiveLifecycle(lead);
        if (!context.canRoute(command.cityId())) {
            throw new AccessDeniedException("Current City Lead-routing authority is required");
        }
        requireVersion(lead, command.expectedVersion());
        if (lead.getOperatingTeam() != null || lead.getOwner() != null) {
            throw new VisitOperationsConflictException("This Lead is already assigned or routed");
        }
        if (lead.getSupportedCity() != null && !command.cityId().equals(lead.getSupportedCity().getId())) {
            throw new VisitOperationsConflictException("Lead routing cannot change its canonical City");
        }
        if (!Long.valueOf(command.expectedCityVersion()).equals(guards.cityVersion(command.cityId()))
                || !Long.valueOf(command.expectedTeamVersion()).equals(guards.teamVersion(command.teamId()))
                || !command.cityId().equals(guards.teamCityId(command.teamId()))) {
            throw new VisitOperationsConflictException("City or Team changed while routing was being reviewed");
        }
        SupportedCity city = cities.findByIdAndActiveTrue(command.cityId())
                .orElseThrow(LeadService::hidden);
        OperatingTeam team = teams.findByIdAndActiveTrue(command.teamId())
                .orElseThrow(LeadService::hidden);
        lead.setSupportedCity(city);
        lead.setOperatingTeam(team);
        lead.setOwner(null);
        leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_ROUTED", null, databaseClock.now(), command.reasonCode()));
        audit.recordUserEvent(actor, "LEAD_ROUTED", "LEAD", lead.getId(), command.reasonCode(), city, team,
                Map.of("previousVersion", command.expectedVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion(),
                        "teamVersion", command.expectedTeamVersion()));
        return detail(lead);
    }

    @Transactional
    public LeadDetailView assign(Long actorId, Long leadId, LeadAssignmentCommand command) {
        if (command == null || command.expectedVersion() == null
                || command.targetCoordinatorUserId() == null) {
            throw new IllegalArgumentException("Lead assignment requires a version and target coordinator");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead,
                List.of(command.targetCoordinatorUserId()));
        leadAccess.requireReadable(context, lead, actorId);
        requireVerified(lead);
        requireActiveLifecycle(lead);
        if (lead.getOperatingTeam() == null) {
            throw new VisitOperationsConflictException("A Lead must be routed before it can be assigned");
        }
        requireVersion(lead, command.expectedVersion());
        if (!context.canAssign(lead.getOperatingTeam().getId())) {
            throw new AccessDeniedException("Current Team supervision authority is required");
        }
        Long teamId = lead.getOperatingTeam().getId();
        if (!staffAccess.hasCapabilityAt(command.targetCoordinatorUserId(),
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)) {
            throw new IllegalArgumentException("Target coordinator does not hold current Team authority");
        }
        if (lead.getOwner() != null
                && command.targetCoordinatorUserId().equals(lead.getOwner().getId())) {
            return detail(lead);
        }
        User target = staffAccess.requireAttributableUser(command.targetCoordinatorUserId());
        lead.setOwner(target);
        leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_ASSIGNED", null,
                databaseClock.now(), command.reasonCode()));
        audit.recordUserEvent(actor, "LEAD_ASSIGNED", "LEAD", lead.getId(), command.reasonCode(),
                lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("coordinatorUserId", target.getId(),
                        "previousVersion", command.expectedVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion()));
        return detail(lead);
    }

    @Transactional
    public LeadDetailView transferTeam(Long actorId, Long leadId, LeadTeamTransferCommand command) {
        if (command == null || command.expectedVersion() == null || command.destinationTeamId() == null
                || command.expectedTeamVersion() == null) {
            throw new IllegalArgumentException("Lead transfer requires current Team versions");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        LeadRoutingQueue lead = lockLead(leadId);
        Long sourceTeamId = lead.getOperatingTeam() == null
                ? null : lead.getOperatingTeam().getId();
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                sourceTeamId == null
                        ? List.of(command.destinationTeamId())
                        : sourceTeamId.equals(command.destinationTeamId())
                        ? List.of(sourceTeamId) : List.of(sourceTeamId, command.destinationTeamId()),
                command.targetCoordinatorUserId() == null
                        ? List.of(actorId) : List.of(actorId, command.targetCoordinatorUserId()));
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireReadable(context, lead, actorId);
        requireVerified(lead);
        requireActiveLifecycle(lead);
        if (sourceTeamId == null) {
            throw new VisitOperationsConflictException("An unrouted Lead must be routed before transfer");
        }
        requireVersion(lead, command.expectedVersion());
        if (!context.supervisedTeamIds().contains(sourceTeamId)
                || !context.supervisedTeamIds().contains(command.destinationTeamId())) {
            throw new AccessDeniedException("Both source and destination Team supervision are required");
        }
        if (!Long.valueOf(command.expectedTeamVersion()).equals(guards.teamVersion(command.destinationTeamId()))) {
            throw new VisitOperationsConflictException("Destination Team changed during transfer review");
        }
        OperatingTeam destination = teams.findByIdAndActiveTrue(command.destinationTeamId())
                .orElseThrow(LeadService::hidden);
        if (cityId == null || !cityId.equals(destination.getCity().getId())) {
            throw new VisitOperationsConflictException("Lead transfer cannot change its canonical City");
        }
        Long targetId = command.targetCoordinatorUserId();
        if (targetId != null && !staffAccess.hasCapabilityAt(targetId, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, destination.getId())) {
            throw new IllegalArgumentException("Target coordinator does not hold destination Team authority");
        }
        if (targetId == null && lead.getOwner() != null
                && !staffAccess.hasCapabilityAt(lead.getOwner().getId(), StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, destination.getId())) {
            lead.setOwner(null);
        } else if (targetId != null) {
            lead.setOwner(staffAccess.requireAttributableUser(targetId));
        }
        lead.setOperatingTeam(destination);
        leads.saveAndFlush(lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_TEAM_TRANSFERRED", null,
                databaseClock.now(), command.reasonCode()));
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("sourceTeamId", sourceTeamId);
        details.put("destinationTeamId", destination.getId());
        if (lead.getOwner() != null) details.put("coordinatorUserId", lead.getOwner().getId());
        details.put("previousVersion", command.expectedVersion());
        details.put("resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion());
        audit.recordUserEvent(actor, "LEAD_TEAM_TRANSFERRED", "LEAD", lead.getId(),
                command.reasonCode(), lead.getSupportedCity(), destination, details);
        return detail(lead);
    }

    @Transactional
    public LeadContactView revealContact(Long actorId, Long leadId) {
        LeadRoutingQueue lead = lockLead(leadId);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        if (!context.canRevealContact(lead, actorId)) throw hidden();
        if (lead.getContactPhoneNormalized() == null) {
            throw new VisitOperationsConflictException("This Lead has no captured contact number");
        }
        User actor = staffAccess.requireAttributableUser(actorId);
        audit.recordUserEvent(actor, "LEAD_CONTACT_REVEALED", "LEAD", lead.getId(), "OWNED_LEAD_CONTACT",
                lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        return new LeadContactView(lead.getId(), lead.getCustomerName(), lead.getContactPhoneNormalized());
    }

    @Transactional(readOnly = true)
    public LeadActivityPage activity(Long actorId, Long leadId, int page, int size) {
        requirePage(page, size);
        LeadAccessContext context = leadAccess.context(actorId);
        LeadRoutingQueue lead = visibleLead(actorId, context, leadId);
        leadAccess.requireReadable(context, lead, actorId);
        Page<LeadActivity> result = activities.findAllByLead_IdOrderByOccurredAtDescIdDesc(
                leadId, PageRequest.of(page, size));
        return new LeadActivityPage(result.getContent().stream()
                .map(item -> new LeadActivityView(item.getId(), item.getActivityType(),
                        item.getOccurredAt(), item.getNote())).toList(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LeadCommandReceiptView receipt(Long actorId, UUID operationId, String action) {
        leadAccess.context(actorId);
        LeadCommandReceipt receipt;
        if (action == null || action.isBlank()) {
            List<LeadCommandReceipt> matches = receipts
                    .findAllByActorIdAndOperationIdOrderByCompletedAtDesc(actorId, operationId);
            if (matches.isEmpty()) {
                return new LeadCommandReceiptView("NOT_RECORDED", null, null, null);
            }
            if (matches.size() > 1) {
                throw new VisitOperationsConflictException(
                        "This operation ID has more than one command type; specify action");
            }
            receipt = matches.get(0);
        } else {
            receipt = receipts.findByActorIdAndOperationIdAndAction(actorId, operationId, action)
                    .orElse(null);
            if (receipt == null) {
                return new LeadCommandReceiptView("NOT_RECORDED", action, null, null);
            }
        }
        if ("LEAD_CAPTURE".equals(receipt.getAction())
                || "LEAD_SOURCE_ATTACH".equals(receipt.getAction())) {
            LeadRoutingQueue lead = leads.findById(parseLeadReference(receipt.getResultReference()))
                    .orElseThrow(LeadService::hidden);
            leadAccess.requireReadable(leadAccess.context(actorId), lead, actorId);
        } else if ("LEAD_SHARE_PREPARE".equals(receipt.getAction())) {
            Long shareLeadId;
            try {
                shareLeadId = jdbc.queryForObject(
                        "select lead_id from lead_shares where id = ?::uuid", Long.class,
                        receipt.getResultReference());
            } catch (RuntimeException exception) {
                throw hidden();
            }
            if (shareLeadId == null) throw hidden();
            LeadAccessContext context = leadAccess.context(actorId);
            LeadRoutingQueue lead = visibleLead(actorId, context, shareLeadId);
            leadAccess.requireReadable(context, lead, actorId);
        } else if ("LEAD_SHORTLIST_CREATE".equals(receipt.getAction())) {
            Long shortlistLeadId;
            try {
                shortlistLeadId = jdbc.queryForObject(
                        "select lead_id from lead_shortlists where id = ?", Long.class,
                        parseLeadReference(receipt.getResultReference()));
            } catch (RuntimeException exception) {
                throw hidden();
            }
            if (shortlistLeadId == null) throw hidden();
            LeadAccessContext context = leadAccess.context(actorId);
            LeadRoutingQueue lead = visibleLead(actorId, context, shortlistLeadId);
            leadAccess.requireReadable(context, lead, actorId);
        } else if (LeadContactPreferenceService.COMMAND_ACTION.equals(receipt.getAction())) {
            LeadContactPreferenceService.ResultReference reference =
                    LeadContactPreferenceService.parseResultReference(receipt.getResultReference());
            LeadContactPreference preference = contactPreferences.findById(reference.preferenceId())
                    .filter(value -> value.getRevision().equals(reference.revision()))
                    .orElseThrow(LeadService::hidden);
            LeadAccessContext context = leadAccess.context(actorId);
            LeadRoutingQueue lead = visibleLead(actorId, context, preference.getLead().getId());
            leadAccess.requireReadable(context, lead, actorId);
        }
        return new LeadCommandReceiptView("RECORDED", receipt.getAction(),
                receipt.getResultReference(), receipt.getCompletedAt());
    }

    private LeadRoutingQueue visibleLead(Long actorId, LeadAccessContext context, Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        return leads.findVisibleById(leadId, actorId,
                        idsOrImpossible(context.visibleUnassignedCityIds()),
                        idsOrImpossible(context.ownedTeamIds()),
                        idsOrImpossible(context.supervisedTeamIds()))
                .orElseThrow(LeadService::hidden);
    }

    private static void requireCaptureScope(LeadAccessContext context, SupportedCity city,
                                            OperatingTeam team) {
        if (city == null) {
            throw new IllegalArgumentException("A canonical City is required for this Lead capture");
        } else if (team != null) {
            if (!context.ownedTeamIds().contains(team.getId())) {
                throw new AccessDeniedException("Current OPS_COORDINATE authority for this Team is required");
            }
        } else if (!context.intakeCityIds().contains(city.getId())) {
            throw new AccessDeniedException("Current OPS_INTAKE authority for this City is required");
        }
    }

    private DuplicateGuardScope duplicateGuardScope(LeadAccessContext context, Long cityId) {
        if (context.globalAdmin()) {
            if (!cities.existsById(cityId)) {
                return new DuplicateGuardScope(List.of(), List.of());
            }
            return new DuplicateGuardScope(List.of(cityId),
                    teams.findAllByCityIdOrderByIdAsc(cityId).stream()
                            .map(OperatingTeam::getId).toList());
        }
        boolean cityAuthority = context.visibleUnassignedCityIds().contains(cityId);
        List<Long> teamIds = new ArrayList<>(context.ownedTeamIds());
        teamIds.addAll(context.supervisedTeamIds());
        List<Long> matchingTeamIds = List.of();
        if (!teamIds.isEmpty()) {
            matchingTeamIds = teams.findAllById(teamIds.stream().distinct().toList()).stream()
                    .filter(team -> team.getCity() != null
                            && cityId.equals(team.getCity().getId()))
                    .map(OperatingTeam::getId)
                    .sorted()
                    .toList();
        }
        if (cityAuthority || !matchingTeamIds.isEmpty()) {
            return new DuplicateGuardScope(List.of(cityId), matchingTeamIds);
        }
        throw new AccessDeniedException("Current Lead authority for this City is required");
    }

    private record DuplicateGuardScope(List<Long> cityIds, List<Long> teamIds) {}

    /**
     * Preserves the exact pre-extension Jackson shape for legacy LEAD_CAPTURE receipts. Extended
     * commands use a versioned outer record so a DTO addition cannot invalidate old idempotency keys.
     */
    private record LegacyCaptureFingerprint(
            Long cityId,
            Long teamId,
            String customerName,
            String contactPhone,
            String customerSaid,
            LeadIntent intent,
            String propertyType,
            String bhk,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String currency,
            LocalDate targetMoveInDate,
            String furnishing,
            String tenantProfile,
            Boolean parkingRequired,
            Boolean petsAllowed,
            Boolean liftRequired,
            String floorRequirement,
            List<Object> preferredLocalityIds,
            Instant followUpAt,
            String followUpZoneId,
            String sourceChannel,
            String campaignRef,
            String referralRef) {

        private static LegacyCaptureFingerprint from(LeadCaptureCommand command) {
            return new LegacyCaptureFingerprint(command.cityId(), command.teamId(),
                    command.customerName(), command.contactPhone(), command.customerSaid(),
                    command.intent(), command.propertyType(), command.bhk(), command.budgetMin(),
                    command.budgetMax(), command.currency(), command.targetMoveInDate(),
                    command.furnishing(), command.tenantProfile(), command.parkingRequired(),
                    command.petsAllowed(), command.liftRequired(), command.floorRequirement(),
                    command.preferredLocalityIds(), command.followUpAt(), command.followUpZoneId(),
                    command.sourceChannel(), command.campaignRef(), command.referralRef());
        }
    }

    private record ExtendedCaptureFingerprint(
            String canonicalVersion,
            LegacyCaptureFingerprint enquiry,
            LeadCaptureCommand.DuplicateDecision duplicateDecision,
            Long existingLeadId,
            Long expectedExistingLeadVersion,
            String reasonCode) {}

    private LeadSummaryView summary(LeadRoutingQueue lead, Instant lastActivityAt) {
        List<String> gaps = new ArrayList<>();
        if (lead.getContactPhoneNormalized() == null) gaps.add("CONTACT");
        if (lead.getSupportedCity() == null) gaps.add("CITY");
        if (lead.getBudgetMin() == null && lead.getBudgetMax() == null) gaps.add("BUDGET");
        if (lead.getPropertyType() == null) gaps.add("PROPERTY_TYPE");
        return new LeadSummaryView(
                lead.getId(), lead.getVersion(), lead.getCustomerName(), mask(lead.getContactPhoneNormalized()),
                lead.getStatus(), lead.getIntent(),
                lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId(),
                lead.getSupportedCity() == null ? null : lead.getSupportedCity().getDisplayName(),
                lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId(),
                lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getDisplayName(),
                lead.getOwner() == null ? null : lead.getOwner().getId(),
                lead.getOwner() == null ? null : lead.getOwner().getFullName(),
                lead.getFollowUpAt(), lead.getFollowUpZoneId(), lastActivityAt, List.copyOf(gaps));
    }

    private LeadDetailView detail(LeadRoutingQueue lead) {
        return detail(lead, null);
    }

    private LeadDetailView detail(LeadRoutingQueue lead, LeadCaptureOutcomeView captureOutcome) {
        List<Long> localityIds = preferredLocalities.findAllByLead_IdOrderByLocality_IdAsc(lead.getId())
                .stream().map(item -> item.getLocality().getId()).toList();
        Instant lastActivityAt = activities.findFirstByLead_IdOrderByOccurredAtDescIdDesc(lead.getId())
                .map(LeadActivity::getOccurredAt).orElse(null);
        List<LeadSourceAttributionView> sourceAttributions =
                sourceEvents.findAllByLead_IdOrderByReceivedAtDescIdDesc(lead.getId()).stream()
                        .map(event -> new LeadSourceAttributionView(event.getId(), event.getEventKind(),
                                event.getSourceChannel(), event.getCampaignRef(), event.getReferralRef(),
                                event.getReceivedAt(), event.getProcessingState(),
                                event.getFingerprintVersion(), manualEnquiryView(event)))
                        .toList();
        List<String> communicationPrerequisites = new ArrayList<>();
        if (lead.getContactPhoneNormalized() == null) communicationPrerequisites.add("CONTACT");
        LeadContactPreference latestPreference =
                contactPreferences.findFirstByLead_IdOrderByRevisionDesc(lead.getId()).orElse(null);
        if (latestPreference == null) {
            communicationPrerequisites.add("PROPERTY_SHARING_AGREEMENT");
        } else if (latestPreference.getConsentStatus() != LeadConsentStatus.GRANTED) {
            communicationPrerequisites.add("PROPERTY_SHARING_AGREEMENT");
        } else if (latestPreference.getDestinationChoice() ==
                com.indore.pathome.spaces.entity.LeadDestinationChoice.NONE) {
            communicationPrerequisites.add("AGREED_DESTINATION");
        }
        List<String> conversionPrerequisites = new ArrayList<>();
        if (lead.getSupportedCity() == null) conversionPrerequisites.add("CITY");
        if (lead.getContactPhoneNormalized() == null) conversionPrerequisites.add("CONTACT");
        if (lead.getBudgetMin() == null && lead.getBudgetMax() == null) conversionPrerequisites.add("BUDGET");
        if (lead.getPropertyType() == null) conversionPrerequisites.add("PROPERTY_TYPE");
        conversionPrerequisites.add("CUSTOMER_ACCOUNT_PAIRING_PROOF");
        return new LeadDetailView(summary(lead, lastActivityAt), lead.getCustomerSaid(), lead.getPropertyType(), lead.getBhk(),
                lead.getBudgetMin(), lead.getBudgetMax(), lead.getCurrency(), lead.getTargetMoveInDate(),
                lead.getFurnishing(), lead.getTenantProfile(), lead.getParkingRequired(),
                lead.getPetsAllowed(), lead.getLiftRequired(), lead.getFloorRequirement(), localityIds,
                sourceAttributions, communicationPrerequisites, conversionPrerequisites,
                lead.getLinkedUser() != null, lead.getLegacyProvenance(), captureOutcome);
    }

    private void applyPatch(LeadRoutingQueue lead, Map<String, Object> changes) {
        if (changes.containsKey("customerName")) {
            lead.setCustomerName(LeadContract.text(asString(changes.get("customerName")), 160, "Customer name", false));
        }
        if (changes.containsKey("contactPhone")) {
            String normalized = LeadContract.normalizePhone(asString(changes.get("contactPhone")));
            lead.setContactPhoneNormalized(normalized);
            lead.setPhoneNumber(normalized);
        }
        if (changes.containsKey("customerSaid")) {
            lead.setCustomerSaid(LeadContract.text(asString(changes.get("customerSaid")),
                    4000, "Customer statement", false));
        }
        if (changes.containsKey("intent")) {
            String value = asString(changes.get("intent"));
            if (value == null) {
                lead.setIntent(LeadIntent.UNSPECIFIED);
            } else {
                try {
                    lead.setIntent(LeadIntent.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Lead intent is invalid");
                }
            }
        }
        if (changes.containsKey("propertyType")) {
            lead.setPropertyType(LeadContract.text(asString(changes.get("propertyType")), 32, "Property type", false));
        }
        if (changes.containsKey("bhk")) {
            lead.setBhk(LeadContract.text(asString(changes.get("bhk")), 32, "BHK", false));
        }
        if (changes.containsKey("budgetMin")) lead.setBudgetMin(asDecimal(changes.get("budgetMin"), "budgetMin"));
        if (changes.containsKey("budgetMax")) lead.setBudgetMax(asDecimal(changes.get("budgetMax"), "budgetMax"));
        LeadContract.validateBudget(lead.getBudgetMin(), lead.getBudgetMax());
        lead.setBudget(lead.getBudgetMax() != null ? lead.getBudgetMax() : lead.getBudgetMin());
        if (changes.containsKey("currency")) {
            String currency = asString(changes.get("currency"));
            currency = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
            LeadContract.validateCurrency(currency);
            lead.setCurrency(currency);
        }
        if (changes.containsKey("targetMoveInDate")) {
            lead.setTargetMoveInDate(asDate(changes.get("targetMoveInDate")));
        }
        if (changes.containsKey("furnishing")) {
            lead.setFurnishing(LeadContract.text(asString(changes.get("furnishing")), 40, "Furnishing", false));
        }
        if (changes.containsKey("tenantProfile")) {
            lead.setTenantProfile(LeadContract.text(asString(changes.get("tenantProfile")), 64,
                    "Tenant profile", false));
        }
        if (changes.containsKey("parkingRequired")) {
            lead.setParkingRequired(asBoolean(changes.get("parkingRequired")));
        }
        if (changes.containsKey("petsAllowed")) {
            lead.setPetsAllowed(asBoolean(changes.get("petsAllowed")));
        }
        if (changes.containsKey("liftRequired")) {
            lead.setLiftRequired(asBoolean(changes.get("liftRequired")));
        }
        if (changes.containsKey("floorRequirement")) {
            lead.setFloorRequirement(LeadContract.text(asString(changes.get("floorRequirement")),
                    80, "Floor requirement", false));
        }
        String zone = changes.containsKey("followUpZoneId") ? asString(changes.get("followUpZoneId"))
                : lead.getFollowUpZoneId();
        Instant followUp = changes.containsKey("followUpAt")
                ? asInstant(changes.get("followUpAt")) : lead.getFollowUpAt();
        LeadContract.validateFollowUp(followUp, zone);
        lead.setFollowUpAt(followUp);
        lead.setFollowUpZoneId(zone);
        if (changes.containsKey("preferredLocalityIds")) {
            List<Locality> rows = resolveLocalities(lead.getSupportedCity(),
                    LeadContract.exactPositiveIds(asList(changes.get("preferredLocalityIds")),
                            "preferredLocalityIds"));
            preferredLocalities.deleteAllByLead_Id(lead.getId());
            savePreferredLocalities(lead, rows);
        }
    }

    private LeadRoutingQueue lockLead(Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        return leads.findLockedById(leadId).orElseThrow(LeadService::hidden);
    }

    private LeadAccessContext lockAndFreshContext(Long actorId, LeadRoutingQueue lead) {
        return lockAndFreshContext(actorId, lead, List.of());
    }

    private LeadAccessContext lockAndFreshContext(Long actorId, LeadRoutingQueue lead, List<Long> targetUserIds) {
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        List<Long> userIds = new ArrayList<>();
        userIds.add(actorId);
        userIds.addAll(targetUserIds);
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), userIds);
        return leadAccess.context(actorId);
    }

    private static void requireVerified(LeadRoutingQueue lead) {
        if (lead.getLegacyProvenance() == LeadLegacyProvenance.LEGACY_UNVERIFIED) {
            throw new VisitOperationsConflictException(
                    "Legacy Lead facts require an explicitly audited reconciliation first");
        }
    }

    private static void requireActiveLifecycle(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status == LeadStatus.CLOSED || status == LeadStatus.ASSIGNED
                || status == LeadStatus.UNMAPPED || status == LeadStatus.CONTACTED
                || status == LeadStatus.STALE) {
            throw new VisitOperationsConflictException(
                    "This Lead must be reopened before its working state can change");
        }
    }

    private SupportedCity resolveCity(Long cityId) {
        if (cityId == null) return null;
        return cities.findByIdAndActiveTrue(cityId)
                .orElseThrow(() -> new IllegalArgumentException("Canonical City is unavailable"));
    }

    private OperatingTeam resolveTeam(Long teamId) {
        if (teamId == null) return null;
        return teams.findByIdAndActiveTrue(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Operating Team is unavailable"));
    }

    private List<Locality> resolveLocalities(SupportedCity city, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 20) throw new IllegalArgumentException("At most 20 preferred localities are allowed");
        List<Long> distinct = new LinkedHashSet<>(ids).stream().toList();
        List<Locality> rows = localities.findAllById(distinct);
        if (rows.size() != distinct.size()) throw new IllegalArgumentException("A preferred locality does not exist");
        if (city == null) throw new IllegalArgumentException("A canonical City is required before localities");
        for (Locality row : rows) {
            if (row.getSupportedCity() == null || !city.getId().equals(row.getSupportedCity().getId())) {
                throw new IllegalArgumentException("Every preferred locality must belong to the Lead City");
            }
        }
        return rows;
    }

    private void savePreferredLocalities(LeadRoutingQueue lead, List<Locality> rows) {
        preferredLocalities.saveAllAndFlush(rows.stream()
                .map(locality -> new LeadPreferredLocality(lead, locality)).toList());
    }

    private void lockOperation(Long actorId, UUID operationId, String action) {
        String lockKey = actorId + ":" + action + ":" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, lockKey), resultSet -> null);
    }

    private String payloadHash(Object command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(command)));
        } catch (Exception exception) {
            throw new IllegalStateException("Lead command could not be fingerprinted");
        }
    }

    private String payloadHash(Long leadId, Object command) {
        return payloadHash(Map.of("leadId", leadId, "command", command));
    }

    private static UUID parseOperationId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID");
        }
    }

    private static Long parseLeadReference(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored Lead receipt is invalid");
        }
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (lead.getVersion() != null && !lead.getVersion().equals(expected)) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress. Refresh and compare before retrying.");
        }
    }

    private static String mask(String phone) {
        if (phone == null) return null;
        String digits = NON_DIGIT.matcher(phone).replaceAll("");
        if (digits.length() <= 4) return "****";
        return "**********" + digits.substring(digits.length() - 4);
    }

    private static void requirePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be zero or greater");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
    }

    private static List<Long> idsOrImpossible(List<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of(-1L) : ids;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal asDecimal(Object value, String field) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
    }

    private static LocalDate asDate(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.toString());
        } catch (Exception exception) {
            throw new IllegalArgumentException("targetMoveInDate must use ISO-8601 date format");
        }
    }

    private static Instant asInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (Exception exception) {
            throw new IllegalArgumentException("followUpAt must use an ISO-8601 instant");
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(value.toString())) return true;
        if ("false".equalsIgnoreCase(value.toString())) return false;
        throw new IllegalArgumentException("Boolean Lead fields must be true or false");
    }

    private static List<?> asList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("preferredLocalityIds must be an array");
        }
        return list;
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException(HIDDEN);
    }
}
