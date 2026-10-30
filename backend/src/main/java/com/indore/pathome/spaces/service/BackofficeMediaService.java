package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Staff media admission. Provider transfer happens outside database transactions; the draft claim
 * remains durable across loss of the upload acknowledgment and can be recovered by deterministic ID.
 */
@Service
public class BackofficeMediaService {
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final BackofficePropertyAccessService access;
    private final LandlordMediaService fileValidation;
    private final CloudinaryService cloudinary;
    private final TransactionTemplate transactions;

    public BackofficeMediaService(PropertyUploadDraftRepository drafts,
                                  PropertyDraftMediaRepository media,
                                  ListingRepository listings, LocalityRepository localities,
                                  BackofficePropertyAccessService access,
                                  LandlordMediaService fileValidation,
                                  CloudinaryService cloudinary,
                                  PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.media = media;
        this.listings = listings;
        this.localities = localities;
        this.access = access;
        this.fileValidation = fileValidation;
        this.cloudinary = cloudinary;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<BackofficeDtos.NewMedia> list(Long userId, Long listingId, String draftId) {
        return transactions.execute(status -> {
            requireAuthority(userId, listingId, draftId, false, 0);
            return media.findByDraftIdOrderBySortOrderAscIdAsc(draftId).stream()
                    .filter(row -> userId.equals(row.getStaffMakerUserId()))
                    .filter(row -> !"REMOVED_FROM_REVISION".equals(row.getUploadStatus()))
                    .map(this::view).toList();
        });
    }

    public BackofficeDtos.NewMedia upload(Long userId, Long listingId, String draftId,
                                          String rawMediaId, int expectedDraftVersion,
                                          MultipartFile file) {
        String mediaId = requireUuid(rawMediaId);
        String contentType = fileValidation.validateFile(file);
        String filename = safeFilename(file.getOriginalFilename());
        PropertyDraftMedia prepared = transactions.execute(status ->
                prepareUpload(userId, listingId, draftId, expectedDraftVersion, mediaId,
                        filename, contentType, file.getSize()));
        if (prepared == null) throw new IllegalStateException("Media claim could not be prepared");
        if ("UPLOADED".equals(prepared.getUploadStatus())) {
            return transactions.execute(status -> view(prepared));
        }
        String requestId = "staff-" + userId + "-" + mediaId.replace("-", "");
        boolean video = contentType.startsWith("video/");
        try {
            CloudinaryService.CloudinaryUploadResult result = cloudinary
                    .findExistingResourceByUploadRequestId(requestId, video)
                    .orElseGet(() -> video
                            ? cloudinary.uploadVideoResult(file, requestId)
                            : cloudinary.uploadImageResult(file, requestId));
            return transactions.execute(status -> completeUpload(userId, listingId, draftId,
                    mediaId, result));
        } catch (RuntimeException ex) {
            try {
                transactions.executeWithoutResult(status ->
                        markFailed(userId, listingId, draftId, mediaId));
            } catch (RuntimeException secondary) {
                ex.addSuppressed(secondary);
            }
            throw ex;
        }
    }

    public BackofficeDtos.NewMedia recover(Long userId, Long listingId, String draftId,
                                            String rawMediaId, int expectedDraftVersion) {
        String mediaId = requireUuid(rawMediaId);
        PropertyDraftMedia row = transactions.execute(status -> {
            requireAuthority(userId, listingId, draftId, true, expectedDraftVersion);
            return media.findByMediaId(mediaId)
                    .filter(item -> draftId.equals(item.getDraftId()))
                    .filter(item -> userId.equals(item.getStaffMakerUserId()))
                    .orElseThrow(() -> new EntityNotFoundException("Media not found"));
        });
        if (row == null) throw new EntityNotFoundException("Media not found");
        if ("UPLOADED".equals(row.getUploadStatus())) {
            return transactions.execute(status -> view(row));
        }
        boolean video = row.getContentType().startsWith("video/");
        String requestId = "staff-" + userId + "-" + mediaId.replace("-", "");
        CloudinaryService.CloudinaryUploadResult result = cloudinary
                .findExistingResourceByUploadRequestId(requestId, video)
                .orElseThrow(() -> new DraftConflictException(draftId, 0,
                        "The provider has not completed this upload. Choose the original file again."));
        return transactions.execute(status -> completeUpload(userId, listingId, draftId, mediaId, result));
    }

    private PropertyDraftMedia prepareUpload(Long userId, Long listingId, String draftId,
                                             int expectedDraftVersion, String mediaId,
                                             String filename, String contentType, long size) {
        PropertyUploadDraft draft = requireAuthority(
                userId, listingId, draftId, true, expectedDraftVersion);
        Optional<PropertyDraftMedia> existing = media.findByMediaId(mediaId);
        if (existing.isPresent()) {
            PropertyDraftMedia row = existing.get();
            if (!draftId.equals(row.getDraftId())
                    || !userId.equals(row.getStaffMakerUserId())) {
                throw new DraftConflictException(draftId, expectedDraftVersion,
                        "This media ID belongs to another revision.");
            }
            if ("UPLOADED".equals(row.getUploadStatus()) || "PENDING".equals(row.getUploadStatus())) {
                return row;
            }
            row.setUploadStatus("PENDING");
            row.setOriginalFilename(filename);
            row.setContentType(contentType);
            row.setFileSizeBytes(size);
            return media.saveAndFlush(row);
        }
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setMediaId(mediaId);
        row.setDraftId(draftId);
        row.setOriginalFilename(filename);
        row.setContentType(contentType);
        row.setFileSizeBytes(size);
        row.setUploadStatus("PENDING");
        row.setSortOrder(media.findByDraftIdOrderBySortOrderAscIdAsc(draftId).size());
        row.setIsCover(false);
        row.setStaffMakerUserId(draft.getStaffMakerUserId());
        return media.saveAndFlush(row);
    }

    private BackofficeDtos.NewMedia completeUpload(Long userId, Long listingId, String draftId,
                                                    String mediaId,
                                                    CloudinaryService.CloudinaryUploadResult result) {
        requireAuthority(userId, listingId, draftId, true, null);
        PropertyDraftMedia row = media.findByMediaId(mediaId)
                .filter(item -> draftId.equals(item.getDraftId()))
                .filter(item -> userId.equals(item.getStaffMakerUserId()))
                .orElseThrow(() -> new EntityNotFoundException("Media not found"));
        row.setCloudinaryUrl(result.secureUrl());
        row.setCloudinaryPublicId(result.publicId());
        row.setUploadStatus("UPLOADED");
        return view(media.saveAndFlush(row));
    }

    private void markFailed(Long userId, Long listingId, String draftId, String mediaId) {
        requireAuthority(userId, listingId, draftId, true, null);
        media.findByMediaId(mediaId)
                .filter(row -> draftId.equals(row.getDraftId()))
                .filter(row -> userId.equals(row.getStaffMakerUserId()))
                .ifPresent(row -> {
                    row.setUploadStatus("FAILED");
                    media.saveAndFlush(row);
                });
    }

    private PropertyUploadDraft requireAuthority(Long userId, Long listingId, String draftId,
                                                  boolean write, Integer expectedVersion) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (listing.getCanonicalLocalityId() == null) throw new EntityNotFoundException("Revision not found");
        Locality locality = localities.findById(listing.getCanonicalLocalityId())
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (locality.getSupportedCity() == null) throw new EntityNotFoundException("Revision not found");
        Long cityId = locality.getSupportedCity().getId();
        if (write) {
            access.requireForMutation(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, cityId);
            listings.lockForSchedulingById(listingId)
                    .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
            access.revalidate(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, cityId);
        } else {
            access.requireRead(userId, StaffCapability.PROPERTY_EDIT_PUBLISH, cityId);
        }
        PropertyUploadDraft draft = (write ? drafts.findStaffDraftForUpdate(draftId, userId)
                : drafts.findByDraftId(draftId).filter(row -> userId.equals(row.getStaffMakerUserId())))
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
        if (!listingId.equals(draft.getPublishedPropertyId())
                || !"STAFF_DIRECT".equals(draft.getRevisionMode())
                || !"DRAFT".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "This revision is no longer accepting media changes.");
        }
        if (write && expectedVersion != null
                && (draft.getVersion() == null || !expectedVersion.equals(draft.getVersion()))) {
            throw new DraftConflictException(draftId, draft.getVersion(),
                    "The revision changed. Reload before uploading media.");
        }
        if (!userId.equals(draft.getStaffMakerUserId())) {
            throw new AccessDeniedException("This revision belongs to another staff editor.");
        }
        return draft;
    }

    private BackofficeDtos.NewMedia view(PropertyDraftMedia row) {
        return new BackofficeDtos.NewMedia(row.getMediaId(), row.getCloudinaryUrl(),
                row.getContentType() != null && row.getContentType().startsWith("video/")
                        ? MediaType.VIDEO_WALKTHROUGH : MediaType.IMAGE,
                RoomTag.fromStored(row.getRoomTag()), row.getCaption(), row.getSortOrder(),
                Boolean.TRUE.equals(row.getIsCover()), row.getUploadStatus());
    }

    private static String requireUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid media request ID");
        }
    }

    private static String safeFilename(String raw) {
        String name = raw == null ? "property-media" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        return name.isEmpty() ? "property-media" : name.substring(0, Math.min(name.length(), 255));
    }
}

