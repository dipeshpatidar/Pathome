package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftPage;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftSummary;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.RentalMode;
import com.indore.pathome.spaces.entity.LocationResolution;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Service
public class LandlordDraftService {
    private static final EnumSet<PropertyType> SUPPORTED_TYPES = EnumSet.of(
            PropertyType.FLAT, PropertyType.HOUSE, PropertyType.STUDIO,
            PropertyType.PENTHOUSE, PropertyType.SERVICED_APARTMENT);
    private static final Pattern BHK_PATTERN = Pattern.compile("^(?:1RK|[1-9][0-9]?BHK)$");
    private static final int PAGE_SIZE = 20;
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private final PropertyUploadDraftRepository drafts;
    private final LandlordCapabilityService capabilities;
    private final ObjectMapper mapper;
    private final LandlordLocationService locations;
    private final PropertyDraftMediaRepository media;
    private final DiscardedDraftCleanupService discardCleanup;

    public LandlordDraftService(PropertyUploadDraftRepository drafts,
                                LandlordCapabilityService capabilities, ObjectMapper mapper,
                                LandlordLocationService locations, PropertyDraftMediaRepository media,
                                DiscardedDraftCleanupService discardCleanup) {
        this.drafts = drafts;
        this.capabilities = capabilities;
        this.mapper = mapper;
        this.locations = locations;
        this.media = media;
        this.discardCleanup = discardCleanup;
    }

    @Transactional
    public void discard(String email, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        PropertyUploadDraft draft = drafts.findLandlordDraftForUpdate(draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
        if (draft.getAdminId() != null) {
            throw new EntityNotFoundException("Draft not found");
        }
        if ("DISCARDED".equals(draft.getStatus())) return;
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new DraftConflictException(draftId, draft.getVersion() == null ? 0 : draft.getVersion(),
                    "Only in-progress drafts can be discarded");
        }
        draft.setStatus("DISCARDED");
        draft.setPayload("{}");
        drafts.saveAndFlush(draft);
        discardCleanup.afterCommit(draftId);
    }

    @Transactional
    public LandlordDraftResponse create(String email, LandlordDraftData.Basics basics) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        LandlordDraftData.Basics validated = validateBasics(basics);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("landlord-" + UUID.randomUUID());
        draft.setLandlordUserId(ownerId);
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setTitleSummary(titleFor(validated));
        draft.setItemCount(1);
        LandlordDraftData data = new LandlordDraftData(validated, null, null, null);
        draft.setPayload(writeData(data));
        return toResponse(drafts.saveAndFlush(draft), data);
    }

    @Transactional(readOnly = true)
    public LandlordDraftResponse get(String email, String draftId) {
        PropertyUploadDraft draft = requireOwned(email, draftId);
        return toResponse(draft, readData(draft));
    }

    @Transactional(readOnly = true)
    public LandlordDraftPage list(String email, int page) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        Slice<PropertyUploadDraft> slice = drafts.findByLandlordUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                ownerId, "DRAFT", PageRequest.of(page, PAGE_SIZE));
        java.util.Map<String, PropertyDraftMedia> covers = slice.isEmpty() ? java.util.Map.of() :
                media.findUploadedCoversForDraftIds(slice.getContent().stream().map(PropertyUploadDraft::getDraftId).toList(), ownerId)
                        .stream().collect(java.util.stream.Collectors.toMap(PropertyDraftMedia::getDraftId,
                                cover -> cover, (first, ignored) -> first));
        return new LandlordDraftPage(slice.getContent().stream().map(draft -> {
            LandlordDraftData data = readData(draft);
            return new LandlordDraftSummary(draft.getDraftId(), draft.getTitleSummary(), draft.getStatus(),
                    completionPercent(data, covers.containsKey(draft.getDraftId())), toInstant(draft.getUpdatedAt()),
                    data.basics() == null ? null : data.basics().propertyType(),
                    data.basics() == null ? null : data.basics().bhkCount(),
                    data.location() == null ? null : data.location().city(),
                    data.location() == null ? null : data.location().localityInput(),
                    data.pricing() == null ? null : data.pricing().monthlyRent(),
                    covers.containsKey(draft.getDraftId()) ? covers.get(draft.getDraftId()).getCloudinaryUrl() : null);
        }).toList(), page, slice.hasNext(), drafts.countByLandlordUserIdAndStatus(ownerId, "DRAFT"));
    }

    private static Instant toInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        return localDateTime.atZone(INDIA_ZONE).toInstant();
    }

    @Transactional
    public LandlordDraftResponse updateBasics(String email, String draftId, int expectedVersion,
                                              LandlordDraftData.Basics basics) {
        LandlordDraftData.Basics validated = validateBasics(basics);
        return update(requireOwned(email, draftId), expectedVersion,
                data -> new LandlordDraftData(validated, data.pricing(), data.location(), data.details()));
    }

    @Transactional
    public LandlordDraftResponse updatePricing(String email, String draftId, int expectedVersion,
                                               LandlordDraftData.Pricing pricing) {
        validatePricing(pricing);
        return update(requireOwned(email, draftId), expectedVersion,
                data -> new LandlordDraftData(data.basics(), pricing, data.location(), data.details()));
    }

    private void validatePricing(LandlordDraftData.Pricing pricing) {
        if (pricing == null || pricing.monthlyRent() != null && pricing.monthlyRent().signum() <= 0
                || pricing.securityDeposit() != null && pricing.securityDeposit().signum() < 0) {
            throw new IllegalArgumentException("Rent must be positive and deposit cannot be negative");
        }
    }

    @Transactional
    public LandlordDraftResponse updateLocation(String email, String draftId, int expectedVersion,
                                                LandlordDraftData.Location location) {
        validateLocation(location);
        return update(requireOwned(email, draftId), expectedVersion,
                data -> new LandlordDraftData(data.basics(), data.pricing(), location, data.details()));
    }

    private void validateLocation(LandlordDraftData.Location location) {
        if (location == null || tooLong(location.city(), 120) || tooLong(location.localityInput(), 120)
                || tooLong(location.address(), 500) || tooLong(location.landmark(), 200)
                || tooLong(location.provider(), 40) || tooLong(location.providerPlaceId(), 160)
                || tooLong(location.selectionToken(), 100)
                || location.canonicalLocalityId() != null && location.canonicalLocalityId() <= 0) {
            throw new IllegalArgumentException("Location contains an invalid value");
        }
        if (location.city() != null && !location.city().isBlank() && !CityRegistry.isCitySupported(location.city())) {
            throw new IllegalArgumentException("Choose a city currently supported by Pathome");
        }
        if (location.canonicalLocalityId() != null) {
            var canonical = locations.requireMatchingLocality(location.city(), location.canonicalLocalityId());
            if (location.resolutionType() != null && location.resolutionType() != LocationResolution.CANONICAL
                    || location.provider() != null || location.providerPlaceId() != null || location.selectionToken() != null
                    || location.localityInput() == null
                    || !canonical.getSectorName().equalsIgnoreCase(location.localityInput().trim()))
                throw new IllegalArgumentException("Choose a matching canonical locality");
        } else if (location.resolutionType() == LocationResolution.EXTERNAL_RESOLVED) {
            if (!locations.validExternalSelection(location.city(), location.localityInput(), location.provider(),
                    location.providerPlaceId(), location.selectionToken()))
                throw new IllegalArgumentException("Choose a locality from the suggestions again");
        } else if (location.resolutionType() == LocationResolution.MANUAL_PENDING) {
            if (location.localityInput() == null || location.localityInput().isBlank()
                    || location.provider() != null || location.providerPlaceId() != null || location.selectionToken() != null)
                throw new IllegalArgumentException("Enter a locality for review");
        } else if (location.provider() != null || location.providerPlaceId() != null || location.selectionToken() != null) {
            throw new IllegalArgumentException("Location contains an invalid selection");
        }
    }

    @Transactional
    public LandlordDraftResponse updateDetails(String email, String draftId, int expectedVersion,
                                               LandlordDraftData.Details details) {
        validateDetails(details);
        return update(requireOwned(email, draftId), expectedVersion,
                data -> new LandlordDraftData(data.basics(), data.pricing(), data.location(), details));
    }

    private void validateDetails(LandlordDraftData.Details details) {
        if (details == null || details.totalAreaSqFt() != null &&
                (!Double.isFinite(details.totalAreaSqFt()) || details.totalAreaSqFt() <= 0)
                || details.floorNumber() != null && details.floorNumber() < 0
                || details.totalFloors() != null && details.totalFloors() < 1
                || tooLong(details.furnishingStatus(), 80) || tooLong(details.amenities(), 2000)
                || tooLong(details.description(), 4000)) {
            throw new IllegalArgumentException("Details contain an invalid value");
        }
    }

    /** Guest ownership is checked by GuestDraftService before these shared validators run. */
    @Transactional
    public LandlordDraftResponse updateGuestBasics(PropertyUploadDraft draft, int version, LandlordDraftData.Basics value) {
        LandlordDraftData.Basics checked = validateBasics(value);
        return update(draft, version, data -> new LandlordDraftData(checked, data.pricing(), data.location(), data.details()));
    }

    @Transactional
    public LandlordDraftResponse updateGuestPricing(PropertyUploadDraft draft, int version, LandlordDraftData.Pricing value) {
        validatePricing(value);
        return update(draft, version, data -> new LandlordDraftData(data.basics(), value, data.location(), data.details()));
    }

    @Transactional
    public LandlordDraftResponse updateGuestLocation(PropertyUploadDraft draft, int version, LandlordDraftData.Location value) {
        validateLocation(value);
        return update(draft, version, data -> new LandlordDraftData(data.basics(), data.pricing(), value, data.details()));
    }

    @Transactional
    public LandlordDraftResponse updateGuestDetails(PropertyUploadDraft draft, int version, LandlordDraftData.Details value) {
        validateDetails(value);
        return update(draft, version, data -> new LandlordDraftData(data.basics(), data.pricing(), data.location(), value));
    }

    @Transactional(readOnly = true)
    public PropertyUploadDraft requireOwned(String email, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        return drafts.findByDraftIdAndLandlordUserId(draftId, ownerId)
                .filter(draft -> !"DISCARDED".equals(draft.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
    }

    public LandlordDraftData readData(PropertyUploadDraft draft) {
        try {
            return mapper.readValue(draft.getPayload(), LandlordDraftData.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Saved draft could not be read");
        }
    }

    public int completionPercent(LandlordDraftData data) {
        return completionPercent(data, false);
    }

    public int completionPercent(LandlordDraftData data, boolean hasCover) {
        int complete = 0;
        if (data.basics() != null && data.basics().propertyType() != null) complete++;
        if (data.basics() != null && data.basics().bhkCount() != null) complete++;
        if (data.pricing() != null && data.pricing().monthlyRent() != null) complete++;
        if (data.pricing() != null && data.pricing().securityDeposit() != null) complete++;
        if (data.location() != null && present(data.location().city())) complete++;
        if (data.location() != null && (data.location().canonicalLocalityId() != null
                || data.location().resolutionType() == LocationResolution.EXTERNAL_RESOLVED
                || data.location().resolutionType() == LocationResolution.MANUAL_PENDING)) complete++;
        if (data.location() != null && present(data.location().address())) complete++;
        if (data.details() != null && data.details().availableFrom() != null) complete++;
        if (hasCover) complete++;
        return complete * 100 / 9;
    }

    private LandlordDraftResponse update(PropertyUploadDraft draft, int expectedVersion,
                                         UnaryOperator<LandlordDraftData> change) {
        String draftId = draft.getDraftId();
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new IllegalStateException("Draft can no longer be edited");
        }
        if (draft.getVersion() == null || draft.getVersion() != expectedVersion) {
            throw new DraftConflictException(draftId, draft.getVersion() == null ? 0 : draft.getVersion(),
                    "A newer version of this draft exists. Review it before saving again.");
        }
        LandlordDraftData data = change.apply(readData(draft));
        draft.setPayload(writeData(data));
        draft.setUpdatedAt(LocalDateTime.now());
        if (data.basics() != null) draft.setTitleSummary(titleFor(data.basics()));
        try {
            return toResponse(drafts.saveAndFlush(draft), data);
        } catch (OptimisticLockingFailureException ex) {
            throw new DraftConflictException(draftId, expectedVersion + 1,
                    "A newer version of this draft exists. Review it before saving again.");
        }
    }

    LandlordDraftData.Basics validateBasics(LandlordDraftData.Basics basics) {
        if (basics == null || basics.propertyType() == null || !SUPPORTED_TYPES.contains(basics.propertyType())
                || basics.rentalMode() != RentalMode.LONG_TERM_RENTAL
                || basics.bhkCount() != null && !BHK_PATTERN.matcher(basics.bhkCount()).matches()) {
            throw new IllegalArgumentException("Choose a supported long-term rental type and exact configuration");
        }
        return basics;
    }

    LandlordDraftResponse toResponse(PropertyUploadDraft draft, LandlordDraftData data) {
        boolean hasCover = draft.getGuestTokenHash() != null
                ? media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draft.getDraftId()).stream()
                    .anyMatch(item -> "STAGED".equals(item.getUploadStatus()) && Boolean.TRUE.equals(item.getIsCover())
                            && item.getContentType().startsWith("image/"))
                : media.existsByDraftIdAndLandlordUserIdAndUploadStatusAndIsCoverTrueAndContentTypeStartingWith(
                    draft.getDraftId(), draft.getLandlordUserId(), "UPLOADED", "image/");
        return new LandlordDraftResponse(draft.getDraftId(), draft.getStatus(), draft.getVersion(),
                completionPercent(data, hasCover), data, draft.getCreatedAt(), draft.getUpdatedAt(),
                draft.getPublishedPropertyId(), draft.getReviewNote());
    }

    String writeData(LandlordDraftData data) {
        try {
            String payload = mapper.writeValueAsString(data);
            if (payload.length() > 64_000) throw new IllegalArgumentException("Draft is too large");
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Draft could not be saved");
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static boolean tooLong(String value, int max) { return value != null && value.length() > max; }
    private static String titleFor(LandlordDraftData.Basics basics) {
        String type = basics.propertyType().name().toLowerCase().replace('_', ' ');
        String label = Character.toUpperCase(type.charAt(0)) + type.substring(1);
        return basics.bhkCount() == null ? label + " draft" : label + " · " + basics.bhkCount();
    }
}
