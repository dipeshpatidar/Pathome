package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordListingAction;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewItem;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewPage;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewDetail;
import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.ListingReviewAuthority.Reviewer;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The landlord listing review and publication workflow.
 *
 * <p>Every method that is part of the approved listing-review authority extension takes the resolved
 * {@link Reviewer} and re-decides that authority from live database facts inside this transaction. The
 * decision is taken at method entry for the reads and, for the guarded mutations, <em>after</em> the
 * listing row lock is held, so a grant revoked between the entry decision and the lock is refused before
 * any state is flushed.
 *
 * <p>The two revision operations that stay {@code ROLE_ADMIN}-only also demand a resolved {@link Reviewer}
 * at this boundary, admitted by the same component, so no entry point into this service can act on a
 * listing or revision without an authenticated authority behind it.
 *
 * <p>The three operations that stay {@code ROLE_ADMIN}-only - requesting listing changes, and approving or
 * refusing a landlord revision - are the <em>only</em> ones this service re-decides by the narrower
 * legacy-administrator rule. That rule is applied here at the service boundary, under the same row lock
 * as every other guarded mutation, so a caller that reaches this service directly with a genuine live
 * {@code STAFF_ADMIN@GLOBAL} verdict is refused exactly as the HTTP rule refuses it. Starting review,
 * linking the canonical locality and publishing are part of the extension and keep the wider rule.
 *
 * <p>Two further guarantees are established here rather than in the authority component.
 *
 * <p><strong>Ordered revocation.</strong> The operations the extension admits hold the actor's authority
 * evidence ({@link ListingReviewAuthority#holdAuthorityEvidence}) <em>before</em> they take the Listing row
 * lock and re-decide the authority <em>after</em> it, so a revocation that commits first is observed and
 * refused, and a revocation that arrives second waits for this transaction to commit. The evidence hold
 * follows the established guard order (actor profile before actor grants, both before the Listing), and
 * every guarded mutation runs under the same transaction-local lock and statement bounds as the rest of
 * the governance surface, so a contended hold fails fast instead of pinning an application thread.
 *
 * <p><strong>Durable attribution.</strong> Every successful mutation writes one immutable
 * {@code operational_audit_events} row in the <em>same</em> transaction and flush boundary as the mutation
 * itself, attributed to the real acting {@code User} resolved from the verified credential the verdict was
 * decided for, against the numeric Listing or draft row id, with the workflow states and optimistic
 * versions either side of the decision and the canonical locality it rests on. A refused or conflicted
 * action writes nothing; nothing is written that did not happen.
 */
@Service
public class LandlordReviewService {
    /** Bounded reason codes for the two review families; the action code carries the precise operation. */
    private static final String LISTING_REVIEW_REASON = "ADMIN_REVIEW_DECISION";
    private static final String REVISION_REVIEW_REASON = "ADMIN_REVISION_DECISION";

    private final ListingRepository listings;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final LandlordDraftService draftService;
    private final LandlordSubmissionService submissions;
    private final LandlordLocationService locations;
    private final ListingWorkflowService workflow;
    private final PropertyMediaAssetRepository assets;
    private final ListingReviewAuthority reviewAuthority;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LessorWorkflowNotificationService workflowNotifications;

    public void setWorkflowNotifications(LessorWorkflowNotificationService workflowNotifications) {
        this.workflowNotifications = workflowNotifications;
    }

    public LandlordReviewService(ListingRepository listings, PropertyUploadDraftRepository drafts,
                                 PropertyDraftMediaRepository media, LandlordDraftService draftService,
                                 LandlordSubmissionService submissions, LandlordLocationService locations,
                                 ListingWorkflowService workflow, PropertyMediaAssetRepository assets,
                                 ListingReviewAuthority reviewAuthority, OperationalAuditService audit,
                                 TransactionalCommandBounds commandBounds) {
        this.listings = listings;
        this.drafts = drafts;
        this.media = media;
        this.draftService = draftService;
        this.submissions = submissions;
        this.locations = locations;
        this.workflow = workflow;
        this.assets = assets;
        this.reviewAuthority = reviewAuthority;
        this.audit = audit;
        this.commandBounds = commandBounds;
    }

    @Transactional(readOnly = true)
    public LandlordReviewPage revisions(int page, Reviewer reviewer) {
        reviewAuthority.revalidate(reviewer);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        var slice = drafts.findByStatusAndPublishedPropertyIdIsNotNullAndLandlordUserIdIsNotNullOrderByUpdatedAtDescIdDesc(
                "REVIEW", PageRequest.of(page, 20));
        return new LandlordReviewPage(slice.getContent().stream().map(draft ->
                new LandlordReviewItem(draft.getDraftId(), draft.getPublishedPropertyId(),
                        draft.getTitleSummary(), draft.getStatus(), draft.getUpdatedAt())).toList(), page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public LandlordReviewPage listings(int page, Reviewer reviewer) {
        reviewAuthority.revalidate(reviewer);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        var slice = listings.findLandlordReviewQueue(
                List.of(ListingWorkflowStatus.SUBMITTED, ListingWorkflowStatus.UNDER_REVIEW), PageRequest.of(page, 20));
        return new LandlordReviewPage(slice.getContent().stream().map(listing ->
                new LandlordReviewItem(null, listing.getId(), listing.getTitle(),
                        listing.getWorkflowStatus().name(), listing.getUpdatedAt())).toList(), page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public LandlordReviewDetail listingDetail(Long listingId, Reviewer reviewer) {
        reviewAuthority.revalidate(reviewer);
        RentalDetails listing = listings.findById(listingId)
                .filter(row -> row.getOwnerUserId() != null && row instanceof RentalDetails)
                .map(RentalDetails.class::cast)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        var data = new LandlordDraftData(
                new LandlordDraftData.Basics(listing.getPropertyType(),
                        listing.getRentalMode(), listing.getBhkCount()),
                new LandlordDraftData.Pricing(listing.getMonthlyRent(), listing.getSecurityDeposit()),
                new LandlordDraftData.Location(listing.getCity(),
                        listing.getCanonicalLocalityId(), listing.getSector(), listing.getAddress(), listing.getLandmark(),
                        listing.getLocationResolution(), listing.getLocationProvider(), listing.getLocationProviderPlaceId(), null),
                new LandlordDraftData.Details(
                        listing.getAvailableFrom() == null ? null : listing.getAvailableFrom().toLocalDate(),
                        listing.getFurnishingStatus(), listing.getTotalAreaSqFt(), listing.getFloorNumber(),
                        listing.getTotalFloors(), listing.getAmenities(), listing.getDescription()));
        var photos = assets.findByListingIdOrderByUploadedAtDesc(listingId).stream()
                .map(asset -> new LandlordMediaItem(String.valueOf(asset.getId()), null,
                        asset.getMediaType() == MediaType.IMAGE ? "image/*" : "video/*",
                        asset.getMediaUrl(), "UPLOADED", Boolean.TRUE.equals(asset.getIsPrimaryCover()), 0,
                        asset.getRoomTag()))
                .toList();
        return new LandlordReviewDetail(null, listingId, listing.getWorkflowStatus().name(),
                listing.getReviewNote(), data, photos);
    }

    @Transactional(readOnly = true)
    public LandlordReviewDetail revisionDetail(String draftId, Reviewer reviewer) {
        reviewAuthority.revalidate(reviewer);
        PropertyUploadDraft draft = drafts.findByDraftId(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null
                        && List.of("REVIEW", "CHANGES_REQUIRED", "APPROVED").contains(row.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        var rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, draft.getLandlordUserId());
        var items = rows.stream().map(row -> new LandlordMediaItem(row.getMediaId(), row.getOriginalFilename(),
                row.getContentType(), row.getCloudinaryUrl(), row.getUploadStatus(),
                Boolean.TRUE.equals(row.getIsCover()), row.getSortOrder() == null ? 0 : row.getSortOrder(),
                RoomTag.fromStored(row.getRoomTag()))).toList();
        return new LandlordReviewDetail(draftId, draft.getPublishedPropertyId(), draft.getStatus(),
                draft.getReviewNote(), draftService.readData(draft), items);
    }

    @Transactional
    public LandlordListingAction decideListing(Long listingId, ListingWorkflowStatus target, String note,
                                               Reviewer reviewer) {
        boolean legacyOnly = target == ListingWorkflowStatus.CHANGES_REQUIRED;
        commandBounds.apply();
        if (!legacyOnly) {
            // Held before the Listing lock, so a revocation cannot commit between this decision and its own
            // commit; the request-changes operation is not part of the extension and holds no staff evidence.
            reviewAuthority.holdAuthorityEvidence(reviewer);
        }
        listings.lockLandlordReviewId(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        // Decided under the listing row lock, so a grant revoked between the entry decision and this
        // lock is refused here, before anything below can flush a mutation. Requesting listing changes is
        // not part of the listing-review extension, so it is re-decided by the narrower legacy rule and a
        // staff verdict is refused even when it is current and backed by a live grant.
        if (legacyOnly) {
            reviewAuthority.revalidateLegacyAdministrator(reviewer);
        } else {
            reviewAuthority.revalidate(reviewer);
        }
        User actor = reviewAuthority.attributableActor(reviewer);
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        String reviewNote = target == ListingWorkflowStatus.CHANGES_REQUIRED ? requireNote(note) : null;
        if (target == ListingWorkflowStatus.PUBLISHED && listing.getOwnerUserId() != null) {
            if (listing.getCanonicalLocalityId() == null) throw new IllegalStateException("Resolve the locality before publishing");
            locations.requireMatchingLocality(listing.getCity(), listing.getCanonicalLocalityId());
        }
        ListingWorkflowStatus fromStatus = listing.getWorkflowStatus();
        long previousVersion = versionOf(listing);
        try { workflow.transition(listing, target, ListingWorkflowService.Actor.ADMIN); }
        catch (IllegalStateException ex) {
            throw new DraftConflictException(String.valueOf(listingId), 0, "Property status changed. Reload the review queue.");
        }
        listing.setReviewNote(reviewNote);
        Listing saved = listings.saveAndFlush(listing);
        audit.recordUserEvent(actor, listingActionCode(target), "LISTING", saved.getId(), LISTING_REVIEW_REASON,
                null, null, listingDecisionDetails(fromStatus, saved, previousVersion));
        if (workflowNotifications != null) {
            if (target == ListingWorkflowStatus.UNDER_REVIEW) {
                workflowNotifications.notifyReviewStarted(saved);
            } else if (target == ListingWorkflowStatus.CHANGES_REQUIRED) {
                workflowNotifications.notifyChangesRequired(saved);
            } else if (target == ListingWorkflowStatus.PUBLISHED) {
                workflowNotifications.notifyPropertyPublished(saved);
            }
        }
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    /** Admin confirmation links a submitted location to a pre-existing canonical locality. */
    @Transactional
    public LandlordListingAction linkLocality(Long listingId, Long localityId, Reviewer reviewer) {
        commandBounds.apply();
        reviewAuthority.holdAuthorityEvidence(reviewer);
        listings.lockLandlordReviewId(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        reviewAuthority.revalidate(reviewer);
        User actor = reviewAuthority.attributableActor(reviewer);
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (listing.getOwnerUserId() == null || !List.of(ListingWorkflowStatus.SUBMITTED,
                ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowStatus.CHANGES_REQUIRED).contains(listing.getWorkflowStatus())) {
            throw new IllegalStateException("Location can only be linked during landlord review");
        }
        Locality locality = locations.requireMatchingLocality(listing.getCity(), localityId);
        // A listing is linked to one canonical locality for good. Repeating the same link is the documented
        // retry and still succeeds below; replacing it with a different locality would rewrite location
        // evidence that review and publication already rest on, so it is refused before anything is flushed.
        Long linkedLocalityId = listing.getCanonicalLocalityId();
        if (linkedLocalityId != null && !linkedLocalityId.equals(locality.getId())) {
            throw new DraftConflictException(String.valueOf(listingId), 0,
                    "This property is already linked to a different locality. Reload the listing to see the "
                            + "locality it is linked to.");
        }
        long previousVersion = versionOf(listing);
        String previousName = listing.getSector();
        String linkedTitle = listing.getTitle();
        if (linkedTitle != null && previousName != null && linkedTitle.endsWith(" in " + previousName)) {
            linkedTitle = linkedTitle.substring(0, linkedTitle.length() - previousName.length())
                    + locality.getSectorName();
        }
        boolean listingChanged = !Objects.equals(linkedLocalityId, locality.getId())
                || !Objects.equals(previousName, locality.getSectorName())
                || !Objects.equals(listing.getTitle(), linkedTitle)
                || listing.getLocationResolution() != LocationResolution.CANONICAL
                || listing.getLocationProvider() != null || listing.getLocationProviderPlaceId() != null;
        var changedMedia = assets.findByListingIdOrderByUploadedAtDesc(listingId).stream()
                .filter(asset -> !Objects.equals(asset.getCity(), locality.getCity())
                        || !Objects.equals(asset.getSector(), locality.getSectorName()))
                .toList();
        if (!listingChanged && changedMedia.isEmpty()) {
            // A successful exact retry is not another mutation. Keep its version and immutable audit intact.
            return new LandlordListingAction(listing.getId(), listing.getWorkflowStatus(), listing.getVersion());
        }

        listing.setCanonicalLocalityId(locality.getId());
        listing.setSector(locality.getSectorName());
        listing.setTitle(linkedTitle);
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLocationProvider(null);
        listing.setLocationProviderPlaceId(null);
        changedMedia.forEach(asset -> {
            asset.setCity(locality.getCity());
            asset.setSector(locality.getSectorName());
        });
        if (!changedMedia.isEmpty()) assets.saveAll(changedMedia);
        Listing saved = listings.saveAndFlush(listing);
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("canonicalLocalityId", locality.getId());
        if (linkedLocalityId != null) linked.put("previousCanonicalLocalityId", linkedLocalityId);
        linked.put("previousVersion", previousVersion);
        linked.put("resultingVersion", versionOf(saved));
        audit.recordUserEvent(actor, "LISTING_LOCALITY_LINKED", "LISTING", saved.getId(), LISTING_REVIEW_REASON,
                null, null, linked);
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    @Transactional
    public LandlordListingAction approveRevision(String draftId, Reviewer reviewer) {
        commandBounds.apply();
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!"REVIEW".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "Revision is no longer awaiting review.");
        }
        listings.lockOwnedId(draft.getPublishedPropertyId(), draft.getLandlordUserId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        // Decided under the owner-scoped property lock, before any revision state can be flushed. Approving
        // a revision stays ROLE_ADMIN-only, so the narrower legacy rule applies here too.
        reviewAuthority.revalidateLegacyAdministrator(reviewer);
        User actor = reviewAuthority.attributableActor(reviewer);
        Listing owned = listings.findByIdAndOwnerUserId(draft.getPublishedPropertyId(), draft.getLandlordUserId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (!(owned instanceof RentalDetails listing) || draft.getRevisionBaseVersion() == null
                || !draft.getRevisionBaseVersion().equals(owned.getVersion())
                || !List.of(ListingWorkflowStatus.PUBLISHED, ListingWorkflowStatus.PAUSED).contains(owned.getWorkflowStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "Approved property changed after this revision began. It needs a fresh review.");
        }
        var data = draftService.readData(draft);
        var rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, draft.getLandlordUserId());
        submissions.requireCompleteRevision(data, rows);
        Locality locality = locations.requireMatchingLocality(data.location().city(), data.location().canonicalLocalityId());
        long previousVersion = versionOf(owned);
        submissions.applyRevision(listing, data, rows, locality);
        Listing saved = listings.saveAndFlush(listing);
        draft.setStatus("APPROVED");
        draft.setReviewNote(null);
        drafts.save(draft);
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("fromStatus", "REVIEW");
        approved.put("toStatus", "APPROVED");
        approved.put("canonicalLocalityId", locality.getId());
        if (draft.getRevisionBaseVersion() != null) {
            approved.put("revisionBaseVersion", draft.getRevisionBaseVersion());
        }
        approved.put("previousVersion", previousVersion);
        approved.put("resultingVersion", versionOf(saved));
        audit.recordUserEvent(actor, "REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT", draft.getId(),
                REVISION_REVIEW_REASON, null, null, approved);
        if (workflowNotifications != null) {
            workflowNotifications.notifyRevisionPublished(saved, draftId);
        }
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    @Transactional
    public LandlordReviewItem requestRevisionChanges(String draftId, String note, Reviewer reviewer) {
        commandBounds.apply();
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!"REVIEW".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "Revision is no longer awaiting review.");
        }
        // Decided under the locked draft row, before any revision state can be flushed. Requesting revision
        // changes stays ROLE_ADMIN-only, so the narrower legacy rule applies here too.
        reviewAuthority.revalidateLegacyAdministrator(reviewer);
        User actor = reviewAuthority.attributableActor(reviewer);
        long previousDraftVersion = draft.getVersion() == null ? 0L : draft.getVersion();
        draft.setReviewNote(requireNote(note));
        draft.setStatus("CHANGES_REQUIRED");
        PropertyUploadDraft savedDraft = drafts.saveAndFlush(draft);
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("fromStatus", "REVIEW");
        requested.put("toStatus", "CHANGES_REQUIRED");
        requested.put("previousVersion", previousDraftVersion);
        requested.put("resultingVersion",
                savedDraft.getVersion() == null ? previousDraftVersion : savedDraft.getVersion());
        audit.recordUserEvent(actor, "REVISION_CHANGES_REQUESTED", "PROPERTY_UPLOAD_DRAFT", savedDraft.getId(),
                REVISION_REVIEW_REASON, null, null, requested);
        if (workflowNotifications != null) {
            listings.findById(draft.getPublishedPropertyId()).ifPresent(owned -> {
                workflowNotifications.notifyRevisionChangesRequired(owned, savedDraft);
            });
        }
        return new LandlordReviewItem(draftId, draft.getPublishedPropertyId(),
                draft.getTitleSummary(), draft.getStatus(), draft.getUpdatedAt());
    }

    private String requireNote(String note) {
        if (note == null || note.trim().length() < 10 || note.trim().length() > 1000) {
            throw new IllegalArgumentException("A review note of 10 to 1000 characters is required");
        }
        return note.trim();
    }

    /**
     * The immutable audit action code for a successful listing-review decision. A target this service
     * cannot reach - the workflow refuses every other transition for an administrator - has no action
     * code, and saying so is better than recording a decision under a code that does not describe it.
     */
    private static String listingActionCode(ListingWorkflowStatus target) {
        return switch (target) {
            case UNDER_REVIEW -> "LISTING_REVIEW_STARTED";
            case PUBLISHED -> "LISTING_PUBLISHED";
            case CHANGES_REQUIRED -> "LISTING_CHANGES_REQUESTED";
            default -> throw new IllegalStateException("No review audit action is defined for " + target);
        };
    }

    /**
     * The bounded, factual evidence of one listing decision: the workflow state either side of it, the
     * optimistic version before and after it, and - for a publication - the canonical locality the
     * publication rests on. Only non-null evidence is recorded, and no free text (such as the review note,
     * which lives on the listing itself) enters the audit details.
     */
    private static Map<String, Object> listingDecisionDetails(ListingWorkflowStatus fromStatus, Listing saved,
                                                              long previousVersion) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (fromStatus != null) details.put("fromStatus", fromStatus.name());
        details.put("toStatus", saved.getWorkflowStatus().name());
        if (saved.getWorkflowStatus() == ListingWorkflowStatus.PUBLISHED
                && saved.getCanonicalLocalityId() != null) {
            details.put("canonicalLocalityId", saved.getCanonicalLocalityId());
        }
        details.put("previousVersion", previousVersion);
        details.put("resultingVersion", versionOf(saved));
        return details;
    }

    /** The persisted optimistic version, read as a number so the audit never records a null version. */
    private static long versionOf(Listing listing) {
        return listing.getVersion() == null ? 0L : listing.getVersion();
    }
}
