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
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LandlordReviewService {
    private final ListingRepository listings;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final LandlordDraftService draftService;
    private final LandlordSubmissionService submissions;
    private final LandlordLocationService locations;
    private final ListingWorkflowService workflow;
    private final PropertyMediaAssetRepository assets;

    public LandlordReviewService(ListingRepository listings, PropertyUploadDraftRepository drafts,
                                 PropertyDraftMediaRepository media, LandlordDraftService draftService,
                                 LandlordSubmissionService submissions, LandlordLocationService locations,
                                 ListingWorkflowService workflow, PropertyMediaAssetRepository assets) {
        this.listings = listings;
        this.drafts = drafts;
        this.media = media;
        this.draftService = draftService;
        this.submissions = submissions;
        this.locations = locations;
        this.workflow = workflow;
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public LandlordReviewPage revisions(int page) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        var slice = drafts.findByStatusAndPublishedPropertyIdIsNotNullAndLandlordUserIdIsNotNullOrderByUpdatedAtDescIdDesc(
                "REVIEW", PageRequest.of(page, 20));
        return new LandlordReviewPage(slice.getContent().stream().map(draft ->
                new LandlordReviewItem(draft.getDraftId(), draft.getPublishedPropertyId(),
                        draft.getTitleSummary(), draft.getStatus(), draft.getUpdatedAt())).toList(), page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public LandlordReviewPage listings(int page) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        var slice = listings.findLandlordReviewQueue(
                List.of(ListingWorkflowStatus.SUBMITTED, ListingWorkflowStatus.UNDER_REVIEW), PageRequest.of(page, 20));
        return new LandlordReviewPage(slice.getContent().stream().map(listing ->
                new LandlordReviewItem(null, listing.getId(), listing.getTitle(),
                        listing.getWorkflowStatus().name(), listing.getUpdatedAt())).toList(), page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public LandlordReviewDetail listingDetail(Long listingId) {
        RentalDetails listing = listings.findById(listingId)
                .filter(row -> row.getOwnerUserId() != null && row instanceof RentalDetails)
                .map(RentalDetails.class::cast)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        var data = new LandlordDraftData(
                new LandlordDraftData.Basics(listing.getPropertyType(),
                        listing.getRentalMode(), listing.getBhkCount()),
                new LandlordDraftData.Pricing(listing.getMonthlyRent(), listing.getSecurityDeposit()),
                new LandlordDraftData.Location(listing.getCity(),
                        listing.getCanonicalLocalityId(), listing.getSector(), listing.getAddress(), listing.getLandmark()),
                new LandlordDraftData.Details(
                        listing.getAvailableFrom() == null ? null : listing.getAvailableFrom().toLocalDate(),
                        listing.getFurnishingStatus(), listing.getTotalAreaSqFt(), listing.getFloorNumber(),
                        listing.getTotalFloors(), listing.getAmenities(), listing.getDescription()));
        var photos = assets.findByListingIdOrderByUploadedAtDesc(listingId).stream()
                .map(asset -> new LandlordMediaItem(String.valueOf(asset.getId()), null,
                        asset.getMediaType() == MediaType.IMAGE ? "image/*" : "video/*",
                        asset.getMediaUrl(), "UPLOADED", Boolean.TRUE.equals(asset.getIsPrimaryCover()), 0))
                .toList();
        return new LandlordReviewDetail(null, listingId, listing.getWorkflowStatus().name(),
                listing.getReviewNote(), data, photos);
    }

    @Transactional(readOnly = true)
    public LandlordReviewDetail revisionDetail(String draftId) {
        PropertyUploadDraft draft = drafts.findByDraftId(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null
                        && List.of("REVIEW", "CHANGES_REQUIRED", "APPROVED").contains(row.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        var rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, draft.getLandlordUserId());
        var items = rows.stream().map(row -> new LandlordMediaItem(row.getMediaId(), row.getOriginalFilename(),
                row.getContentType(), row.getCloudinaryUrl(), row.getUploadStatus(),
                Boolean.TRUE.equals(row.getIsCover()), row.getSortOrder() == null ? 0 : row.getSortOrder())).toList();
        return new LandlordReviewDetail(draftId, draft.getPublishedPropertyId(), draft.getStatus(),
                draft.getReviewNote(), draftService.readData(draft), items);
    }

    @Transactional
    public LandlordListingAction decideListing(Long listingId, ListingWorkflowStatus target, String note) {
        listings.lockLandlordReviewId(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        String reviewNote = target == ListingWorkflowStatus.CHANGES_REQUIRED ? requireNote(note) : null;
        try { workflow.transition(listing, target, ListingWorkflowService.Actor.ADMIN); }
        catch (IllegalStateException ex) {
            throw new DraftConflictException(String.valueOf(listingId), 0, "Property status changed. Reload the review queue.");
        }
        listing.setReviewNote(reviewNote);
        Listing saved = listings.saveAndFlush(listing);
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    @Transactional
    public LandlordListingAction approveRevision(String draftId) {
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!"REVIEW".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "Revision is no longer awaiting review.");
        }
        listings.lockOwnedId(draft.getPublishedPropertyId(), draft.getLandlordUserId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
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
        submissions.applyRevision(listing, data, rows, locality);
        Listing saved = listings.saveAndFlush(listing);
        draft.setStatus("APPROVED");
        draft.setReviewNote(null);
        drafts.save(draft);
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    @Transactional
    public LandlordReviewItem requestRevisionChanges(String draftId, String note) {
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId)
                .filter(row -> row.getLandlordUserId() != null && row.getPublishedPropertyId() != null)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!"REVIEW".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(), "Revision is no longer awaiting review.");
        }
        draft.setReviewNote(requireNote(note));
        draft.setStatus("CHANGES_REQUIRED");
        drafts.saveAndFlush(draft);
        return new LandlordReviewItem(draftId, draft.getPublishedPropertyId(),
                draft.getTitleSummary(), draft.getStatus(), draft.getUpdatedAt());
    }

    private String requireNote(String note) {
        if (note == null || note.trim().length() < 10 || note.trim().length() > 1000) {
            throw new IllegalArgumentException("A review note of 10 to 1000 characters is required");
        }
        return note.trim();
    }
}
