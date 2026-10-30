package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import com.indore.pathome.spaces.dto.backoffice.StaffPropertySnapshot;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Service
public class BackofficePropertyService {
    private static final int PAGE_SIZE = 20;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Pattern BHK = Pattern.compile("^(?:1RK|[1-9][0-9]?BHK)$");
    private static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Set<String> FURNISHING = Set.of(
            "UNFURNISHED", "SEMI_FURNISHED", "FULLY_FURNISHED");
    private static final Set<String> OPEN_REVISION_STATUSES =
            Set.of("DRAFT", "REVIEW", "CHANGES_REQUIRED");

    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository draftMedia;
    private final PropertyMediaAssetRepository assets;
    private final PropertyPublicationVersionRepository publications;
    private final PropertyRevisionEventRepository revisionEvents;
    private final PropertyCommandReceiptRepository receipts;
    private final PropertyPublicationOutboxRepository outbox;
    private final UserRepository users;
    private final BackofficePropertyAccessService access;
    private final OperationalAuditService audit;
    private final DatabaseClock clock;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final PropertyPublicationSnapshotAdapter publicationSnapshots;
    private final PropertyActivityCursorCodec activityCursors;

    public BackofficePropertyService(ListingRepository listings, LocalityRepository localities,
                                     PropertyUploadDraftRepository drafts,
                                     PropertyDraftMediaRepository draftMedia,
                                     PropertyMediaAssetRepository assets,
                                     PropertyPublicationVersionRepository publications,
                                     PropertyRevisionEventRepository revisionEvents,
                                     PropertyCommandReceiptRepository receipts,
                                     PropertyPublicationOutboxRepository outbox,
                                     UserRepository users, BackofficePropertyAccessService access,
                                     OperationalAuditService audit, DatabaseClock clock,
                                     ObjectMapper mapper, EntityManager entityManager,
                                     PropertyPublicationSnapshotAdapter publicationSnapshots,
                                     PropertyActivityCursorCodec activityCursors) {
        this.listings = listings;
        this.localities = localities;
        this.drafts = drafts;
        this.draftMedia = draftMedia;
        this.assets = assets;
        this.publications = publications;
        this.revisionEvents = revisionEvents;
        this.receipts = receipts;
        this.outbox = outbox;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.publicationSnapshots = publicationSnapshots;
        this.activityCursors = activityCursors;
    }

    @Transactional(readOnly = true)
    public BackofficeDtos.Page list(Long userId, String rawQuery, int page) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 120) throw new IllegalArgumentException("Search is too long");
        List<Long> cityIds = new ArrayList<>();
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_EDIT_PUBLISH));
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_AUDIT_READ));
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_LIFECYCLE));
        cityIds = cityIds.stream().distinct().toList();
        if (cityIds.isEmpty()) return new BackofficeDtos.Page(List.of(), page, false);
        var slice = listings.findBackofficeInventory(cityIds, query, PageRequest.of(page, PAGE_SIZE));
        List<Long> ids = slice.getContent().stream().map(RentalDetails::getId).toList();
        Map<Long, List<PropertyMediaAsset>> mediaByListing = ids.isEmpty() ? Map.of()
                : assets.findByListingIdInOrderByUploadedAtDesc(ids).stream()
                    .collect(java.util.stream.Collectors.groupingBy(PropertyMediaAsset::getListingId));
        Map<Long, PropertyUploadDraft> open = ids.isEmpty() ? Map.of()
                : drafts.findByPublishedPropertyIdInAndStatusInOrderByIdDesc(
                        ids, List.copyOf(OPEN_REVISION_STATUSES)).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PropertyUploadDraft::getPublishedPropertyId, draft -> draft,
                            (first, ignored) -> first));
        List<BackofficeDtos.Summary> items = slice.getContent().stream().map(listing -> {
            String cover = mediaByListing.getOrDefault(listing.getId(), List.of()).stream()
                    .filter(asset -> asset.getMediaType() == MediaType.IMAGE)
                    .filter(asset -> Boolean.TRUE.equals(asset.getIsPrimaryCover()))
                    .map(PropertyMediaAsset::getMediaUrl).findFirst().orElse(null);
            PropertyUploadDraft revision = open.get(listing.getId());
            return new BackofficeDtos.Summary(listing.getId(), listing.getTitle(), listing.getCity(),
                    listing.getSector(), cover, listing.getMonthlyRent(), listing.getWorkflowStatus(),
                    listing.getVersion(), toInstant(listing.getUpdatedAt()),
                    revision == null ? null : revision.getDraftId(),
                    revision == null ? null : revision.getStatus());
        }).toList();
        return new BackofficeDtos.Page(items, page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public BackofficeDtos.Detail detail(Long userId, Long listingId) {
        PropertyContext context = propertyContext(listingId);
        requireAnyReadAuthority(userId, context.cityId());
        RentalDetails listing = context.listing();
        PropertyUploadDraft open = drafts.findOpenRevisions(listingId, List.copyOf(OPEN_REVISION_STATUSES))
                .stream().findFirst().orElse(null);
        boolean canEdit;
        try {
            access.requireRead(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, context.cityId());
            canEdit = true;
        } catch (AccessDeniedException denied) {
            canEdit = false;
        }
        boolean canSupersede = canEdit && open != null
                && "STAFF_DIRECT".equals(open.getRevisionMode())
                && "DRAFT".equals(open.getStatus())
                && open.getStaffMakerUserId() != null
                && !userId.equals(open.getStaffMakerUserId());
        Long publicationVersion = publications.currentVersion(listingId);
        return new BackofficeDtos.Detail(listingId, listing.getWorkflowStatus(), listing.getVersion(),
                context.city(), context.localityName(), currentSnapshot(listing),
                open == null ? null : open.getDraftId(), open == null ? null : open.getStatus(),
                open == null ? null : open.getVersion(),
                open == null ? null : open.getRevisionMode(), canSupersede, publicationVersion,
                toInstant(listing.getPublishedAt()), toInstant(listing.getLastContentPublishedAt()));
    }

    @Transactional
    public BackofficeDtos.Revision startRevision(Long userId, Long listingId) {
        return createRevision(userId, listingId, null);
    }

    @Transactional
    public BackofficeDtos.Revision startCorrection(Long userId, Long listingId, Long publicationVersion) {
        if (publicationVersion == null || publicationVersion <= 0) {
            throw new IllegalArgumentException("A historical publication version is required");
        }
        return createRevision(userId, listingId, publicationVersion);
    }

    private BackofficeDtos.Revision createRevision(Long userId, Long listingId, Long sourcePublication) {
        PropertyContext initial = propertyContext(listingId);
        BackofficePropertyAccessService.GrantEvidence evidence = requireMutationAuthority(
                userId, StaffCapability.PROPERTY_EDIT_PUBLISH, initial.cityId());
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext context = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, context.cityId());
        RentalDetails listing = context.listing();
        requirePublishableStatus(listing, true);
        List<PropertyUploadDraft> openRevisions = drafts.findOpenRevisions(
                listingId, List.copyOf(OPEN_REVISION_STATUSES));
        for (PropertyUploadDraft open : openRevisions) {
            if (userId.equals(open.getStaffMakerUserId()) && "STAFF_DIRECT".equals(open.getRevisionMode())
                    && "DRAFT".equals(open.getStatus()) && sourcePublication == null) {
                return revisionView(open, listing);
            }
            if ("REVIEW".equals(open.getStatus()) || "CHANGES_REQUIRED".equals(open.getStatus())) {
                throw new DraftConflictException(listingId.toString(), 0,
                        "A lessor revision is already awaiting review.");
            }
            if ("DRAFT".equals(open.getStatus())) {
                throw new DraftConflictException(listingId.toString(), 0,
                        open.getStaffMakerUserId() == null
                                ? "A lessor draft already owns the revision slot."
                                : "Another staff draft owns the revision slot. Review and explicitly supersede it first.");
            }
        }
        StaffPropertySnapshot content;
        if (sourcePublication == null) {
            content = currentSnapshot(listing);
        } else {
            PropertyPublicationVersion source = publications
                    .findByListingIdAndPublicationVersion(listingId, sourcePublication)
                    .orElseThrow(() -> new EntityNotFoundException("Publication version not found"));
            content = publicationSnapshots.read(source.getAfterSnapshot(), listingId);
        }
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("staff-" + UUID.randomUUID());
        draft.setStaffMakerUserId(userId);
        draft.setRevisionMode("STAFF_DIRECT");
        draft.setLessorProfileId(listing.getLessorProfileId());
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setPublishedPropertyId(listingId);
        draft.setRevisionBaseVersion(listing.getVersion());
        draft.setTitleSummary(listing.getTitle());
        draft.setItemCount(1);
        draft.setPayload(write(content));
        PropertyUploadDraft saved = drafts.saveAndFlush(draft);
        seedMedia(saved, listing, content, sourcePublication);
        Map<String, Object> startedDetails = new LinkedHashMap<>();
        startedDetails.put("baseVersion", listing.getVersion());
        startedDetails.put("authorityGrantId", evidence.firstGrantId());
        startedDetails.put("sourcePublicationVersion", sourcePublication == null ? 0L : sourcePublication);
        if (sourcePublication == null && publications.currentVersion(listingId) == 0) {
            startedDetails.put("legacyBaseline", snapshotMap(currentSnapshot(listing)));
        }
        revisionEvent(listingId, saved.getDraftId(), "REVISION_STARTED", userId,
                saved.getVersion(), startedDetails);
        return revisionView(saved, listing);
    }

    @Transactional
    public BackofficeDtos.Revision getRevision(Long userId, Long listingId, String draftId) {
        PropertyContext context = propertyContext(listingId);
        requireEditRead(userId, context.cityId());
        PropertyUploadDraft draft = requireStaffDraft(userId, listingId, draftId, false);
        return revisionView(draft, context.listing());
    }

    @Transactional
    public BackofficeDtos.Revision saveBasics(Long userId, Long listingId, String draftId,
                                               int expectedVersion, BackofficeDtos.Basics input) {
        validateBasics(input);
        return updateRevision(userId, listingId, draftId, expectedVersion,
                data -> new StaffPropertySnapshot(blankToNull(input.title()),
                        blankToNull(input.title()) == null ? "GENERATED" : "EXPLICIT",
                        input.description(),
                        input.propertyType(), input.bhkCount(), data.monthlyRent(), data.securityDeposit(),
                        data.availableFrom(), data.maintenanceCharge(), data.brokerageAmount(),
                        data.brokerageDays(), data.securityDepositMonths(), data.totalAreaSqFt(),
                        data.floorNumber(), data.totalFloors(), data.furnishingStatus(), data.amenities(),
                        data.bathroomCount(), data.vastuFacing(), data.bachelorAllowed(),
                        data.preferredTenant(), data.address(), data.landmark(), data.colony(),
                        data.pincode(), data.media()));
    }

    @Transactional
    public BackofficeDtos.Revision saveCommercial(Long userId, Long listingId, String draftId,
                                                   int expectedVersion, BackofficeDtos.Commercial input) {
        validateCommercial(input);
        return updateRevision(userId, listingId, draftId, expectedVersion,
                data -> new StaffPropertySnapshot(data.title(), data.titleProvenance(),
                        data.description(), data.propertyType(),
                        data.bhkCount(), input.monthlyRent(), input.securityDeposit(), input.availableFrom(),
                        input.maintenanceCharge(), input.brokerageAmount(), input.brokerageDays(),
                        input.securityDepositMonths(), data.totalAreaSqFt(), data.floorNumber(),
                        data.totalFloors(), data.furnishingStatus(), data.amenities(), data.bathroomCount(),
                        data.vastuFacing(), data.bachelorAllowed(), data.preferredTenant(),
                        data.address(), data.landmark(), data.colony(), data.pincode(), data.media()));
    }

    @Transactional
    public BackofficeDtos.Revision saveDetails(Long userId, Long listingId, String draftId,
                                                int expectedVersion, BackofficeDtos.Details input) {
        validateDetails(input);
        return updateRevision(userId, listingId, draftId, expectedVersion,
                data -> new StaffPropertySnapshot(data.title(), data.titleProvenance(),
                        data.description(), data.propertyType(),
                        data.bhkCount(), data.monthlyRent(), data.securityDeposit(), data.availableFrom(),
                        data.maintenanceCharge(), data.brokerageAmount(), data.brokerageDays(),
                        data.securityDepositMonths(), input.totalAreaSqFt(), input.floorNumber(),
                        input.totalFloors(), input.furnishingStatus(), input.amenities(),
                        input.bathroomCount(), input.vastuFacing(), input.bachelorAllowed(),
                        input.preferredTenant(), data.address(), data.landmark(), data.colony(),
                        data.pincode(), data.media()));
    }

    @Transactional
    public BackofficeDtos.Revision saveAddress(Long userId, Long listingId, String draftId,
                                                int expectedVersion,
                                                BackofficeDtos.PresentationAddress input) {
        validateAddress(input);
        return updateRevision(userId, listingId, draftId, expectedVersion,
                data -> new StaffPropertySnapshot(data.title(), data.titleProvenance(),
                        data.description(), data.propertyType(),
                        data.bhkCount(), data.monthlyRent(), data.securityDeposit(), data.availableFrom(),
                        data.maintenanceCharge(), data.brokerageAmount(), data.brokerageDays(),
                        data.securityDepositMonths(), data.totalAreaSqFt(), data.floorNumber(),
                        data.totalFloors(), data.furnishingStatus(), data.amenities(), data.bathroomCount(),
                        data.vastuFacing(), data.bachelorAllowed(), data.preferredTenant(),
                        trimToNull(input.address()), trimToNull(input.landmark()), trimToNull(input.colony()),
                        blankToNull(input.pincode()), data.media()));
    }

    @Transactional
    public BackofficeDtos.Revision saveMedia(Long userId, Long listingId, String draftId,
                                              int expectedVersion,
                                              List<BackofficeDtos.MediaPresentation> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("Choose at least one property photo");
        Set<String> unique = new HashSet<>();
        input.forEach(item -> {
            if (item == null || item.mediaId() == null || item.mediaId().isBlank()
                    || !unique.add(item.mediaId()) || item.roomTag() == null
                    || item.sortOrder() == null || item.sortOrder() < 0
                    || item.caption() != null && item.caption().length() > 255) {
                throw new IllegalArgumentException("Media presentation contains an invalid value");
            }
        });
        return updateRevision(userId, listingId, draftId, expectedVersion, data -> {
            List<PropertyDraftMedia> rows = draftMedia.findByDraftIdOrderBySortOrderAscIdAsc(draftId);
            Map<String, PropertyDraftMedia> byId = rows.stream()
                    .collect(java.util.stream.Collectors.toMap(PropertyDraftMedia::getMediaId, item -> item));
            if (!byId.keySet().containsAll(unique)) {
                throw new IllegalArgumentException("Media membership does not match this revision");
            }
            rows.stream().filter(row -> !unique.contains(row.getMediaId()))
                    .filter(row -> !"REMOVED_FROM_REVISION".equals(row.getUploadStatus()))
                    .forEach(row -> row.setUploadStatus("REMOVED_FROM_REVISION"));
            long covers = input.stream().filter(BackofficeDtos.MediaPresentation::cover)
                    .filter(item -> byId.get(item.mediaId()).getContentType().startsWith("image/")).count();
            if (covers != 1) throw new IllegalArgumentException("Choose exactly one photo as the cover");
            for (BackofficeDtos.MediaPresentation item : input) {
                PropertyDraftMedia row = byId.get(item.mediaId());
                row.setRoomTag(item.roomTag().name());
                row.setCaption(trimToNull(item.caption()));
                row.setSortOrder(item.sortOrder());
                row.setIsCover(item.cover());
            }
            draftMedia.saveAll(rows);
            List<StaffPropertySnapshot.Media> media = input.stream()
                    .sorted(Comparator.comparing(BackofficeDtos.MediaPresentation::sortOrder))
                    .map(item -> {
                        PropertyDraftMedia row = byId.get(item.mediaId());
                        return new StaffPropertySnapshot.Media(row.getSourceMediaAssetId(), row.getMediaId(),
                                row.getCloudinaryUrl(), mediaType(row.getContentType()),
                                item.roomTag(), trimToNull(item.caption()), item.sortOrder(),
                                item.cover(), row.getUploadStatus());
                    }).toList();
            return new StaffPropertySnapshot(data.title(), data.titleProvenance(),
                    data.description(), data.propertyType(),
                    data.bhkCount(), data.monthlyRent(), data.securityDeposit(), data.availableFrom(),
                    data.maintenanceCharge(), data.brokerageAmount(), data.brokerageDays(),
                    data.securityDepositMonths(), data.totalAreaSqFt(), data.floorNumber(),
                    data.totalFloors(), data.furnishingStatus(), data.amenities(), data.bathroomCount(),
                    data.vastuFacing(), data.bachelorAllowed(), data.preferredTenant(),
                    data.address(), data.landmark(), data.colony(), data.pincode(), media);
        });
    }

    @Transactional
    public BackofficeDtos.Committed discard(Long userId, Long listingId, String draftId,
                                            String reason) {
        String cleanReason = requireReason(reason);
        PropertyContext initial = propertyContext(listingId);
        requireMutationAuthority(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, initial.cityId());
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext current = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, current.cityId());
        PropertyUploadDraft draft = requireStaffDraft(userId, listingId, draftId, true);
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "Only an editable staff draft can be discarded.");
        }
        draft.setStatus("DISCARDED");
        drafts.saveAndFlush(draft);
        revisionEvent(listingId, draftId, "DRAFT_DISCARDED", userId, draft.getVersion(),
                Map.of("reason", cleanReason, "reasonCode", "DRAFT_DISCARD"));
        return new BackofficeDtos.Committed(listingId, current.listing().getVersion(),
                current.listing().getWorkflowStatus(), publications.currentVersion(listingId), clock.now());
    }

    @Transactional
    public BackofficeDtos.Revision supersede(Long userId, Long listingId, String draftId,
                                              int expectedVersion, String reason) {
        String cleanReason = requireReason(reason);
        PropertyContext initial = propertyContext(listingId);
        BackofficePropertyAccessService.GrantEvidence evidence = requireMutationAuthority(
                userId, StaffCapability.PROPERTY_EDIT_PUBLISH, initial.cityId());
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext current = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, current.cityId());
        PropertyUploadDraft prior = drafts.findByDraftIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!listingId.equals(prior.getPublishedPropertyId()) || !"STAFF_DIRECT".equals(prior.getRevisionMode())
                || !"DRAFT".equals(prior.getStatus())) {
            throw new DraftConflictException(draftId, prior.getVersion(),
                    "Only another staff draft can be superseded.");
        }
        if (prior.getVersion() == null || prior.getVersion() != expectedVersion) {
            throw new DraftConflictException(draftId, prior.getVersion(),
                    "A newer version of this revision exists. Reload before superseding it.");
        }
        prior.setStatus("SUPERSEDED");
        drafts.saveAndFlush(prior);
        revisionEvent(listingId, draftId, "STAFF_DRAFT_SUPERSEDED", userId, prior.getVersion(),
                Map.of("reason", cleanReason, "reasonCode", "DRAFT_SUPERSEDE",
                        "authorityGrantId", evidence.firstGrantId()));
        return createRevisionAfterSupersede(userId, current.listing());
    }

    private BackofficeDtos.Revision createRevisionAfterSupersede(Long userId, RentalDetails listing) {
        PropertyUploadDraft draft = newDraft(userId, listing, currentSnapshot(listing));
        seedMedia(draft, listing, currentSnapshot(listing), null);
        revisionEvent(listing.getId(), draft.getDraftId(), "REVISION_STARTED", userId,
                draft.getVersion(), Map.of("baseVersion", listing.getVersion()));
        return revisionView(draft, listing);
    }

    @Transactional
    public BackofficeDtos.PublishResult publish(Long userId, Long listingId, String draftId,
                                                 UUID operationId, int expectedDraftVersion,
                                                 Long expectedListingVersion, String reason) {
        if (operationId == null) throw new IllegalArgumentException("Operation ID is required");
        String cleanReason = requireReason(reason);
        String payloadHash = hash("PUBLISH|" + listingId + "|" + draftId + "|"
                + expectedDraftVersion + "|" + expectedListingVersion + "|" + cleanReason);
        PropertyContext initial = propertyContext(listingId);
        BackofficePropertyAccessService.GrantEvidence evidence = requireMutationAuthority(
                userId, StaffCapability.PROPERTY_EDIT_PUBLISH, initial.cityId());
        Optional<PropertyCommandReceipt> existing = receipts.findByOperationId(operationId);
        if (existing.isPresent()) return replayPublication(existing.get(), userId, listingId, draftId, payloadHash);

        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext context = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, context.cityId());
        RentalDetails listing = context.listing();
        Optional<PropertyCommandReceipt> postLockReceipt = receipts.findByOperationId(operationId);
        if (postLockReceipt.isPresent()) {
            return replayPublication(postLockReceipt.get(), userId, listingId, draftId, payloadHash);
        }
        requirePublishableStatus(listing, false);
        PropertyUploadDraft draft = requireStaffDraft(userId, listingId, draftId, true);
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "This revision is no longer publishable.");
        }
        if (draft.getVersion() == null || draft.getVersion() != expectedDraftVersion) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "The revision changed. Review the current draft before publishing.");
        }
        if (listing.getVersion() == null || !listing.getVersion().equals(expectedListingVersion)
                || !expectedListingVersion.equals(draft.getRevisionBaseVersion())) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "The property changed after this revision began. Reload and create a fresh correction.");
        }
        StaffPropertySnapshot content = read(draft);
        List<String> blockers = readiness(content, listingId);
        if (!blockers.isEmpty()) throw new IllegalArgumentException("Publication is incomplete: " + String.join(", ", blockers));
        Map<String, Object> before = snapshotMap(currentSnapshot(listing));
        applyContent(listing, content, context.locality());
        applyMedia(listingId, draftId, content, context);
        Instant committedAt = clock.now();
        LocalDateTime localCommittedAt = LocalDateTime.ofInstant(committedAt, INDIA);
        listing.setLastContentPublishedAt(localCommittedAt);
        if (listing.getPublishedAt() == null) listing.setPublishedAt(localCommittedAt);
        RentalDetails saved = (RentalDetails) listings.saveAndFlush(listing);
        StaffPropertySnapshot afterContent = currentSnapshot(saved);
        Map<String, Object> after = snapshotMap(afterContent);
        Map<String, Object> changed = changed(before, after);
        long version = publications.currentVersion(listingId) + 1;
        PropertyPublicationVersion publication = new PropertyPublicationVersion(listingId, version,
                draftId, "STAFF_DIRECT", userId,
                Map.of("capability", StaffCapability.PROPERTY_EDIT_PUBLISH.name(),
                        "scopeType", "CITY", "cityId", context.cityId(),
                        "grantId", evidence.firstGrantId()),
                before, after, changed, committedAt);
        publications.saveAndFlush(publication);
        receipts.saveAndFlush(new PropertyCommandReceipt(operationId, "PROPERTY_PUBLISH",
                listingId, payloadHash, userId, version, committedAt));
        outbox.saveAndFlush(new PropertyPublicationOutbox(listingId, version,
                "PROPERTY_CONTENT_PUBLISHED", Map.of("listingId", listingId,
                "publicationVersion", version, "listingVersion", saved.getVersion(),
                "changedFields", changed.keySet().stream().sorted().toList()), committedAt));
        revisionEvent(listingId, draftId, "PUBLISHED", userId, draft.getVersion(),
                Map.of("publicationVersion", version, "reason", cleanReason,
                        "reasonCode", "PROPERTY_PUBLISH"));
        draft.setStatus("APPROVED");
        draft.setReviewNote(null);
        drafts.saveAndFlush(draft);
        User actor = users.findById(userId).orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        audit.recordUserEvent(actor, "PROPERTY_CONTENT_PUBLISHED", "LISTING", listingId,
                "STAFF_PROPERTY_PUBLICATION", null, null,
                Map.of("publicationVersion", version, "previousVersion", expectedListingVersion,
                        "resultingVersion", saved.getVersion(),
                        "capability", StaffCapability.PROPERTY_EDIT_PUBLISH.name(),
                        "scopeType", "CITY", "cityId", context.cityId()));
        return new BackofficeDtos.PublishResult(listingId, saved.getVersion(), version,
                committedAt, saved.getWorkflowStatus());
    }

    @Transactional
    public BackofficeDtos.Committed lifecycle(Long userId, Long listingId, UUID operationId,
                                              Long expectedVersion, String action, String reason) {
        if (operationId == null) throw new IllegalArgumentException("Operation ID is required");
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("PAUSE") && !normalized.equals("ARCHIVE")) {
            throw new IllegalArgumentException("Lifecycle action must be PAUSE or ARCHIVE");
        }
        String cleanReason = requireReason(reason);
        String payloadHash = hash(normalized + "|" + listingId + "|" + expectedVersion + "|" + cleanReason);
        PropertyContext initial = propertyContext(listingId);
        BackofficePropertyAccessService.GrantEvidence evidence = requireMutationAuthority(
                userId, StaffCapability.PROPERTY_LIFECYCLE, initial.cityId());
        Optional<PropertyCommandReceipt> prior = receipts.findByOperationId(operationId);
        if (prior.isPresent()) {
            PropertyCommandReceipt receipt = prior.get();
            if (!"PROPERTY_LIFECYCLE".equals(receipt.getCommandType())
                    || !listingId.equals(receipt.getAggregateId())
                    || !payloadHash.equals(receipt.getPayloadHash())
                    || !userId.equals(receipt.getActorUserId())) {
                throw new DraftConflictException(listingId.toString(), 0,
                        "This operation ID is already committed with different details.");
            }
            Listing existing = listings.findById(listingId)
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            return new BackofficeDtos.Committed(listingId, existing.getVersion(),
                    existing.getWorkflowStatus(), publications.currentVersion(listingId), clock.now());
        }
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext context = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_LIFECYCLE, context.cityId());
        Optional<PropertyCommandReceipt> postLockReceipt = receipts.findByOperationId(operationId);
        if (postLockReceipt.isPresent()) {
            PropertyCommandReceipt receipt = postLockReceipt.get();
            if (!"PROPERTY_LIFECYCLE".equals(receipt.getCommandType())
                    || !listingId.equals(receipt.getAggregateId())
                    || !payloadHash.equals(receipt.getPayloadHash())
                    || !userId.equals(receipt.getActorUserId())) {
                throw new DraftConflictException(listingId.toString(), 0,
                        "This operation ID is already committed with different details.");
            }
            Listing existing = listings.findById(listingId)
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            return new BackofficeDtos.Committed(listingId, existing.getVersion(),
                    existing.getWorkflowStatus(), publications.currentVersion(listingId), clock.now());
        }
        Listing listing = context.listing();
        if (expectedVersion == null || !expectedVersion.equals(listing.getVersion())) {
            throw new DraftConflictException(listingId.toString(), 0,
                    "The property changed. Reload before changing its lifecycle.");
        }
        if (normalized.equals("PAUSE") && listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED) {
            throw new DraftConflictException(listingId.toString(), 0,
                    "Only a published property can be paused.");
        }
        if (normalized.equals("ARCHIVE") && !List.of(ListingWorkflowStatus.PUBLISHED,
                ListingWorkflowStatus.PAUSED).contains(listing.getWorkflowStatus())) {
            throw new DraftConflictException(listingId.toString(), 0,
                    "This property cannot be archived through content lifecycle.");
        }
        List<PropertyRemovalRequest> pending = normalized.equals("ARCHIVE")
                ? pendingRemovalRequests(listingId) : List.of();
        if (!pending.isEmpty()) {
            throw new DraftConflictException(listingId.toString(), 0,
                    "A pending lessor removal request must be decided by an authorized Admin.");
        }
        Instant committedAt = clock.now();
        listing.setWorkflowStatus(normalized.equals("PAUSE")
                ? ListingWorkflowStatus.PAUSED : ListingWorkflowStatus.ARCHIVED);
        listing.setStatus(ListingStatus.CLOSED);
        Listing saved = listings.saveAndFlush(listing);
        receipts.saveAndFlush(new PropertyCommandReceipt(operationId, "PROPERTY_LIFECYCLE",
                listingId, payloadHash, userId, saved.getVersion(), committedAt));
        revisionEvent(listingId, null, "LIFECYCLE_" + normalized, userId, (Integer) null,
                Map.of("reason", cleanReason, "reasonCode", "LIFECYCLE_" + normalized,
                        "previousVersion", expectedVersion,
                        "resultingVersion", saved.getVersion()));
        User actor = users.findById(userId).orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        audit.recordUserEvent(actor, "PROPERTY_" + normalized, "LISTING", listingId,
                "PROPERTY_LIFECYCLE", null, null,
                Map.of("fromStatus", normalized.equals("PAUSE") ? "PUBLISHED" : "ACTIVE",
                        "toStatus", saved.getWorkflowStatus().name(),
                        "previousVersion", expectedVersion, "resultingVersion", saved.getVersion(),
                        "capability", StaffCapability.PROPERTY_LIFECYCLE.name(),
                        "scopeType", "CITY", "cityId", context.cityId()));
        return new BackofficeDtos.Committed(listingId, saved.getVersion(), saved.getWorkflowStatus(),
                publications.currentVersion(listingId), committedAt);
    }

    @Transactional
    public BackofficeDtos.ActivityPage activity(Long userId, Long listingId, int page) {
        return activity(userId, listingId, page, null, PAGE_SIZE);
    }

    @Transactional
    public BackofficeDtos.ActivityPage activity(Long userId, Long listingId, int page,
                                                 String encodedCursor, int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Activity limit must be between 1 and 50");
        }
        if (page < 0 || page > 100) {
            throw new IllegalArgumentException("Activity page must be between 0 and 100");
        }
        boolean cursorProvided = encodedCursor != null;
        boolean cursorMode = cursorProvided && !encodedCursor.isBlank();
        if (cursorProvided && encodedCursor.isBlank()) {
            throw new IllegalArgumentException("Activity cursor is malformed");
        }
        if (cursorMode && page != 0) {
            throw new IllegalArgumentException("page and cursor cannot be combined");
        }
        PropertyContext context = propertyContext(listingId);
        requireCommandReadAuthority(userId, StaffCapability.PROPERTY_AUDIT_READ, context.cityId());

        PropertyActivityCursorCodec.Cursor cursor = cursorMode
                ? activityCursors.decode(encodedCursor, listingId, limit) : null;
        long publicationCutoff;
        long revisionCutoff;
        if (cursor == null) {
            if (page == 0) {
                listings.lockForSchedulingById(listingId)
                        .orElseThrow(() -> new EntityNotFoundException("Property not found"));
                context = refreshedPropertyContextAfterLock(listingId);
                requireCommandReadAuthority(userId, StaffCapability.PROPERTY_AUDIT_READ, context.cityId());
            }
            publicationCutoff = publications.latestRowId(listingId);
            revisionCutoff = revisionEvents.latestRowId(listingId);
        } else {
            publicationCutoff = cursor.publicationCutoff();
            revisionCutoff = cursor.revisionCutoff();
            verifyActivityCursorReferences(cursor, publicationCutoff, revisionCutoff);
        }

        int effectiveLimit = cursor == null && page > 0 ? PAGE_SIZE : limit;
        int offset = cursor == null ? Math.multiplyExact(page, PAGE_SIZE) : 0;
        int fetchSize = Math.addExact(offset, effectiveLimit + 1);
        PageRequest request = PageRequest.of(0, fetchSize);
        List<PropertyPublicationVersion> publicationRows;
        List<PropertyRevisionEvent> eventRows;
        if (cursor == null) {
            publicationRows = publications.findActivityCohort(listingId, publicationCutoff, request);
            eventRows = revisionEvents.findActivityCohort(listingId, revisionCutoff, request);
        } else {
            publicationRows = publications.findActivityAfterAnchor(listingId, publicationCutoff,
                    cursor.timestamp(), cursor.lastSource().rank(), cursor.lastRowId(), request);
            eventRows = revisionEvents.findActivityAfterAnchor(listingId, revisionCutoff,
                    cursor.timestamp(), cursor.lastSource().rank(), cursor.lastRowId(), request);
        }

        List<PropertyActivityOrdering.Entry> merged = activityEntries(publicationRows, eventRows);
        int end = Math.min(merged.size(), offset + effectiveLimit);
        List<PropertyActivityOrdering.Entry> selected = offset >= merged.size()
                ? List.of() : merged.subList(offset, end);
        boolean hasMore = cursor == null && page == 0
                ? merged.size() > effectiveLimit
                : merged.size() > end;
        int responsePage = cursor == null ? page : cursor.page();
        Integer nextPage = null;
        if (hasMore && !selected.isEmpty()) {
            nextPage = cursor == null ? 1 : nextCursorPage(cursor.page());
        }
        String nextCursor = null;
        if (nextPage != null) {
            PropertyActivityOrdering.Entry anchor = selected.get(selected.size() - 1);
            nextCursor = activityCursors.encode(new PropertyActivityCursorCodec.Cursor(
                    1, listingId, anchor.source(), anchor.timestamp(), anchor.rowId(),
                    publicationCutoff, revisionCutoff, limit, nextPage));
        }
        List<BackofficeDtos.Activity> items = withActorNames(selected);
        return new BackofficeDtos.ActivityPage(items, responsePage, hasMore, nextCursor);
    }

    static Integer nextCursorPage(int currentPage) {
        try {
            return Math.addExact(currentPage, 1);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private List<PropertyActivityOrdering.Entry> activityEntries(
            List<PropertyPublicationVersion> publicationRows,
            List<PropertyRevisionEvent> eventRows) {
        List<PropertyActivityOrdering.Entry> merged = new ArrayList<>();
        publicationRows.forEach(row -> merged.add(new PropertyActivityOrdering.Entry(
                new BackofficeDtos.Activity("PUBLICATION:" + row.getId(), "PUBLICATION",
                        row.getEventKind(), row.getRevisionId(), row.getActorUserId(), "",
                        row.getPublishedAt(), row.getPublicationVersion(),
                        "Published version " + row.getPublicationVersion() + " with "
                                + row.getChangedFields().size() + " changed section(s).",
                        row.getChangedFields()),
                PropertyActivityOrdering.Source.PUBLICATION, row.getId(), row.getPublishedAt())));
        eventRows.forEach(row -> merged.add(new PropertyActivityOrdering.Entry(
                new BackofficeDtos.Activity("REVISION:" + row.getId(), "REVISION",
                        row.getEventType(), row.getDraftId(), row.getActorUserId(), "",
                        row.getOccurredAt(),
                        row.getDraftVersion() == null ? null : row.getDraftVersion().longValue(),
                        activitySummary(row.getEventType()), row.getDetails()),
                PropertyActivityOrdering.Source.REVISION, row.getId(), row.getOccurredAt())));
        merged.sort(PropertyActivityOrdering.ORDER);
        return merged;
    }

    private List<BackofficeDtos.Activity> withActorNames(
            List<PropertyActivityOrdering.Entry> selected) {
        Set<Long> actorIds = selected.stream().map(entry -> entry.activity().actorUserId())
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> actorNames = actorIds.isEmpty() ? Map.of()
                : users.findAllById(actorIds).stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId,
                            user -> user.getFullName() == null || user.getFullName().isBlank()
                                    ? "User #" + user.getId() : user.getFullName()));
        return selected.stream().map(entry -> {
            BackofficeDtos.Activity item = entry.activity();
            return new BackofficeDtos.Activity(item.eventId(), item.kind(), item.eventType(),
                    item.draftId(), item.actorUserId(),
                    actorNames.getOrDefault(item.actorUserId(), "User #" + item.actorUserId()),
                    item.occurredAt(), item.version(), item.summary(), item.details());
        }).toList();
    }

    private void verifyActivityCursorReferences(PropertyActivityCursorCodec.Cursor cursor,
                                                 long publicationCutoff, long revisionCutoff) {
        if (publicationCutoff > 0 && publications
                .findByIdAndListingId(publicationCutoff, cursor.listingId()).isEmpty()) {
            throw new IllegalArgumentException("Activity cursor cutoff does not match this property");
        }
        if (revisionCutoff > 0 && revisionEvents
                .findByIdAndListingId(revisionCutoff, cursor.listingId()).isEmpty()) {
            throw new IllegalArgumentException("Activity cursor cutoff does not match this property");
        }
        if (cursor.lastSource() == PropertyActivityOrdering.Source.PUBLICATION) {
            PropertyPublicationVersion anchor = publications
                    .findByIdAndListingId(cursor.lastRowId(), cursor.listingId())
                    .filter(row -> row.getPublishedAt().equals(cursor.timestamp()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Activity cursor anchor does not match this property"));
            if (anchor.getId() > publicationCutoff) {
                throw new IllegalArgumentException("Activity cursor anchor is outside its cohort");
            }
        } else {
            PropertyRevisionEvent anchor = revisionEvents
                    .findByIdAndListingId(cursor.lastRowId(), cursor.listingId())
                    .filter(row -> row.getOccurredAt().equals(cursor.timestamp()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Activity cursor anchor does not match this property"));
            if (anchor.getId() > revisionCutoff) {
                throw new IllegalArgumentException("Activity cursor anchor is outside its cohort");
            }
        }
    }

    @Transactional(readOnly = true)
    public BackofficeDtos.CommandOutcome commandOutcome(Long userId, Long listingId, UUID operationId) {
        PropertyContext context = propertyContext(listingId);
        requireAnyReadAuthority(userId, context.cityId());
        PropertyCommandReceipt receipt = receipts.findByOperationId(operationId)
                .filter(row -> listingId.equals(row.getAggregateId()))
                .filter(row -> userId.equals(row.getActorUserId()))
                .orElseThrow(() -> new EntityNotFoundException("Command outcome not found"));
        StaffCapability duty = switch (receipt.getCommandType()) {
            case "PROPERTY_PUBLISH" -> StaffCapability.PROPERTY_EDIT_PUBLISH;
            case "PROPERTY_LIFECYCLE" -> StaffCapability.PROPERTY_LIFECYCLE;
            default -> throw new EntityNotFoundException("Command outcome not found");
        };
        requireCommandReadAuthority(userId, duty, context.cityId());
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        return new BackofficeDtos.CommandOutcome(operationId, receipt.getCommandType(), listingId,
                receipt.getResultVersion(), listing.getVersion(), listing.getWorkflowStatus(),
                receipt.getCreatedAt());
    }

    private BackofficeDtos.Revision updateRevision(Long userId, Long listingId, String draftId,
                                                    int expectedVersion,
                                                    UnaryOperator<StaffPropertySnapshot> update) {
        PropertyContext initial = propertyContext(listingId);
        requireMutationAuthority(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, initial.cityId());
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        PropertyContext context = refreshedPropertyContextAfterLock(listingId);
        access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, context.cityId());
        PropertyUploadDraft draft = requireStaffDraft(userId, listingId, draftId, true);
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "This revision is no longer editable.");
        }
        if (draft.getVersion() == null || draft.getVersion() != expectedVersion) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "A newer revision version exists. Review it before saving again.");
        }
        StaffPropertySnapshot updated = update.apply(read(draft));
        draft.setPayload(write(updated));
        draft.setTitleSummary(updated.title() == null ? draft.getTitleSummary() : updated.title());
        PropertyUploadDraft saved = drafts.saveAndFlush(draft);
        revisionEvent(listingId, draftId, "SECTION_SAVED", userId, saved.getVersion(),
                Map.of("baseVersion", saved.getRevisionBaseVersion()));
        return revisionView(saved, context.listing());
    }

    private PropertyUploadDraft requireStaffDraft(Long userId, Long listingId, String draftId,
                                                   boolean lock) {
        PropertyUploadDraft draft = (lock ? drafts.findStaffDraftForUpdate(draftId, userId)
                : drafts.findByDraftId(draftId))
                .filter(row -> userId.equals(row.getStaffMakerUserId()))
                .filter(row -> listingId.equals(row.getPublishedPropertyId()))
                .filter(row -> "STAFF_DIRECT".equals(row.getRevisionMode()))
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if ("DISCARDED".equals(draft.getStatus())) {
            throw new EntityNotFoundException("Revision not found");
        }
        return draft;
    }

    private BackofficeDtos.Revision revisionView(PropertyUploadDraft draft, RentalDetails listing) {
        StaffPropertySnapshot content = read(draft);
        List<BackofficeDtos.FieldChange> changes = changes(currentSnapshot(listing), content);
        List<String> blockers = readiness(content, listing.getId());
        return new BackofficeDtos.Revision(draft.getDraftId(), listing.getId(), draft.getRevisionMode(),
                draft.getStatus(), draft.getVersion(), draft.getRevisionBaseVersion(), content, changes,
                new BackofficeDtos.Preview(blockers.isEmpty(), blockers,
                        List.of("Media approval history is unavailable for legacy resources."),
                        changes, draft.getRevisionBaseVersion(), listing.getVersion()));
    }

    private void seedMedia(PropertyUploadDraft draft, RentalDetails listing,
                           StaffPropertySnapshot content, Long sourcePublication) {
        List<PropertyDraftMedia> rows = new ArrayList<>();
        if (sourcePublication != null) {
            List<StaffPropertySnapshot.Media> historical = content.media();
            int order = 0;
            for (StaffPropertySnapshot.Media item : historical) {
                PropertyDraftMedia row = new PropertyDraftMedia();
                row.setMediaId(UUID.randomUUID().toString());
                row.setDraftId(draft.getDraftId());
                row.setStaffMakerUserId(draft.getStaffMakerUserId());
                row.setSourceMediaAssetId(item.sourceMediaAssetId());
                row.setOriginalFilename("Historical property media");
                row.setContentType(item.mediaType() == MediaType.IMAGE ? "image/jpeg" : "video/mp4");
                row.setCloudinaryUrl(item.url());
                row.setUploadStatus("UPLOADED");
                row.setSortOrder(item.sortOrder() == null ? order : item.sortOrder());
                row.setIsCover(item.cover() && item.mediaType() == MediaType.IMAGE);
                row.setRoomTag(item.roomTag() == null ? RoomTag.GENERAL.name() : item.roomTag().name());
                row.setCaption(item.caption());
                row.setReusedFromListing(true);
                rows.add(row);
                order++;
            }
        } else {
            int order = 0;
            for (PropertyMediaAsset asset : assets.findByListingIdOrderByUploadedAtDesc(listing.getId())) {
                PropertyDraftMedia row = new PropertyDraftMedia();
                row.setMediaId(UUID.randomUUID().toString());
                row.setDraftId(draft.getDraftId());
                row.setStaffMakerUserId(draft.getStaffMakerUserId());
                row.setSourceMediaAssetId(asset.getId());
                row.setOriginalFilename("Existing property media");
                row.setContentType(asset.getMediaType() == MediaType.IMAGE ? "image/jpeg" : "video/mp4");
                row.setCloudinaryUrl(asset.getMediaUrl());
                row.setCloudinaryPublicId(asset.getCloudinaryPublicId());
                row.setUploadStatus("UPLOADED");
                row.setSortOrder(asset.getSortOrder() == null ? order : asset.getSortOrder());
                row.setIsCover(Boolean.TRUE.equals(asset.getIsPrimaryCover())
                        && asset.getMediaType() == MediaType.IMAGE);
                row.setRoomTag(asset.getRoomTag() == null ? RoomTag.GENERAL.name() : asset.getRoomTag().name());
                row.setCaption(asset.getCaption());
                row.setReusedFromListing(true);
                rows.add(row);
                order++;
            }
        }
        if (!rows.isEmpty()) draftMedia.saveAllAndFlush(rows);
        StaffPropertySnapshot withMedia = new StaffPropertySnapshot(content.title(),
                content.titleProvenance(), content.description(),
                content.propertyType(), content.bhkCount(), content.monthlyRent(), content.securityDeposit(),
                content.availableFrom(), content.maintenanceCharge(), content.brokerageAmount(),
                content.brokerageDays(), content.securityDepositMonths(), content.totalAreaSqFt(),
                content.floorNumber(), content.totalFloors(), content.furnishingStatus(), content.amenities(),
                content.bathroomCount(), content.vastuFacing(), content.bachelorAllowed(),
                content.preferredTenant(), content.address(), content.landmark(), content.colony(),
                content.pincode(), rows.stream().sorted(Comparator.comparing(
                        PropertyDraftMedia::getSortOrder)).map(this::draftMediaView).toList());
        draft.setPayload(write(withMedia));
        drafts.saveAndFlush(draft);
    }

    private void applyContent(RentalDetails listing, StaffPropertySnapshot data, Locality locality) {
        String title = data.title();
        boolean generatedTitle = title == null || title.isBlank();
        if (title == null || title.isBlank()) {
            String type = data.propertyType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            title = data.bhkCount() + " " + type + " in " + locality.getSectorName();
        }
        listing.setTitle(title.trim());
        listing.setTitleProvenance(generatedTitle ? "GENERATED" : "EXPLICIT");
        listing.setDescription(data.description());
        listing.setPropertyType(data.propertyType());
        listing.setBhkCount(data.bhkCount());
        listing.setMonthlyRent(data.monthlyRent());
        listing.setSecurityDeposit(data.securityDeposit());
        listing.setAvailableFrom(data.availableFrom().atStartOfDay());
        listing.setMaintenanceCharge(data.maintenanceCharge());
        listing.setBrokerageAmount(data.brokerageAmount());
        listing.setBrokerageDays(data.brokerageDays());
        listing.setSecurityDepositMonths(data.securityDepositMonths());
        listing.setTotalAreaSqFt(data.totalAreaSqFt());
        listing.setFloorNumber(data.floorNumber());
        listing.setTotalFloors(data.totalFloors());
        listing.setFurnishingStatus(data.furnishingStatus());
        listing.setAmenities(data.amenities());
        listing.setBathroomCount(data.bathroomCount());
        listing.setVastuFacing(data.vastuFacing());
        listing.setBachelorAllowed(data.bachelorAllowed());
        listing.setPreferredTenant(data.preferredTenant());
        listing.setAddress(data.address());
        listing.setLandmark(data.landmark());
        listing.setColony(data.colony());
        listing.setPincode(data.pincode());
    }

    private void applyMedia(Long listingId, String draftId, StaffPropertySnapshot data,
                            PropertyContext context) {
        Map<Long, PropertyMediaAsset> current = assets.findByListingIdOrderByUploadedAtDesc(listingId)
                .stream().collect(java.util.stream.Collectors.toMap(PropertyMediaAsset::getId, item -> item));
        Set<Long> retained = new HashSet<>();
        List<PropertyDraftMedia> rows = draftMedia.findByDraftIdOrderBySortOrderAscIdAsc(draftId);
        for (PropertyDraftMedia row : rows.stream().filter(item -> "UPLOADED".equals(item.getUploadStatus())).toList()) {
            PropertyMediaAsset asset;
            if (row.getSourceMediaAssetId() != null) {
                asset = current.get(row.getSourceMediaAssetId());
                if (asset == null) asset = assets.findById(row.getSourceMediaAssetId())
                        .filter(item -> listingId.equals(item.getListingId()))
                        .orElseThrow(() -> new IllegalArgumentException("Historical media no longer belongs to this property"));
                retained.add(asset.getId());
            } else {
                asset = new PropertyMediaAsset(listingId, row.getCloudinaryUrl(),
                        mediaType(row.getContentType()), RoomTag.fromStored(row.getRoomTag()), row.getCaption());
                asset.setUploadRequestId(row.getMediaId());
                asset.setCloudinaryPublicId(row.getCloudinaryPublicId());
            }
            asset.setMediaType(mediaType(row.getContentType()));
            asset.setRetiredAt(null);
            asset.setRoomTag(RoomTag.fromStored(row.getRoomTag()));
            asset.setCaption(row.getCaption());
            asset.setSortOrder(row.getSortOrder());
            asset.setIsPrimaryCover(Boolean.TRUE.equals(row.getIsCover())
                    && asset.getMediaType() == MediaType.IMAGE);
            asset.setCity(context.city());
            asset.setSector(context.localityName());
            asset.setPriceTag("₹" + data.monthlyRent().toPlainString() + " / month");
            assets.save(asset);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.now(), INDIA);
        current.values().stream().filter(asset -> !retained.contains(asset.getId()))
                .forEach(asset -> asset.setRetiredAt(now));
        assets.saveAll(current.values());
        assets.flush();
    }

    private List<String> readiness(StaffPropertySnapshot data, Long listingId) {
        List<String> blockers = new ArrayList<>();
        try { validateCommercial(new BackofficeDtos.Commercial(data.monthlyRent(),
                data.securityDeposit(), data.availableFrom(), data.maintenanceCharge(),
                data.brokerageAmount(), data.brokerageDays(), data.securityDepositMonths())); }
        catch (IllegalArgumentException ex) { blockers.add(ex.getMessage()); }
        try { validateBasics(new BackofficeDtos.Basics(data.title(), data.description(),
                data.bhkCount(), data.propertyType())); }
        catch (IllegalArgumentException ex) { blockers.add(ex.getMessage()); }
        try { validateDetails(new BackofficeDtos.Details(data.totalAreaSqFt(), data.floorNumber(),
                data.totalFloors(), data.furnishingStatus(), data.amenities(), data.bathroomCount(),
                data.vastuFacing(), data.bachelorAllowed(), data.preferredTenant())); }
        catch (IllegalArgumentException ex) { blockers.add(ex.getMessage()); }
        try { validateAddress(new BackofficeDtos.PresentationAddress(data.address(), data.landmark(),
                data.colony(), data.pincode())); }
        catch (IllegalArgumentException ex) { blockers.add(ex.getMessage()); }
        // The snapshot's membership is the authoritative review state; validate it without a second
        // query that could accidentally select a newer draft.
        long covers = data.media().stream().filter(StaffPropertySnapshot.Media::cover)
                .filter(item -> item.mediaType() == MediaType.IMAGE).count();
        if (covers != 1) blockers.add("Choose exactly one ready photo as the cover");
        if (data.media().stream().anyMatch(item -> !"UPLOADED".equals(item.uploadStatus()))) {
            blockers.add("Wait for media processing to finish");
        }
        return blockers.stream().distinct().toList();
    }

    private StaffPropertySnapshot currentSnapshot(RentalDetails listing) {
        List<StaffPropertySnapshot.Media> media = assets.findByListingIdOrderByUploadedAtDesc(listing.getId())
                .stream().map(asset -> new StaffPropertySnapshot.Media(asset.getId(),
                        String.valueOf(asset.getId()), asset.getMediaUrl(), asset.getMediaType(),
                        asset.getRoomTag(), asset.getCaption(), asset.getSortOrder(),
                        Boolean.TRUE.equals(asset.getIsPrimaryCover()), "UPLOADED")).toList();
        return new StaffPropertySnapshot(listing.getTitle(),
                listing.getTitleProvenance() == null ? "LEGACY_UNKNOWN" : listing.getTitleProvenance(),
                listing.getDescription(),
                listing.getPropertyType(), listing.getBhkCount(), listing.getMonthlyRent(),
                listing.getSecurityDeposit(),
                listing.getAvailableFrom() == null ? null : listing.getAvailableFrom().toLocalDate(),
                listing.getMaintenanceCharge(), listing.getBrokerageAmount(), listing.getBrokerageDays(),
                listing.getSecurityDepositMonths(), listing.getTotalAreaSqFt(), listing.getFloorNumber(),
                listing.getTotalFloors(), listing.getFurnishingStatus(), listing.getAmenities(),
                listing.getBathroomCount(), listing.getVastuFacing(), listing.getBachelorAllowed(),
                listing.getPreferredTenant(), listing.getAddress(), listing.getLandmark(),
                listing.getColony(), listing.getPincode(), media);
    }

    private StaffPropertySnapshot.Media draftMediaView(PropertyDraftMedia row) {
        return new StaffPropertySnapshot.Media(row.getSourceMediaAssetId(), row.getMediaId(),
                row.getCloudinaryUrl(), mediaType(row.getContentType()),
                RoomTag.fromStored(row.getRoomTag()), row.getCaption(), row.getSortOrder(),
                Boolean.TRUE.equals(row.getIsCover()), row.getUploadStatus());
    }

    private StaffPropertySnapshot read(PropertyUploadDraft draft) {
        try {
            return mapper.readerFor(StaffPropertySnapshot.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(draft.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Staff revision data could not be read", ex);
        }
    }

    private String write(StaffPropertySnapshot data) {
        try {
            String payload = mapper.writeValueAsString(data);
            if (payload.length() > 64_000) throw new IllegalArgumentException("Revision is too large");
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Staff revision data could not be saved", ex);
        }
    }

    private Map<String, Object> snapshotMap(StaffPropertySnapshot snapshot) {
        return mapper.convertValue(snapshot, new TypeReference<>() {});
    }

    private List<BackofficeDtos.FieldChange> changes(StaffPropertySnapshot before,
                                                      StaffPropertySnapshot after) {
        Map<String, Object> left = snapshotMap(before);
        Map<String, Object> right = snapshotMap(after);
        List<BackofficeDtos.FieldChange> result = new ArrayList<>();
        for (String key : right.keySet()) {
            Object oldValue = left.get(key);
            Object newValue = right.get(key);
            if ("media".equals(key)) {
                if (sameMediaBusinessContent(before.media(), after.media())) continue;
                result.add(new BackofficeDtos.FieldChange(key, label(key),
                        mediaDisplay(before.media()), mediaDisplay(after.media())));
                continue;
            }
            if (!Objects.equals(oldValue, newValue)) {
                result.add(new BackofficeDtos.FieldChange(key, label(key),
                        display(oldValue), display(newValue)));
            }
        }
        return result;
    }

    static boolean sameMediaBusinessContent(List<StaffPropertySnapshot.Media> left,
                                            List<StaffPropertySnapshot.Media> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            StaffPropertySnapshot.Media a = left.get(index);
            StaffPropertySnapshot.Media b = right.get(index);
            Long aIdentity = a.sourceMediaAssetId() != null
                    ? a.sourceMediaAssetId() : numericMediaId(a.mediaId());
            Long bIdentity = b.sourceMediaAssetId() != null
                    ? b.sourceMediaAssetId() : numericMediaId(b.mediaId());
            if (!Objects.equals(aIdentity, bIdentity)
                    || !Objects.equals(a.url(), b.url())
                    || a.mediaType() != b.mediaType()
                    || a.roomTag() != b.roomTag()
                    || !Objects.equals(a.caption(), b.caption())
                    || !Objects.equals(a.sortOrder(), b.sortOrder())
                    || a.cover() != b.cover()
                    || !Objects.equals(a.uploadStatus(), b.uploadStatus())) return false;
        }
        return true;
    }

    private static Long numericMediaId(String mediaId) {
        try {
            return mediaId == null ? null : Long.valueOf(mediaId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String mediaDisplay(List<StaffPropertySnapshot.Media> media) {
        if (media == null || media.isEmpty()) return "No media";
        int cover = 1;
        for (int index = 0; index < media.size(); index++) {
            if (media.get(index).cover()) {
                cover = index + 1;
                break;
            }
        }
        long videos = media.stream().filter(item -> item.mediaType() == MediaType.VIDEO_WALKTHROUGH).count();
        return media.size() + " item(s) · cover position " + cover
                + (videos == 0 ? "" : " · " + videos + " walkthrough video(s)");
    }

    private Map<String, Object> changed(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                result.put(key, Map.of("before", before.get(key) == null ? "" : before.get(key),
                        "after", after.get(key) == null ? "" : after.get(key)));
            }
        }
        return result;
    }

    private void validateBasics(BackofficeDtos.Basics input) {
        if (input == null || input.propertyType() == null
                || input.propertyType() == PropertyType.PLOT || input.propertyType() == PropertyType.LAND
                || input.bhkCount() == null || !BHK.matcher(input.bhkCount()).matches()
                || tooLong(input.title(), 255) || tooLong(input.description(), 4000)) {
            throw new IllegalArgumentException("Check the property title, description and exact configuration");
        }
    }

    private void validateCommercial(BackofficeDtos.Commercial input) {
        if (input == null || input.monthlyRent() == null || input.monthlyRent().signum() <= 0
                || input.securityDeposit() == null || input.securityDeposit().signum() < 0
                || input.availableFrom() == null
                || nonnegative(input.maintenanceCharge()) || nonnegative(input.brokerageAmount())
                || nonnegative(input.brokerageDays()) || nonnegative(input.securityDepositMonths())
                || tooPrecise(input.monthlyRent()) || tooPrecise(input.securityDeposit())
                || tooPrecise(input.maintenanceCharge()) || tooPrecise(input.brokerageAmount())) {
            throw new IllegalArgumentException("Check the rent, deposit and availability details");
        }
    }

    private void validateDetails(BackofficeDtos.Details input) {
        if (input == null || input.totalAreaSqFt() != null
                && (!Double.isFinite(input.totalAreaSqFt()) || input.totalAreaSqFt() <= 0)
                || input.floorNumber() != null && input.floorNumber() < 0
                || input.totalFloors() != null && input.totalFloors() < 1
                || input.bathroomCount() != null && input.bathroomCount() < 0
                || input.floorNumber() != null && input.totalFloors() != null
                && input.floorNumber() > input.totalFloors()
                || input.furnishingStatus() != null && !FURNISHING.contains(input.furnishingStatus())
                || tooLong(input.amenities(), 2000) || tooLong(input.vastuFacing(), 255)
                || tooLong(input.preferredTenant(), 255)) {
            throw new IllegalArgumentException("Check the home details and floor values");
        }
    }

    private void validateAddress(BackofficeDtos.PresentationAddress input) {
        if (input == null || input.address() == null || input.address().isBlank()
                || input.address().trim().length() > 500 || tooLong(input.landmark(), 200)
                || tooLong(input.colony(), 255)
                || input.pincode() != null && !input.pincode().isBlank()
                && !PINCODE.matcher(input.pincode().trim()).matches()) {
            throw new IllegalArgumentException("Check the presentation address and six-digit pincode");
        }
    }

    private void requirePublishableStatus(RentalDetails listing, boolean allowExistingOwnRevision) {
        if (!List.of(ListingWorkflowStatus.PUBLISHED, ListingWorkflowStatus.PAUSED)
                .contains(listing.getWorkflowStatus())) {
            throw new DraftConflictException(listing.getId().toString(), 0,
                    "This property cannot be edited or published in its current state.");
        }
        if (listing.getCanonicalLocalityId() == null) {
            throw new DraftConflictException(listing.getId().toString(), 0,
                    "A confirmed canonical locality is required.");
        }
    }

    private void requireAnyReadAuthority(Long userId, Long cityId) {
        if (!propertyCityIds(userId).contains(cityId)) {
            throw new EntityNotFoundException("Property not found");
        }
        try {
            access.requireRead(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, cityId);
            return;
        } catch (AccessDeniedException ignored) {}
        try {
            access.requireRead(userId, StaffCapability.PROPERTY_AUDIT_READ, cityId);
            return;
        } catch (AccessDeniedException ignored) {}
        access.requireRead(userId, StaffCapability.PROPERTY_LIFECYCLE, cityId);
    }

    private void requireEditRead(Long userId, Long cityId) {
        try {
            access.requireRead(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, cityId);
        } catch (AccessDeniedException denied) {
            if (!propertyCityIds(userId).contains(cityId)) {
                throw new EntityNotFoundException("Property not found");
            }
            throw denied;
        }
    }

    private void requireCommandReadAuthority(Long userId, StaffCapability capability, Long cityId) {
        try {
            access.requireRead(userId, capability, cityId);
        } catch (AccessDeniedException denied) {
            if (!propertyCityIds(userId).contains(cityId)) {
                throw new EntityNotFoundException("Property not found");
            }
            throw denied;
        }
    }

    private BackofficePropertyAccessService.GrantEvidence requireMutationAuthority(
            Long userId, StaffCapability capability, Long cityId) {
        try {
            return access.requireForMutation(userId, capability, cityId);
        } catch (AccessDeniedException denied) {
            if (!propertyCityIds(userId).contains(cityId)) {
                throw new EntityNotFoundException("Property not found");
            }
            throw denied;
        }
    }

    private List<Long> propertyCityIds(Long userId) {
        List<Long> cityIds = new ArrayList<>();
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_EDIT_PUBLISH));
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_AUDIT_READ));
        cityIds.addAll(access.inventoryCityIds(userId, StaffCapability.PROPERTY_LIFECYCLE));
        return cityIds.stream().distinct().toList();
    }

    private PropertyContext propertyContext(Long listingId) {
        RentalDetails listing = listings.findById(listingId)
                .filter(RentalDetails.class::isInstance).map(RentalDetails.class::cast)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (listing.getCanonicalLocalityId() == null) {
            throw new EntityNotFoundException("Property not found");
        }
        Locality locality = localities.findById(listing.getCanonicalLocalityId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (locality.getSupportedCity() == null) {
            throw new EntityNotFoundException("Property not found");
        }
        return new PropertyContext(listing, locality, locality.getSupportedCity().getId(),
                locality.getSupportedCity().getDisplayName(), locality.getSectorName());
    }

    /**
     * A scalar row lock does not refresh an entity already present in the persistence context.
     * Re-read it under the Listing lock before any lifecycle, ownership or version decision.
     */
    private PropertyContext refreshedPropertyContextAfterLock(Long listingId) {
        RentalDetails managed = listings.findById(listingId)
                .filter(RentalDetails.class::isInstance).map(RentalDetails.class::cast)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        entityManager.refresh(managed, LockModeType.PESSIMISTIC_WRITE);
        return propertyContext(listingId);
    }

    private List<PropertyRemovalRequest> pendingRemovalRequests(Long listingId) {
        // This method is populated by the removal service through the shared repository at runtime.
        // It remains a narrow lifecycle guard even before a request exists.
        return removalRequests == null ? List.of()
                : removalRequests.findPendingForUpdate(listingId);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PropertyRemovalRequestRepository removalRequests;

    private void revisionEvent(Long listingId, String draftId, String eventType, Long actorId,
                               Integer draftVersion, Map<String, Object> details) {
        revisionEvents.save(new PropertyRevisionEvent(listingId, draftId, eventType, actorId,
                clock.now(), draftVersion, details));
    }

    private void revisionEvent(Long listingId, String draftId, String eventType, Long actorId,
                               Long draftVersion, Map<String, Object> details) {
        revisionEvent(listingId, draftId, eventType, actorId,
                draftVersion == null ? null : draftVersion.intValue(), details);
    }

    private BackofficeDtos.PublishResult replayPublication(PropertyCommandReceipt receipt, Long userId,
                                                            Long listingId, String draftId,
                                                            String payloadHash) {
        if (!"PROPERTY_PUBLISH".equals(receipt.getCommandType())
                || !listingId.equals(receipt.getAggregateId())
                || !userId.equals(receipt.getActorUserId())
                || !payloadHash.equals(receipt.getPayloadHash())) {
            throw new DraftConflictException(draftId, 0,
                    "This operation ID is already committed with different publication details.");
        }
        PropertyPublicationVersion publication = publications
                .findByListingIdAndPublicationVersion(listingId, receipt.getResultVersion())
                .orElseThrow(() -> new IllegalStateException("Publication receipt is incomplete"));
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        return new BackofficeDtos.PublishResult(listingId, listing.getVersion(),
                publication.getPublicationVersion(), publication.getPublishedAt(),
                listing.getWorkflowStatus());
    }

    private PropertyUploadDraft newDraft(Long userId, RentalDetails listing, StaffPropertySnapshot content) {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("staff-" + UUID.randomUUID());
        draft.setStaffMakerUserId(userId);
        draft.setRevisionMode("STAFF_DIRECT");
        draft.setLessorProfileId(listing.getLessorProfileId());
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setPublishedPropertyId(listing.getId());
        draft.setRevisionBaseVersion(listing.getVersion());
        draft.setTitleSummary(listing.getTitle());
        draft.setItemCount(1);
        draft.setPayload(write(content));
        return drafts.saveAndFlush(draft);
    }

    private static MediaType mediaType(String contentType) {
        return contentType != null && contentType.startsWith("video/") ? MediaType.VIDEO_WALKTHROUGH : MediaType.IMAGE;
    }

    private static boolean nonnegative(Number value) {
        return (value instanceof BigDecimal decimal && decimal.signum() < 0)
                || (value instanceof Integer integer && integer < 0);
    }

    private static boolean tooPrecise(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() > 2;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) { return trimToNull(value); }

    private static String requireReason(String reason) {
        String value = trimToNull(reason);
        if (value == null || value.length() > 500) {
            throw new IllegalArgumentException("A reason of 1 to 500 characters is required");
        }
        return value;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(INDIA).toInstant();
    }

    private static String label(String key) {
        return switch (key) {
            case "media" -> "Media";
            case "monthlyRent" -> "Monthly rent";
            case "securityDeposit" -> "Security deposit";
            case "availableFrom" -> "Available from";
            case "maintenanceCharge" -> "Maintenance";
            case "brokerageAmount" -> "Brokerage amount";
            case "brokerageDays" -> "Brokerage days";
            case "securityDepositMonths" -> "Deposit months";
            case "totalAreaSqFt" -> "Total area";
            case "floorNumber" -> "Floor";
            case "totalFloors" -> "Total floors";
            case "furnishingStatus" -> "Furnishing";
            case "bathroomCount" -> "Bathrooms";
            case "vastuFacing" -> "Facing";
            case "bachelorAllowed" -> "Bachelor allowed";
            case "preferredTenant" -> "Preferred tenant";
            case "bhkCount" -> "Configuration";
            case "propertyType" -> "Property type";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1)
                    .replaceAll("([A-Z])", " $1").toLowerCase(Locale.ROOT);
        };
    }

    private static String display(Object value) {
        if (value == null) return "Not set";
        if (value instanceof List<?> list) return list.size() + " item(s)";
        return String.valueOf(value);
    }

    private static String activitySummary(String eventType) {
        return switch (eventType) {
            case "REVISION_STARTED" -> "Staff revision started";
            case "SECTION_SAVED" -> "Revision section saved";
            case "PUBLISHED" -> "Revision published";
            case "DRAFT_DISCARDED" -> "Staff draft discarded";
            case "STAFF_DRAFT_SUPERSEDED" -> "Staff draft superseded";
            case "LIFECYCLE_PAUSE" -> "Property paused";
            case "LIFECYCLE_ARCHIVE" -> "Property archived";
            default -> "Property activity recorded";
        };
    }

    private record PropertyContext(RentalDetails listing, Locality locality, Long cityId,
                                   String city, String localityName) {}
}

