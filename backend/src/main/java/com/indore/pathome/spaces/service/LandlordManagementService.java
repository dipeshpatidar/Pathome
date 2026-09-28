package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.dto.lessor.LandlordListingAction;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LandlordManagementService {
    private final LandlordCapabilityService capabilities;
    private final ListingRepository listings;
    private final ListingWorkflowService workflow;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final PropertyMediaAssetRepository assets;
    private final LandlordDraftService draftService;
    private final ObjectMapper mapper;

    public LandlordManagementService(LandlordCapabilityService capabilities, ListingRepository listings,
                                     ListingWorkflowService workflow, PropertyUploadDraftRepository drafts,
                                     PropertyDraftMediaRepository media, PropertyMediaAssetRepository assets,
                                     LandlordDraftService draftService, ObjectMapper mapper) {
        this.capabilities = capabilities;
        this.listings = listings;
        this.workflow = workflow;
        this.drafts = drafts;
        this.media = media;
        this.assets = assets;
        this.draftService = draftService;
        this.mapper = mapper;
    }

    @Transactional
    public LandlordListingAction changeStatus(String email, Long listingId, Long expectedVersion,
                                              ListingWorkflowStatus target) {
        Long ownerId = capabilities.requireLandlordUserId(email);
        Listing listing = requireOwnedForUpdate(listingId, ownerId);
        if (expectedVersion == null || !expectedVersion.equals(listing.getVersion())) {
            throw new DraftConflictException(String.valueOf(listingId), 0, "Property changed in another session. Reload before trying again.");
        }
        if (target != ListingWorkflowStatus.PAUSED && target != ListingWorkflowStatus.ARCHIVED
                && target != ListingWorkflowStatus.SUBMITTED) throw new IllegalArgumentException("Action is not supported");
        try { workflow.transition(listing, target, ListingWorkflowService.Actor.LANDLORD); }
        catch (IllegalStateException ex) {
            throw new DraftConflictException(String.valueOf(listingId), 0, "This action is unavailable for the current property status.");
        }
        Listing saved = listings.saveAndFlush(listing);
        return new LandlordListingAction(saved.getId(), saved.getWorkflowStatus(), saved.getVersion());
    }

    @Transactional
    public LandlordDraftResponse startRevision(String email, Long listingId) {
        Long ownerId = capabilities.requireLandlordUserId(email);
        Listing owned = requireOwnedForUpdate(listingId, ownerId);
        if (!(owned instanceof RentalDetails listing) ||
                !List.of(ListingWorkflowStatus.PUBLISHED, ListingWorkflowStatus.PAUSED,
                        ListingWorkflowStatus.CHANGES_REQUIRED).contains(owned.getWorkflowStatus())) {
            throw new DraftConflictException(String.valueOf(listingId), 0, "This property cannot be revised in its current state.");
        }
        var existing = drafts.findFirstByPublishedPropertyIdAndLandlordUserIdAndStatusInOrderByIdDesc(
                listingId, ownerId, List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED"));
        String carriedReviewNote = listing.getReviewNote();
        if (existing.isPresent()) {
            PropertyUploadDraft open = existing.get();
            if ("CHANGES_REQUIRED".equals(open.getStatus())) {
                if (open.getRevisionBaseVersion() != null && open.getRevisionBaseVersion().equals(listing.getVersion())) {
                    open.setStatus("DRAFT");
                    drafts.saveAndFlush(open);
                    return draftService.get(email, open.getDraftId());
                }
                carriedReviewNote = open.getReviewNote();
                open.setStatus("SUPERSEDED");
                drafts.saveAndFlush(open);
            } else if ("REVIEW".equals(open.getStatus())) {
                throw new DraftConflictException(open.getDraftId(), open.getVersion(),
                        "A revision is already awaiting review.");
            } else return draftService.get(email, open.getDraftId());
        }
        if (listing.getCanonicalLocalityId() == null) throw new DraftConflictException(String.valueOf(listingId), 0,
                "This property needs a confirmed locality before revision.");
        var data = new LandlordDraftData(
                new LandlordDraftData.Basics(listing.getPropertyType(), RentalMode.LONG_TERM_RENTAL, listing.getBhkCount()),
                new LandlordDraftData.Pricing(listing.getMonthlyRent(), listing.getSecurityDeposit()),
                new LandlordDraftData.Location(listing.getCity(), listing.getCanonicalLocalityId(),
                        listing.getSector(), listing.getAddress(), listing.getLandmark()),
                new LandlordDraftData.Details(listing.getAvailableFrom() == null ? null : listing.getAvailableFrom().toLocalDate(),
                        listing.getFurnishingStatus(), listing.getTotalAreaSqFt(), listing.getFloorNumber(),
                        listing.getTotalFloors(), listing.getAmenities(), listing.getDescription()));
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("landlord-" + UUID.randomUUID());
        draft.setLandlordUserId(ownerId);
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setPublishedPropertyId(listingId);
        draft.setRevisionBaseVersion(listing.getVersion());
        draft.setTitleSummary(listing.getTitle());
        draft.setReviewNote(carriedReviewNote);
        draft.setItemCount(1);
        try { draft.setPayload(mapper.writeValueAsString(data)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Revision could not be prepared"); }
        drafts.saveAndFlush(draft);
        int order = 0;
        for (PropertyMediaAsset asset : assets.findByListingIdOrderByUploadedAtDesc(listingId)) {
            PropertyDraftMedia row = new PropertyDraftMedia();
            row.setMediaId(UUID.randomUUID().toString());
            row.setDraftId(draft.getDraftId());
            row.setLandlordUserId(ownerId);
            row.setOriginalFilename("Existing property media");
            row.setContentType(asset.getMediaType() == MediaType.IMAGE ? "image/jpeg" : "video/mp4");
            row.setCloudinaryUrl(asset.getMediaUrl());
            row.setCloudinaryPublicId(asset.getCloudinaryPublicId());
            row.setUploadStatus("UPLOADED");
            row.setSortOrder(order++);
            row.setIsCover(Boolean.TRUE.equals(asset.getIsPrimaryCover()) && asset.getMediaType() == MediaType.IMAGE);
            row.setReusedFromListing(true);
            media.save(row);
        }
        media.flush();
        return draftService.get(email, draft.getDraftId());
    }

    private Listing requireOwnedForUpdate(Long listingId, Long ownerId) {
        listings.lockOwnedId(listingId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        return listings.findByIdAndOwnerUserId(listingId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
    }
}
