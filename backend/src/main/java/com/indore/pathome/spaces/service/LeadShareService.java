package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadShareEventCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareEventView;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryEventView;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryPage;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryView;
import com.indore.pathome.spaces.dto.crm.LeadSharePrepareCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadShare;
import com.indore.pathome.spaces.entity.LeadShareEvent;
import com.indore.pathome.spaces.entity.LeadShareEventType;
import com.indore.pathome.spaces.entity.LeadShareMode;
import com.indore.pathome.spaces.entity.LeadShortlist;
import com.indore.pathome.spaces.entity.LeadShortlistItem;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShareEventRepository;
import com.indore.pathome.spaces.repository.LeadShareRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeadShareService {
    private final LeadRoutingQueueRepository leads;
    private final LeadShortlistRepository shortlists;
    private final LeadShortlistItemRepository shortlistItems;
    private final LeadContactPreferenceRepository preferences;
    private final LeadShareRepository shares;
    private final LeadShareEventRepository shareEvents;
    private final LeadService readableLeads;
    private final LeadActivityRepository activities;
    private final LeadCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final LeadPublicPropertyService publicProperties;
    private final LeadDataProtection protection;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String configuredOrigin;

    public LeadShareService(LeadRoutingQueueRepository leads,
                            LeadShortlistRepository shortlists,
                            LeadShortlistItemRepository shortlistItems,
                            LeadContactPreferenceRepository preferences,
                            LeadShareRepository shares,
                            LeadShareEventRepository shareEvents,
                            LeadService readableLeads,
                            LeadActivityRepository activities,
                            LeadCommandReceiptRepository receipts,
                            LeadAccessService leadAccess,
                            StaffAccessService staffAccess,
                            OperationalSecurityGuards guards,
                            OperationalAuditService audit,
                            DatabaseClock databaseClock,
                            LeadPublicPropertyService publicProperties,
                            LeadDataProtection protection,
                            JdbcTemplate jdbc,
                            ObjectMapper objectMapper,
                            @Value("${pathome.crm.public-origin:}") String configuredOrigin) {
        this.leads = leads;
        this.shortlists = shortlists;
        this.shortlistItems = shortlistItems;
        this.preferences = preferences;
        this.shares = shares;
        this.shareEvents = shareEvents;
        this.readableLeads = readableLeads;
        this.activities = activities;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.publicProperties = publicProperties;
        this.protection = protection;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configuredOrigin = configuredOrigin;
    }

    @Transactional
    public LeadShareView prepare(Long actorId, Long leadId, LeadSharePrepareCommand command) {
        validate(command);
        String payloadHash = payloadHash(leadId, command);
        lockOperation(actorId, command.operationId());
        LeadCommandReceipt receipt = receipts.findByActorIdAndOperationIdAndAction(
                actorId, command.operationId(), "LEAD_SHARE_PREPARE").orElse(null);
        if (receipt != null) {
            if (!receipt.getPayloadHash().equals(payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different share request");
            }
            LeadShare existing = shares.findById(UUID.fromString(receipt.getResultReference()))
                    .orElseThrow(() -> new EntityNotFoundException("Prepared share not found"));
            LeadRoutingQueue lead = leads.findLockedById(leadId)
                    .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
            authorize(actorId, lead);
            revalidateReplay(lead, existing);
            return view(existing);
        }

        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        LeadShortlist shortlist = shortlists.findLockedById(command.shortlistId())
                .orElseThrow(() -> new EntityNotFoundException("Shortlist not found"));
        if (!shortlist.getLead().getId().equals(leadId)) {
            throw new EntityNotFoundException("Shortlist not found");
        }
        authorize(actorId, lead);
        requireVersion(lead, command.expectedLeadVersion());
        requireActiveLifecycle(lead);
        if (!command.expectedShortlistVersion().equals(shortlist.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The shortlist changed while the share was being prepared");
        }

        LeadContactPreference preference = currentGrantedPreference(
                leadId, command.contactPreferenceRevision());
        if (command.mode() == LeadShareMode.MANUAL_BUSINESS_HANDOFF
                && preference.getDestinationChoice() == LeadDestinationChoice.NONE) {
            throw new VisitOperationsConflictException(
                    "A business-channel handoff requires an agreed current destination");
        }

        List<Long> listingIds = shortlistItems
                .findAllByShortlist_IdOrderByPositionAsc(shortlist.getId()).stream()
                .map(LeadShortlistItem::getListingId).toList();
        List<LeadPublicPropertyService.EligibleProperty> eligible =
                publicProperties.requireCurrentPublicProperties(lead, listingIds);
        if (eligible.isEmpty()) {
            throw new VisitOperationsConflictException(
                    "At least one current public property is required before sharing");
        }
        String origin = publicOrigin();
        List<String> publicLinks = eligible.stream()
                .map(property -> origin + "/property/" + property.listingId()).toList();
        String preview = preview(eligible, publicLinks);
        String maskedDestination = mask(preference);

        Instant now = databaseClock.now();
        User actor = staffAccess.requireAttributableUser(actorId);
        UUID shareId = UUID.randomUUID();
        LeadShare share;
        try {
            share = shares.saveAndFlush(new LeadShare(shareId, lead, shortlist,
                    shortlist.getVersion(), preference.getRevision(), command.mode(),
                    objectMapper.writeValueAsString(publicLinks), preview, maskedDestination,
                    now, actor));
        } catch (Exception exception) {
            throw new IllegalStateException("Prepared share could not be stored");
        }
        activities.save(new LeadActivity(lead, actor, "SHARE_PREPARED",
                null, now, null));
        audit.recordUserEvent(actor, "LEAD_SHARE_PREPARED", "LEAD", leadId,
                "MANUAL_PROPERTY_SHARE", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        receipts.saveAndFlush(new LeadCommandReceipt(actor, command.operationId(),
                "LEAD_SHARE_PREPARE", payloadHash, shareId.toString(), now));
        return view(share);
    }

    @Transactional
    public LeadShareEventView recordEvent(Long actorId, Long leadId, UUID shareId,
                                          LeadShareEventCommand command) {
        if (command == null || command.eventId() == null || command.type() == null) {
            throw new IllegalArgumentException("eventId and share event type are required");
        }
        LeadRoutingQueue lead = leads.findLockedById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        authorize(actorId, lead);
        LeadShare share = shares.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("Prepared share not found"));
        if (!share.getLead().getId().equals(leadId)) {
            throw new EntityNotFoundException("Prepared share not found");
        }
        LeadShareEvent existing = shareEvents.findById(command.eventId()).orElse(null);
        if (existing != null) {
            if (!existing.getShare().getId().equals(shareId)
                    || existing.getEventType() != command.type()
                    || !existing.getActorId().equals(actorId)) {
                throw new VisitOperationsConflictException(
                        "This event ID was already used for a different share fact");
            }
            return view(existing);
        }
        requireFinalActionReadiness(lead, share, command.type());
        // Definitive admission follows every readiness lock. The effective-grant query uses
        // clock_timestamp(), so expiry observed after a lock wait is evaluated against database time.
        authorizeCurrentRelationship(actorId, lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        LeadShareEvent event = shareEvents.saveAndFlush(
                new LeadShareEvent(command.eventId(), share, actor, command.type(), now));
        activities.save(new LeadActivity(lead, actor, "SHARE_ACTIVITY", null, now,
                command.type().name()));
        return view(event);
    }

    /**
     * New manual actions are authorized against one current transaction snapshot. Event replay above
     * remains read-only so a lost acknowledgement can be reconciled without performing the action twice.
     */
    private void requireFinalActionReadiness(LeadRoutingQueue lead, LeadShare share,
                                             LeadShareEventType type) {
        if (type != LeadShareEventType.HANDOFF_ATTEMPTED
                && type != LeadShareEventType.CLIPBOARD_COPIED) {
            return;
        }
        requireActiveLifecycle(lead,
                "The current Lead lifecycle no longer permits manual sharing");
        if (type == LeadShareEventType.HANDOFF_ATTEMPTED
                && share.getMode() != LeadShareMode.MANUAL_BUSINESS_HANDOFF) {
            throw new VisitOperationsConflictException(
                    "This prepared share does not permit a WhatsApp handoff");
        }
        LeadShortlist currentShortlist = shortlists.findLockedById(share.getShortlist().getId())
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "The prepared shortlist is no longer available"));
        if (!share.getShortlistVersion().equals(currentShortlist.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The shortlist changed after this share was prepared; prepare a refreshed share");
        }
        LeadContactPreference preference = currentGrantedPreference(
                lead.getId(), share.getContactPreferenceRevision());
        if (share.getMode() == LeadShareMode.MANUAL_BUSINESS_HANDOFF
                && preference.getDestinationChoice() == LeadDestinationChoice.NONE) {
            throw new VisitOperationsConflictException(
                    "The current destination no longer permits a WhatsApp handoff");
        }
        if (!currentDestinationMatches(share, preference)) {
            throw new VisitOperationsConflictException(
                    "The confirmed destination changed after this share was prepared");
        }
        List<Long> listingIds = shortlistItems
                .findAllByShortlist_IdOrderByPositionAsc(currentShortlist.getId()).stream()
                .map(LeadShortlistItem::getListingId).toList();
        List<LeadPublicPropertyService.EligibleProperty> eligible =
                publicProperties.requireCurrentPublicPropertiesForUpdate(lead, listingIds);
        if (eligible.isEmpty()) {
            throw new VisitOperationsConflictException(
                    "The prepared shortlist no longer contains a current public property");
        }
        String origin = publicOrigin();
        List<String> currentLinks = eligible.stream()
                .map(property -> origin + "/property/" + property.listingId()).toList();
        String currentPreview = preview(eligible, currentLinks);
        if (!publicLinks(share).equals(currentLinks)
                || !share.getPreviewText().equals(currentPreview)) {
            throw new VisitOperationsConflictException(
                    "Public property facts changed after this share was prepared; prepare a refreshed share");
        }
    }

    @Transactional(readOnly = true)
    public LeadShareHistoryPage history(Long actorId, Long leadId, int page, int size) {
        if (page < 0 || size < 1 || size > 20) {
            throw new IllegalArgumentException("page must be nonnegative and size must be 1..20");
        }
        LeadRoutingQueue lead = readableLeads.requireReadableLead(actorId, leadId);
        LeadAccessContext access = leadAccess.context(actorId);
        boolean authorityMatches = access.canEdit(lead, actorId) && activeLifecycle(lead);
        Page<LeadShare> sharesPage = shares.findAllByLead_IdOrderByRecordedAtDescIdDesc(
                leadId, PageRequest.of(page, size));
        List<UUID> shareIds = sharesPage.getContent().stream().map(LeadShare::getId).toList();
        Map<UUID, List<LeadShareEvent>> eventsByShare = shareIds.isEmpty()
                ? Map.of()
                : shareEvents.findAllByShare_IdInOrderByOccurredAtAscIdAsc(shareIds).stream()
                        .collect(Collectors.groupingBy(event -> event.getShare().getId()));
        List<Long> shortlistIds = sharesPage.getContent().stream()
                .map(share -> share.getShortlist().getId()).distinct().toList();
        Map<Long, LeadShortlist> currentShortlists = shortlists.findAllById(shortlistIds).stream()
                .collect(Collectors.toMap(LeadShortlist::getId, shortlist -> shortlist));
        LeadContactPreference latestPreference =
                preferences.findFirstByLead_IdOrderByRevisionDesc(leadId).orElse(null);
        List<LeadShortlistItem> allShortlistItems = currentShortlists.isEmpty() ? List.of()
                : shortlistItems.findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(
                        currentShortlists.keySet().stream().sorted().toList());
        Map<Long, List<Long>> listingIdsByShortlist = allShortlistItems.stream()
                .collect(Collectors.groupingBy(item -> item.getShortlist().getId(),
                        Collectors.mapping(LeadShortlistItem::getListingId, Collectors.toList())));
        List<Long> allListingIds = listingIdsByShortlist.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<Long, LeadPublicPropertyService.EligibleProperty> currentProperties =
                publicProperties.currentPublicPropertiesById(lead, allListingIds, false);
        List<LeadShareHistoryView> items = sharesPage.getContent().stream()
                .map(share -> historyView(lead, share,
                        eventsByShare.getOrDefault(share.getId(), List.of()),
                        currentShortlists.get(share.getShortlist().getId()), latestPreference,
                        listingIdsByShortlist, currentProperties, authorityMatches))
                .toList();
        return new LeadShareHistoryPage(items, sharesPage.getTotalElements(), page, size,
                sharesPage.getTotalPages());
    }

    private void authorize(Long actorId, LeadRoutingQueue lead) {
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(actorId));
        authorizeCurrentRelationship(actorId, lead);
    }

    private void authorizeCurrentRelationship(Long actorId, LeadRoutingQueue lead) {
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireEditable(context, lead, actorId);
    }

    private String mask(LeadContactPreference preference) {
        if (preference.getDestinationCiphertext() == null) return null;
        return LeadDataProtection.mask(protection.reveal(preference.getDestinationCiphertext(),
                preference.getDestinationIv(), preference.getDestinationKeyVersion()));
    }

    private LeadContactPreference currentGrantedPreference(Long leadId, Long revision) {
        LeadContactPreference latest = preferences.findFirstByLead_IdOrderByRevisionDesc(leadId)
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "Current PROPERTY_SHARING customer agreement is required"));
        if (!latest.getRevision().equals(revision)) {
            throw new VisitOperationsConflictException(
                    "The selected contact preference is no longer current");
        }
        if (latest.getConsentStatus() != LeadConsentStatus.GRANTED) {
            throw new VisitOperationsConflictException(
                    "Current PROPERTY_SHARING customer agreement is required");
        }
        return latest;
    }

    private void revalidateReplay(LeadRoutingQueue lead, LeadShare existing) {
        requireActiveLifecycle(lead);
        LeadShortlist currentShortlist = shortlists.findById(existing.getShortlist().getId())
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "The prepared shortlist is no longer available"));
        if (!existing.getShortlistVersion().equals(currentShortlist.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The shortlist changed after this share was prepared; prepare a refreshed share");
        }
        LeadContactPreference currentPreference = currentGrantedPreference(
                lead.getId(), existing.getContactPreferenceRevision());
        if (currentPreference.getDestinationChoice() == LeadDestinationChoice.NONE
                && existing.getMode() != LeadShareMode.COPY_LINKS) {
            throw new VisitOperationsConflictException(
                    "The current destination no longer supports this prepared share");
        }
        if (currentPreference.getDestinationCiphertext() != null
                && !LeadDataProtection.mask(protection.reveal(
                        currentPreference.getDestinationCiphertext(),
                        currentPreference.getDestinationIv(),
                        currentPreference.getDestinationKeyVersion()))
                        .equals(existing.getMaskedDestination())) {
            throw new VisitOperationsConflictException(
                    "The confirmed destination changed after this share was prepared");
        }
        List<Long> listingIds = shortlistItems
                .findAllByShortlist_IdOrderByPositionAsc(currentShortlist.getId()).stream()
                .map(LeadShortlistItem::getListingId).toList();
        List<LeadPublicPropertyService.EligibleProperty> eligible =
                publicProperties.requireCurrentPublicProperties(lead, listingIds);
        if (eligible.isEmpty()) {
            throw new VisitOperationsConflictException(
                    "The prepared shortlist no longer contains a current public property");
        }
        String origin = publicOrigin();
        List<String> currentLinks = eligible.stream()
                .map(property -> origin + "/property/" + property.listingId()).toList();
        String currentPreview = preview(eligible, currentLinks);
        if (!existing.getPreviewText().equals(currentPreview)) {
            throw new VisitOperationsConflictException(
                    "Public property facts changed after this share was prepared; prepare a refreshed share");
        }
    }

    private String publicOrigin() {
        if (configuredOrigin == null || configuredOrigin.isBlank()) {
            throw new LeadDataProtectionUnavailableException(
                    "A canonical public property origin is not configured");
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
            throw new LeadDataProtectionUnavailableException(
                    "The configured public property origin is invalid");
        }
    }

    private static String preview(List<LeadPublicPropertyService.EligibleProperty> properties,
                                  List<String> links) {
        StringBuilder text = new StringBuilder("Pathome property options\n");
        for (int index = 0; index < properties.size(); index++) {
            LeadPublicPropertyService.EligibleProperty property = properties.get(index);
            text.append(index + 1).append(". ").append(property.title());
            if (property.location() != null) text.append(" - ").append(property.location());
            if (property.bhk() != null) text.append(" - ").append(property.bhk());
            if (property.monthlyRent() != null) {
                text.append(" - INR ").append(property.monthlyRent().toPlainString()).append(" / month");
            }
            text.append('\n').append(links.get(index)).append('\n');
        }
        return text.toString().trim();
    }

    private LeadShareView view(LeadShare share) {
        try {
            List<String> links = objectMapper.readValue(share.getPublicLinksJson(),
                    new TypeReference<List<String>>() {});
            return new LeadShareView(share.getId(), share.getLead().getId(),
                    share.getShortlist().getId(), share.getShortlistVersion(),
                    share.getContactPreferenceRevision(), share.getMode(), links,
                    share.getPreviewText(), share.getMaskedDestination(), share.getRecordedAt());
        } catch (Exception exception) {
            throw new IllegalStateException("Stored prepared share is invalid");
        }
    }

    private LeadShareHistoryView historyView(LeadRoutingQueue lead, LeadShare share,
                                             List<LeadShareEvent> events,
                                             LeadShortlist currentShortlist,
                                             LeadContactPreference latestPreference,
                                             Map<Long, List<Long>> listingIdsByShortlist,
                                             Map<Long, LeadPublicPropertyService.EligibleProperty>
                                                     currentProperties,
                                             boolean currentAuthorityMatches) {
        List<String> links = publicLinks(share);
        boolean factsMatch = currentFactsMatch(lead, share, links, currentShortlist,
                listingIdsByShortlist, currentProperties);
        boolean consentMatches = currentConsentMatches(share, latestPreference);
        boolean destinationMatches = currentDestinationMatches(share, latestPreference);
        return new LeadShareHistoryView(share.getId(), share.getShortlist().getId(),
                share.getShortlistVersion(), share.getContactPreferenceRevision(), share.getMode(),
                links, share.getPreviewText(), share.getMaskedDestination(), share.getRecordedAt(),
                events.stream().map(event -> new LeadShareHistoryEventView(event.getId(),
                        event.getEventType(), event.getOccurredAt())).toList(),
                factsMatch, consentMatches, destinationMatches, currentAuthorityMatches,
                currentAuthorityMatches && factsMatch && consentMatches && destinationMatches);
    }

    private List<String> publicLinks(LeadShare share) {
        try {
            return objectMapper.readValue(share.getPublicLinksJson(),
                    new TypeReference<List<String>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Stored prepared share is invalid");
        }
    }

    private boolean currentConsentMatches(LeadShare share, LeadContactPreference latestPreference) {
        return latestPreference != null
                && latestPreference.getRevision().equals(share.getContactPreferenceRevision())
                && latestPreference.getConsentStatus() == LeadConsentStatus.GRANTED;
    }

    private boolean currentDestinationMatches(LeadShare share,
                                              LeadContactPreference latestPreference) {
        if (!currentConsentMatches(share, latestPreference)) return false;
        if (latestPreference.getDestinationChoice() == LeadDestinationChoice.NONE) {
            return share.getMode() == LeadShareMode.COPY_LINKS;
        }
        if (latestPreference.getDestinationCiphertext() == null) return false;
        try {
            String destination = protection.reveal(latestPreference.getDestinationCiphertext(),
                    latestPreference.getDestinationIv(), latestPreference.getDestinationKeyVersion());
            return LeadDataProtection.mask(destination).equals(share.getMaskedDestination());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean currentFactsMatch(LeadRoutingQueue lead, LeadShare share,
                                      List<String> storedLinks,
                                      LeadShortlist currentShortlist,
                                      Map<Long, List<Long>> listingIdsByShortlist,
                                      Map<Long, LeadPublicPropertyService.EligibleProperty>
                                              currentProperties) {
        if (currentShortlist == null
                || !share.getShortlistVersion().equals(currentShortlist.getVersion())) {
            return false;
        }
        List<Long> listingIds = listingIdsByShortlist.getOrDefault(currentShortlist.getId(),
                List.of());
        if (listingIds.isEmpty()) return false;
        List<LeadPublicPropertyService.EligibleProperty> eligible = listingIds.stream()
                .map(currentProperties::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (eligible.size() != listingIds.size()) return false;
        try {
            String origin = publicOrigin();
            List<String> currentLinks = eligible.stream()
                    .map(property -> origin + "/property/" + property.listingId()).toList();
            return storedLinks.equals(currentLinks)
                    && share.getPreviewText().equals(preview(eligible, currentLinks));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static LeadShareEventView view(LeadShareEvent event) {
        return new LeadShareEventView(event.getId(), event.getShare().getId(),
                event.getEventType(), event.getOccurredAt());
    }

    private static void validate(LeadSharePrepareCommand command) {
        if (command == null || command.operationId() == null || command.expectedLeadVersion() == null
                || command.shortlistId() == null || command.expectedShortlistVersion() == null
                || command.contactPreferenceRevision() == null || command.mode() == null) {
            throw new IllegalArgumentException(
                    "Operation ID, Lead/shortlist versions, preference revision and mode are required");
        }
    }

    private static void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (lead.getVersion() != null && !lead.getVersion().equals(expected)) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress. Refresh and compare before retrying.");
        }
    }

    private static void requireActiveLifecycle(LeadRoutingQueue lead) {
        requireActiveLifecycle(lead, "A manual share cannot be prepared for this Lead state");
    }

    private static void requireActiveLifecycle(LeadRoutingQueue lead, String message) {
        if (!activeLifecycle(lead)) {
            throw new VisitOperationsConflictException(message);
        }
    }

    private static boolean activeLifecycle(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        return status != LeadStatus.CLOSED && status != LeadStatus.ASSIGNED
                && status != LeadStatus.UNMAPPED && status != LeadStatus.CONTACTED
                && status != LeadStatus.STALE;
    }

    private void lockOperation(Long actorId, UUID operationId) {
        String key = actorId + ":LEAD_SHARE_PREPARE:" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, key), resultSet -> null);
    }

    private String payloadHash(Object command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(command)));
        } catch (Exception exception) {
            throw new IllegalStateException("Share command could not be fingerprinted");
        }
    }

    private String payloadHash(Long leadId, Object command) {
        return payloadHash(Map.of("leadId", leadId, "command", command));
    }
}
