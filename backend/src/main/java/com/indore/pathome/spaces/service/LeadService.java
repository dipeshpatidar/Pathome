package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadActivityPage;
import com.indore.pathome.spaces.dto.crm.LeadActivityView;
import com.indore.pathome.spaces.dto.crm.LeadAssignmentCommand;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.dto.crm.LeadCommandReceiptView;
import com.indore.pathome.spaces.dto.crm.LeadContactView;
import com.indore.pathome.spaces.dto.crm.LeadDetailView;
import com.indore.pathome.spaces.dto.crm.LeadLifecycleCommand;
import com.indore.pathome.spaces.dto.crm.LeadPage;
import com.indore.pathome.spaces.dto.crm.LeadPatchCommand;
import com.indore.pathome.spaces.dto.crm.LeadRoutingCommand;
import com.indore.pathome.spaces.dto.crm.LeadSummaryView;
import com.indore.pathome.spaces.dto.crm.LeadTeamTransferCommand;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
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
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadPreferredLocalityRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
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

@Service
public class LeadService {
    private static final String HIDDEN = "Lead not found";
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
    private final LocalityRepository localities;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final StaffAccessService staffAccess;
    private final LeadAccessService leadAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LeadService(LeadRoutingQueueRepository leads,
                       LeadPreferredLocalityRepository preferredLocalities,
                       LeadSourceEventRepository sourceEvents,
                       LeadActivityRepository activities,
                       LeadCommandReceiptRepository receipts,
                       LocalityRepository localities,
                       SupportedCityRepository cities,
                       OperatingTeamRepository teams,
                       StaffAccessService staffAccess,
                       LeadAccessService leadAccess,
                       OperationalSecurityGuards guards,
                       OperationalAuditService audit,
                       DatabaseClock databaseClock,
                       JdbcTemplate jdbc,
                       ObjectMapper objectMapper) {
        this.leads = leads;
        this.preferredLocalities = preferredLocalities;
        this.sourceEvents = sourceEvents;
        this.activities = activities;
        this.receipts = receipts;
        this.localities = localities;
        this.cities = cities;
        this.teams = teams;
        this.staffAccess = staffAccess;
        this.leadAccess = leadAccess;
        this.guards = guards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public LeadPage list(Long actorId, Long cityId, Long teamId, LeadStatus status,
                         boolean ownerMe, Instant followUpBefore, int page, int size) {
        requirePage(page, size);
        LeadAccessContext context = leadAccess.context(actorId);
        PageRequest request = PageRequest.of(page, size);
        Page<LeadRoutingQueue> result = leads.findVisibleLeadPage(actorId, context.globalAdmin(),
                idsOrImpossible(context.visibleUnassignedCityIds()), idsOrImpossible(context.ownedTeamIds()),
                idsOrImpossible(context.supervisedTeamIds()), cityId, teamId, status, ownerMe,
                followUpBefore, request);
        List<Long> leadIds = result.getContent().stream().map(LeadRoutingQueue::getId).toList();
        Map<Long, Instant> lastActivity = leadIds.isEmpty() ? Map.of()
                : activities.findLatestActivityByLeadIds(leadIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Instant) row[1]));
        List<LeadSummaryView> items = result.getContent().stream()
                .map(lead -> summary(lead, lastActivity.get(lead.getId()))).toList();
        return new LeadPage(items, result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LeadDetailView detail(Long actorId, Long leadId) {
        LeadAccessContext context = leadAccess.context(actorId);
        LeadRoutingQueue lead = leads.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException(HIDDEN));
        leadAccess.requireReadable(context, lead);
        return detail(lead);
    }

    @Transactional
    public LeadDetailView capture(Long actorId, String operationKey, LeadCaptureCommand command) {
        if (command == null) throw new IllegalArgumentException("Lead capture body is required");
        UUID operationId = parseOperationId(operationKey);
        String payloadHash = payloadHash(command);
        lockOperation(actorId, operationId);

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
            leadAccess.requireReadable(leadAccess.context(actorId), replay);
            return detail(replay);
        }

        User actor = staffAccess.requireAttributableUser(actorId);
        LeadAccessContext context = leadAccess.context(actorId);
        SupportedCity city = resolveCity(command.cityId());
        OperatingTeam team = resolveTeam(command.teamId());
        if (team != null) {
            if (city == null) city = team.getCity();
            if (city == null || !Objects.equals(team.getCity().getId(), city.getId())) {
                throw new IllegalArgumentException("Lead Team must belong to the selected canonical City");
            }
        }
        if (city == null) {
            if (!context.globalAdmin()) {
                throw new IllegalArgumentException("A canonical City is required for this Lead capture");
            }
        } else if (team != null) {
            if (!context.globalAdmin() && !context.ownedTeamIds().contains(team.getId())) {
                throw new AccessDeniedException("Current OPS_COORDINATE authority for this Team is required");
            }
        } else if (!context.globalAdmin() && !context.intakeCityIds().contains(city.getId())) {
            throw new AccessDeniedException("Current OPS_INTAKE authority for this City is required");
        }

        LeadContract.validateBudget(command.budgetMin(), command.budgetMax());
        String currency = command.currency() == null ? "INR" : command.currency().trim().toUpperCase(Locale.ROOT);
        LeadContract.validateCurrency(currency);
        LeadContract.validateFollowUp(command.followUpAt(), command.followUpZoneId());
        List<Locality> localityRows = resolveLocalities(city, command.preferredLocalityIds());

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

        Instant now = databaseClock.now();
        LeadSourceEvent sourceEvent = new LeadSourceEvent(UUID.randomUUID(), "MANUAL",
                String.valueOf(actor.getId()), operationId.toString(), LeadSourceEventKind.MANUAL_PHONE,
                payloadHash, now, LeadSourceEventState.PROCESSED, null);
        sourceEvent.setSourceAttribution(
                LeadContract.text(command.sourceChannel(), 64, "Source channel", false),
                LeadContract.text(command.campaignRef(), 200, "Campaign reference", false),
                LeadContract.text(command.referralRef(), 200, "Referral reference", false));
        sourceEvent.attachTo(lead);
        sourceEvent = sourceEvents.saveAndFlush(sourceEvent);
        savePreferredLocalities(lead, localityRows);
        activities.save(new LeadActivity(lead, actor, "LEAD_CAPTURED", sourceEvent, now, null));
        audit.recordUserEvent(actor, "LEAD_CAPTURED", "LEAD", lead.getId(), "MANUAL_INTAKE",
                city, team, Map.of());
        receipts.saveAndFlush(new LeadCommandReceipt(actor, operationId, "LEAD_CAPTURE",
                payloadHash, lead.getId().toString(), now));
        return detail(lead);
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
        requireVerified(lead);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
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
        requireVerified(lead);
        LeadAccessContext context = lockAndFreshContext(actorId, lead);
        leadAccess.requireEditable(context, lead, actorId);
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
    public LeadDetailView route(Long actorId, Long leadId, LeadRoutingCommand command) {
        if (command == null || command.expectedVersion() == null || command.cityId() == null
                || command.teamId() == null || command.expectedCityVersion() == null
                || command.expectedTeamVersion() == null) {
            throw new IllegalArgumentException("Lead routing requires current City/Team versions");
        }
        LeadContract.requireReasonCode(command.reasonCode());
        LeadRoutingQueue lead = lockLead(leadId);
        requireVerified(lead);
        requireActiveLifecycle(lead);
        guards.acquire(List.of(command.cityId()), List.of(command.teamId()), List.of(actorId));
        LeadAccessContext context = leadAccess.context(actorId);
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
        requireVerified(lead);
        requireActiveLifecycle(lead);
        if (lead.getOperatingTeam() == null) {
            throw new VisitOperationsConflictException("A Lead must be routed before it can be assigned");
        }
        LeadAccessContext context = lockAndFreshContext(actorId, lead,
                List.of(command.targetCoordinatorUserId()));
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
        requireVerified(lead);
        requireActiveLifecycle(lead);
        if (lead.getOperatingTeam() == null) {
            throw new VisitOperationsConflictException("An unrouted Lead must be routed before transfer");
        }
        Long sourceTeamId = lead.getOperatingTeam().getId();
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                sourceTeamId.equals(command.destinationTeamId())
                        ? List.of(sourceTeamId) : List.of(sourceTeamId, command.destinationTeamId()),
                command.targetCoordinatorUserId() == null
                        ? List.of(actorId) : List.of(actorId, command.targetCoordinatorUserId()));
        LeadAccessContext context = leadAccess.context(actorId);
        requireVersion(lead, command.expectedVersion());
        if (!context.globalAdmin()
                && (!context.supervisedTeamIds().contains(sourceTeamId)
                    || !context.supervisedTeamIds().contains(command.destinationTeamId()))) {
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
        LeadRoutingQueue lead = leads.findById(leadId).orElseThrow(() -> new EntityNotFoundException(HIDDEN));
        leadAccess.requireReadable(context, lead);
        Page<LeadActivity> result = activities.findAllByLead_IdOrderByOccurredAtDescIdDesc(
                leadId, PageRequest.of(page, size));
        return new LeadActivityPage(result.getContent().stream()
                .map(item -> new LeadActivityView(item.getId(), item.getActivityType(),
                        item.getOccurredAt(), item.getNote())).toList(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LeadCommandReceiptView receipt(Long actorId, UUID operationId) {
        leadAccess.context(actorId);
        LeadCommandReceipt receipt = receipts.findByActorIdAndOperationIdAndAction(
                        actorId, operationId, "LEAD_CAPTURE")
                .orElse(null);
        if (receipt == null) return new LeadCommandReceiptView("NOT_RECORDED", null, null);
        LeadRoutingQueue lead = leads.findById(parseLeadReference(receipt.getResultReference()))
                .orElseThrow(LeadService::hidden);
        leadAccess.requireReadable(leadAccess.context(actorId), lead);
        return new LeadCommandReceiptView("RECORDED",
                parseLeadReference(receipt.getResultReference()), receipt.getCompletedAt());
    }

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
        List<Long> localityIds = preferredLocalities.findAllByLead_IdOrderByLocality_IdAsc(lead.getId())
                .stream().map(item -> item.getLocality().getId()).toList();
        Instant lastActivityAt = activities.findFirstByLead_IdOrderByOccurredAtDescIdDesc(lead.getId())
                .map(LeadActivity::getOccurredAt).orElse(null);
        return new LeadDetailView(summary(lead, lastActivityAt), lead.getCustomerSaid(), lead.getPropertyType(), lead.getBhk(),
                lead.getBudgetMin(), lead.getBudgetMax(), lead.getCurrency(), lead.getTargetMoveInDate(),
                lead.getFurnishing(), lead.getTenantProfile(), lead.getParkingRequired(),
                lead.getPetsAllowed(), lead.getLiftRequired(), lead.getFloorRequirement(), localityIds,
                lead.getLinkedUser() != null, lead.getLegacyProvenance());
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
                    asLongList(changes.get("preferredLocalityIds")));
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

    private void lockOperation(Long actorId, UUID operationId) {
        String lockKey = actorId + ":LEAD_CAPTURE:" + operationId;
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
        String digits = phone.replaceAll("\\D", "");
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

    @SuppressWarnings("unchecked")
    private static List<Long> asLongList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("preferredLocalityIds must be an array");
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            } else {
                try {
                    result.add(Long.parseLong(item.toString()));
                } catch (Exception exception) {
                    throw new IllegalArgumentException("Preferred locality IDs must be positive integers");
                }
            }
        }
        return result;
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException(HIDDEN);
    }
}
