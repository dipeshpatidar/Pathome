package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.dto.lessor.LandlordPreview;
import com.indore.pathome.spaces.dto.lessor.LandlordSubmission;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
public class LandlordSubmissionService {
    private static final Pattern BHK = Pattern.compile("^(?:1RK|[1-9][0-9]?BHK)$");
    private final LandlordCapabilityService capabilities;
    private final LandlordDraftService draftData;
    private final LandlordLocationService locations;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final ListingRepository listings;
    private final PropertyMediaAssetRepository assets;
    private final UserRepository users;
    private final ListingWorkflowService workflow;
    private final LessorProfileService lessorProfiles;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LessorWorkflowNotificationService workflowNotifications;

    public void setWorkflowNotifications(LessorWorkflowNotificationService workflowNotifications) {
        this.workflowNotifications = workflowNotifications;
    }

    public LandlordSubmissionService(LandlordCapabilityService capabilities, LandlordDraftService draftData,
                                     LandlordLocationService locations, PropertyUploadDraftRepository drafts,
                                     PropertyDraftMediaRepository media, ListingRepository listings,
                                     PropertyMediaAssetRepository assets, UserRepository users,
                                     ListingWorkflowService workflow,
                                     LessorProfileService lessorProfiles) {
        this.capabilities = capabilities;
        this.draftData = draftData;
        this.locations = locations;
        this.drafts = drafts;
        this.media = media;
        this.listings = listings;
        this.assets = assets;
        this.users = users;
        this.workflow = workflow;
        this.lessorProfiles = lessorProfiles;
    }

    @Transactional(readOnly = true)
    public LandlordPreview preview(String email, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        PropertyUploadDraft draft = drafts.findByDraftIdAndLandlordUserId(draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
        List<PropertyDraftMedia> rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
        return buildPreview(draft, rows);
    }

    public LandlordPreview previewGuest(PropertyUploadDraft draft, List<PropertyDraftMedia> rows) {
        return buildPreview(draft, rows);
    }

    private LandlordPreview buildPreview(PropertyUploadDraft draft, List<PropertyDraftMedia> rows) {
        LandlordDraftData data = draftData.readData(draft);
        String localityName = locationName(data.location());
        return new LandlordPreview(title(data, localityName),
                data.basics() == null ? null : data.basics().propertyType(),
                data.basics() == null ? null : data.basics().bhkCount(),
                data.location() == null ? null : data.location().city(), localityName,
                data.pricing() == null ? null : data.pricing().monthlyRent(),
                data.pricing() == null ? null : data.pricing().securityDeposit(),
                data.details() == null ? null : data.details().availableFrom(),
                data.details() == null ? null : data.details().furnishingStatus(),
                data.details() == null ? null : data.details().totalAreaSqFt(),
                data.details() == null ? null : data.details().description(),
                rows.stream().filter(row -> "UPLOADED".equals(row.getUploadStatus()) || "STAGED".equals(row.getUploadStatus()))
                        .map(row -> new LandlordMediaItem(row.getMediaId(), row.getOriginalFilename(), row.getContentType(),
                                row.getGuestOwned() ? "/api/v1/lessor/guest/drafts/" + draft.getDraftId() + "/media/" + row.getMediaId() + "/content"
                                        : "STAGED".equals(row.getUploadStatus()) && row.getStagingObjectKey() != null
                                            ? "/api/v1/lessor/properties/drafts/" + draft.getDraftId() + "/media/" + row.getMediaId() + "/content"
                                            : row.getCloudinaryUrl(), row.getUploadStatus(), Boolean.TRUE.equals(row.getIsCover()),
                                row.getSortOrder() == null ? 0 : row.getSortOrder(),
                                RoomTag.fromStored(row.getRoomTag()))).toList(),
                missing(data, rows, localityName != null));
    }

    @Transactional
    public LandlordSubmission submit(String email, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        PropertyUploadDraft draft = drafts.findLandlordDraftForUpdate(draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
        if (!"DRAFT".equals(draft.getStatus())) {
            if (draft.getPublishedPropertyId() != null &&
                    ("REVIEW".equals(draft.getStatus()) || "SUBMITTED".equals(draft.getStatus()))) {
                Listing existing = listings.findByIdAndOwnerUserId(draft.getPublishedPropertyId(), ownerId)
                        .orElseThrow(() -> new EntityNotFoundException("Property not found"));
                return new LandlordSubmission(existing.getId(), draftId, existing.getTitle(),
                        ListingWorkflowStatus.SUBMITTED, draft.getUpdatedAt());
            }
            Listing existing = listings.findByOriginDraftId(draftId)
                    .filter(listing -> ownerId.equals(listing.getOwnerUserId()))
                    .orElseThrow(() -> new DraftConflictException(draftId, draft.getVersion(), "Draft is no longer editable"));
            return response(existing, draftId);
        }
        LandlordDraftData data = draftData.readData(draft);
        List<PropertyDraftMedia> rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
        List<String> missing = missing(data, rows, locationName(data.location()) != null);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Complete before submitting: " + String.join(", ", missing));
        if (rows.stream().anyMatch(row -> "PENDING".equals(row.getUploadStatus()) || "DELETING".equals(row.getUploadStatus()))) {
            throw new DraftConflictException(draftId, draft.getVersion(), "Wait for media uploads or removals to finish");
        }
        User owner = users.findById(ownerId).orElseThrow(() -> new EntityNotFoundException("Account not found"));
        LessorProfile existingProfile = lessorProfiles.getProfileForUser(ownerId).orElse(null);
        String contactName = existingProfile == null ? owner.getFullName() : existingProfile.getDisplayName();
        String contactPhone = existingProfile == null ? owner.getPhoneNumber() : existingProfile.getMobileNumber();
        if (!LandlordContactService.isUsableName(contactName) || !LandlordContactService.isUsablePhone(contactPhone)) {
            throw new IllegalArgumentException("Complete your contact details (full name and mobile number) before submitting for review");
        }
        LessorProfile profile = existingProfile == null ? lessorProfiles.getOrCreateProfileForUser(owner) : existingProfile;
        draft.setLessorProfileId(profile.getId());
        Locality locality = data.location().canonicalLocalityId() == null ? null :
                locations.requireMatchingLocality(data.location().city(), data.location().canonicalLocalityId());
        String selectedName = locality == null ? data.location().localityInput().trim() : locality.getSectorName();
        String selectedCity = locality == null ? CityRegistry.canonicalCityName(data.location().city()) : locality.getCity();
        if (draft.getPublishedPropertyId() != null) {
            listings.lockOwnedId(draft.getPublishedPropertyId(), ownerId)
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            Listing target = listings.findByIdAndOwnerUserId(draft.getPublishedPropertyId(), ownerId)
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            if (!(target instanceof RentalDetails rental) || draft.getRevisionBaseVersion() == null
                    || !draft.getRevisionBaseVersion().equals(target.getVersion())) {
                throw new DraftConflictException(draftId, draft.getVersion(),
                        "The approved property changed. Review a fresh revision before submitting.");
            }
            if (target.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED
                    && target.getWorkflowStatus() != ListingWorkflowStatus.PAUSED
                    && target.getWorkflowStatus() != ListingWorkflowStatus.CHANGES_REQUIRED) {
                throw new DraftConflictException(draftId, draft.getVersion(), "The property status changed. Reload before submitting.");
            }
            if (target.getWorkflowStatus() == ListingWorkflowStatus.CHANGES_REQUIRED) {
                applyRevision(rental, data, rows, locality);
                workflow.transition(rental, ListingWorkflowStatus.SUBMITTED, ListingWorkflowService.Actor.LANDLORD);
                rental.setReviewNote(null);
                listings.saveAndFlush(rental);
                draft.setStatus("SUBMITTED");
                if (workflowNotifications != null) {
                    workflowNotifications.notifyPropertySubmitted(rental);
                }
            } else {
                // Approved live content is unchanged until an administrator applies this revision.
                draft.setStatus("REVIEW");
            }
            draft.setReviewNote(null);
            draft.setUpdatedAt(LocalDateTime.now());
            PropertyUploadDraft savedDraft = drafts.saveAndFlush(draft);
            if ("REVIEW".equals(savedDraft.getStatus()) && workflowNotifications != null) {
                workflowNotifications.notifyRevisionSubmitted(target, draftId, savedDraft.getVersion());
            }
            return new LandlordSubmission(target.getId(), draftId, title(data, selectedName),
                    ListingWorkflowStatus.SUBMITTED, draft.getUpdatedAt());
        }
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title(data, selectedName));
        listing.setPropertyType(data.basics().propertyType());
        listing.setBhkCount(data.basics().bhkCount());
        listing.setCity(selectedCity);
        listing.setSector(selectedName);
        setResolution(listing, data.location());
        listing.setAddress(data.location().address().trim());
        listing.setLandmark(data.location().landmark());
        listing.setDescription(data.details().description());
        listing.setFurnishingStatus(data.details().furnishingStatus());
        listing.setTotalAreaSqFt(data.details().totalAreaSqFt());
        listing.setFloorNumber(data.details().floorNumber());
        listing.setTotalFloors(data.details().totalFloors());
        listing.setLessorProfileId(profile.getId());
        listing.setMonthlyRent(data.pricing().monthlyRent());
        listing.setSecurityDeposit(data.pricing().securityDeposit());
        listing.setAvailableFrom(data.details().availableFrom().atStartOfDay());
        listing.setOriginDraftId(draftId);
        workflow.prepareSubmitted(listing, ownerId, locality == null ? null : locality.getId());
        Listing saved = listings.saveAndFlush(listing);
        List<PropertyMediaAsset> permanent = new ArrayList<>();
        int sortOrder = 0;
        for (PropertyDraftMedia row : rows.stream().filter(item -> "UPLOADED".equals(item.getUploadStatus()))
                .sorted(Comparator.comparing(PropertyDraftMedia::getSortOrder)).toList()) {
            boolean video = row.getContentType().startsWith("video/");
            PropertyMediaAsset asset = new PropertyMediaAsset(saved.getId(), row.getCloudinaryUrl(),
                    video ? MediaType.VIDEO_WALKTHROUGH : MediaType.IMAGE, RoomTag.fromStored(row.getRoomTag()),
                    video ? "Property video" : "Property photo");
            asset.setCloudinaryPublicId(row.getCloudinaryPublicId());
            asset.setUploadRequestId(row.getMediaId());
            asset.setIsPrimaryCover(Boolean.TRUE.equals(row.getIsCover()) && !video);
            asset.setSortOrder(sortOrder++);
            asset.setSector(selectedName);
            asset.setCity(selectedCity);
            asset.setPriceTag("₹" + data.pricing().monthlyRent().toPlainString() + " / month");
            permanent.add(asset);
        }
        assets.saveAll(permanent);
        draft.setStatus("SUBMITTED");
        drafts.saveAndFlush(draft);
        if (workflowNotifications != null) {
            workflowNotifications.notifyPropertySubmitted(saved);
        }
        capabilities.activateAfterSubmission(email);
        return response(saved, draftId);
    }

    void requireCompleteRevision(LandlordDraftData data, List<PropertyDraftMedia> rows) {
        List<String> missing = missing(data, rows, locationName(data.location()) != null);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Revision is incomplete: " + String.join(", ", missing));
        if (rows.stream().anyMatch(row -> "PENDING".equals(row.getUploadStatus()) || "DELETING".equals(row.getUploadStatus()))) {
            throw new IllegalArgumentException("Revision media is still changing");
        }
    }

    /** Called only after a validated, owner-scoped revision enters review. */
    void applyRevision(RentalDetails listing, LandlordDraftData data,
                       List<PropertyDraftMedia> rows, Locality locality) {
        String selectedName = locality == null ? data.location().localityInput().trim() : locality.getSectorName();
        String selectedCity = locality == null ? CityRegistry.canonicalCityName(data.location().city()) : locality.getCity();
        listing.setTitle(title(data, selectedName));
        listing.setPropertyType(data.basics().propertyType());
        listing.setBhkCount(data.basics().bhkCount());
        listing.setCity(selectedCity);
        listing.setSector(selectedName);
        listing.setCanonicalLocalityId(locality == null ? null : locality.getId());
        setResolution(listing, data.location());
        listing.setAddress(data.location().address().trim());
        listing.setLandmark(data.location().landmark());
        listing.setDescription(data.details().description());
        listing.setFurnishingStatus(data.details().furnishingStatus());
        listing.setTotalAreaSqFt(data.details().totalAreaSqFt());
        listing.setFloorNumber(data.details().floorNumber());
        listing.setTotalFloors(data.details().totalFloors());
        listing.setAmenities(data.details().amenities());
        listing.setMonthlyRent(data.pricing().monthlyRent());
        listing.setSecurityDeposit(data.pricing().securityDeposit());
        listing.setAvailableFrom(data.details().availableFrom().atStartOfDay());
        List<PropertyMediaAsset> previous = assets.findByListingIdOrderByUploadedAtDesc(listing.getId());
        assets.deleteAll(previous);
        assets.flush();
        List<PropertyMediaAsset> replacement = new ArrayList<>();
        int sortOrder = 0;
        for (PropertyDraftMedia row : rows.stream().filter(item -> "UPLOADED".equals(item.getUploadStatus()))
                .sorted(Comparator.comparing(PropertyDraftMedia::getSortOrder)).toList()) {
            boolean video = row.getContentType().startsWith("video/");
            PropertyMediaAsset asset = new PropertyMediaAsset(listing.getId(), row.getCloudinaryUrl(),
                    video ? MediaType.VIDEO_WALKTHROUGH : MediaType.IMAGE, RoomTag.fromStored(row.getRoomTag()),
                    video ? "Property video" : "Property photo");
            asset.setCloudinaryPublicId(row.getCloudinaryPublicId());
            asset.setUploadRequestId(row.getMediaId());
            asset.setIsPrimaryCover(Boolean.TRUE.equals(row.getIsCover()) && !video);
            asset.setSortOrder(sortOrder++);
            asset.setCity(selectedCity);
            asset.setSector(selectedName);
            asset.setPriceTag("₹" + data.pricing().monthlyRent().toPlainString() + " / month");
            replacement.add(asset);
        }
        assets.saveAll(replacement);
    }

    private List<String> missing(LandlordDraftData data, List<PropertyDraftMedia> rows, boolean canonicalLocation) {
        List<String> missing = new ArrayList<>();
        if (data.basics() == null || data.basics().propertyType() == null ||
                !List.of(PropertyType.FLAT, PropertyType.HOUSE, PropertyType.STUDIO,
                        PropertyType.PENTHOUSE, PropertyType.SERVICED_APARTMENT).contains(data.basics().propertyType()) ||
                data.basics().rentalMode() != RentalMode.LONG_TERM_RENTAL ||
                data.basics().bhkCount() == null || !BHK.matcher(data.basics().bhkCount()).matches()) {
            missing.add("supported home type and exact configuration");
        }
        if (data.pricing() == null || data.pricing().monthlyRent() == null ||
                data.pricing().monthlyRent().compareTo(BigDecimal.ZERO) <= 0 ||
                data.pricing().securityDeposit() == null || data.pricing().securityDeposit().compareTo(BigDecimal.ZERO) < 0) {
            missing.add("monthly rent and deposit");
        }
        if (data.location() == null || data.location().city() == null || data.location().city().isBlank() ||
                !canonicalLocation || data.location().address() == null ||
                data.location().address().isBlank()) missing.add("confirmed city, locality, and private address");
        if (data.details() == null || data.details().availableFrom() == null) missing.add("availability date");
        long covers = rows.stream().filter(row -> ("UPLOADED".equals(row.getUploadStatus()) ||
                Boolean.TRUE.equals(row.getGuestOwned()) && "STAGED".equals(row.getUploadStatus())) &&
                row.getContentType().startsWith("image/") && Boolean.TRUE.equals(row.getIsCover())).count();
        if (covers == 0) missing.add("choose a cover photo");
        else if (covers > 1) missing.add("choose one cover photo");
        return missing;
    }

    private String locationName(LandlordDraftData.Location location) {
        if (location == null || !CityRegistry.isCitySupported(location.city())) return null;
        if (location.canonicalLocalityId() != null) {
            try { return locations.requireMatchingLocality(location.city(), location.canonicalLocalityId()).getSectorName(); }
            catch (IllegalArgumentException ex) { return null; }
        }
        if (location.localityInput() == null || location.localityInput().isBlank()) return null;
        if (location.resolutionType() == LocationResolution.MANUAL_PENDING) return location.localityInput().trim();
        if (location.resolutionType() == LocationResolution.EXTERNAL_RESOLVED
                && locations.validExternalSelection(location.city(), location.localityInput(), location.provider(),
                        location.providerPlaceId(), location.selectionToken())) return location.localityInput().trim();
        return null;
    }

    private void setResolution(Listing listing, LandlordDraftData.Location location) {
        listing.setLocationResolution(location.canonicalLocalityId() == null ? location.resolutionType() : LocationResolution.CANONICAL);
        listing.setLocationProvider(location.canonicalLocalityId() == null ? location.provider() : null);
        listing.setLocationProviderPlaceId(location.canonicalLocalityId() == null ? location.providerPlaceId() : null);
    }

    private String title(LandlordDraftData data, String locality) {
        if (data.basics() == null) return "Rental home";
        String type = data.basics().propertyType() == null ? "home" : data.basics().propertyType().name().toLowerCase().replace('_', ' ');
        return (data.basics().bhkCount() == null ? "" : data.basics().bhkCount() + " ") + type +
                (locality == null ? "" : " in " + locality);
    }

    private LandlordSubmission response(Listing listing, String draftId) {
        return new LandlordSubmission(listing.getId(), draftId, listing.getTitle(),
                listing.getWorkflowStatus(), listing.getSubmittedAt());
    }
}
