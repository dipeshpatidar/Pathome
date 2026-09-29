package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordSubmissionServiceTest {
    private LandlordCapabilityService capabilities;
    private LandlordDraftService draftData;
    private LandlordLocationService locations;
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private ListingRepository listings;
    private PropertyMediaAssetRepository assets;
    private UserRepository users;
    private LessorProfileService lessorProfiles;
    private LandlordSubmissionService service;
    private PropertyUploadDraft draft;
    private List<PropertyDraftMedia> rows;

    @BeforeEach
    void setUp() {
        capabilities = mock(LandlordCapabilityService.class);
        draftData = mock(LandlordDraftService.class);
        locations = mock(LandlordLocationService.class);
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        listings = mock(ListingRepository.class);
        assets = mock(PropertyMediaAssetRepository.class);
        users = mock(UserRepository.class);
        lessorProfiles = mock(LessorProfileService.class);
        service = new LandlordSubmissionService(capabilities, draftData, locations, drafts, media, listings,
                assets, users, new ListingWorkflowService(), lessorProfiles);
        when(capabilities.requireOnboardingUserId("owner@example.com")).thenReturn(5L);
        draft = new PropertyUploadDraft();
        draft.setDraftId("d1");
        draft.setLandlordUserId(5L);
        draft.setStatus("DRAFT");
        when(drafts.findByDraftIdAndLandlordUserId("d1", 5L)).thenReturn(Optional.of(draft));
        when(drafts.findLandlordDraftForUpdate("d1", 5L)).thenReturn(Optional.of(draft));
        when(draftData.readData(draft)).thenReturn(validData());
        Locality locality = new Locality();
        locality.setId(10L);
        locality.setCity("Indore");
        locality.setSectorName("Vijay Nagar");
        when(locations.requireMatchingLocality("Indore", 10L)).thenReturn(locality);
        PropertyDraftMedia photo = new PropertyDraftMedia();
        photo.setMediaId("photo-one");
        photo.setContentType("image/jpeg");
        photo.setUploadStatus("UPLOADED");
        photo.setCloudinaryUrl("https://example.com/photo.jpg");
        photo.setCloudinaryPublicId("pathome/properties/images/photo-one");
        photo.setIsCover(true);
        photo.setSortOrder(0);
        rows = List.of(photo);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L)).thenReturn(rows);
        User owner = new User();
        owner.setId(5L);
        owner.setRole(Role.ROLE_TENANT);
        owner.setFullName("Owner");
        owner.setPhoneNumber("+91 98260 12345");
        when(users.findById(5L)).thenReturn(Optional.of(owner));
        LessorProfile profile = new LessorProfile(50L, 5L, "Owner", "+91 98260 12345", "owner@example.com", LessorSourceType.SELF_SERVICE);
        when(lessorProfiles.getOrCreateProfileForUser(owner)).thenReturn(profile);
        when(lessorProfiles.getProfileForUser(5L)).thenReturn(Optional.of(profile));
        when(listings.saveAndFlush(any())).thenAnswer(invocation -> {
            Listing saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(drafts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void previewRedactsAddressContactAndCoordinates() throws Exception {
        var preview = service.preview("owner@example.com", "d1");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(preview);
        assertFalse(json.contains("10 Private Road"));
        assertFalse(json.contains("ownerPhone"));
        assertFalse(json.contains("latitude"));
        assertEquals("Vijay Nagar", preview.locality());
        assertTrue(preview.missingRequirements().isEmpty());
        verify(listings, never()).saveAndFlush(any());
    }

    @Test
    void duplicateReadyCoversCannotBeSubmitted() {
        PropertyDraftMedia second = new PropertyDraftMedia();
        second.setMediaId("photo-two");
        second.setContentType("image/jpeg");
        second.setUploadStatus("UPLOADED");
        second.setCloudinaryUrl("https://example.com/second.jpg");
        second.setIsCover(true);
        second.setSortOrder(1);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(rows.get(0), second));
        assertTrue(service.preview("owner@example.com", "d1").missingRequirements().contains("choose one cover photo"));
        assertThrows(IllegalArgumentException.class, () -> service.submit("owner@example.com", "d1"));
        verify(listings, never()).saveAndFlush(any());
    }

    @Test
    void submitsPrivateListingOnceAndSecondTapReturnsSameListing() {
        var first = service.submit("owner@example.com", "d1");
        assertEquals(42L, first.listingId());
        assertEquals(ListingWorkflowStatus.SUBMITTED, first.status());
        assertEquals("SUBMITTED", draft.getStatus());
        ArgumentCaptor<Listing> captured = ArgumentCaptor.forClass(Listing.class);
        verify(listings).saveAndFlush(captured.capture());
        var listing = (RentalDetails) captured.getValue();
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        assertEquals("10 Private Road", listing.getAddress());
        assertNull(listing.getOwnerPhoneNumber(),
            "New LessorProfile-backed listing must not duplicate contact into legacy field");
        assertEquals(5L, listing.getOwnerUserId());
        assertEquals(50L, listing.getLessorProfileId());
        when(listings.findByOriginDraftId("d1")).thenReturn(Optional.of(listing));
        var second = service.submit("owner@example.com", "d1");
        assertEquals(first.listingId(), second.listingId());
        verify(listings, times(1)).saveAndFlush(any());
        verify(assets, times(1)).saveAll(any());
        verify(capabilities, times(1)).activateAfterSubmission("owner@example.com");
    }

    @Test
    void firstSubmissionCreatesProfileAndActivatesOnlyAfterListingAndDraftPersist() {
        when(lessorProfiles.getProfileForUser(5L)).thenReturn(Optional.empty());
        var result = service.submit("owner@example.com", "d1");
        assertEquals(42L, result.listingId());
        assertEquals(Role.ROLE_TENANT, users.findById(5L).orElseThrow().getRole());
        var order = inOrder(lessorProfiles, listings, drafts, capabilities);
        order.verify(lessorProfiles).getOrCreateProfileForUser(any(User.class));
        order.verify(listings).saveAndFlush(any(Listing.class));
        order.verify(drafts).saveAndFlush(draft);
        order.verify(capabilities).activateAfterSubmission("owner@example.com");
    }

    @Test
    void lateSubmissionFailureDoesNotActivate() {
        when(lessorProfiles.getProfileForUser(5L)).thenReturn(Optional.empty());
        when(assets.saveAll(any())).thenThrow(new IllegalStateException("media write failed"));
        assertThrows(IllegalStateException.class, () -> service.submit("owner@example.com", "d1"));
        verify(capabilities, never()).activateAfterSubmission(any());
    }

    @Test
    void anotherPropertyReusesExistingProfile() {
        service.submit("owner@example.com", "d1");
        PropertyUploadDraft nextDraft = new PropertyUploadDraft();
        nextDraft.setDraftId("d2");
        nextDraft.setLandlordUserId(5L);
        nextDraft.setStatus("DRAFT");
        when(drafts.findLandlordDraftForUpdate("d2", 5L)).thenReturn(Optional.of(nextDraft));
        when(draftData.readData(nextDraft)).thenReturn(validData());
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d2", 5L)).thenReturn(rows);
        service.submit("owner@example.com", "d2");
        assertEquals(50L, nextDraft.getLessorProfileId());
        verify(lessorProfiles, never()).getOrCreateProfileForUser(any());
        verify(capabilities, times(2)).activateAfterSubmission("owner@example.com");
    }

    @Test
    void incompleteDraftAndCrossOwnerCannotSubmit() {
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.submit("owner@example.com", "d1"));
        when(drafts.findLandlordDraftForUpdate("d1", 5L)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.submit("owner@example.com", "d1"));
        verify(listings, never()).saveAndFlush(any());
        verify(capabilities, never()).activateAfterSubmission(any());
    }

    @Test
    void missingOwnerPhoneOrNameRejectsSubmission() {
        User incompleteOwner = new User();
        incompleteOwner.setId(5L);
        incompleteOwner.setFullName("Owner");
        incompleteOwner.setPhoneNumber(null);
        when(users.findById(5L)).thenReturn(Optional.of(incompleteOwner));
        when(lessorProfiles.getProfileForUser(5L)).thenReturn(Optional.empty());
        var ex = assertThrows(IllegalArgumentException.class, () -> service.submit("owner@example.com", "d1"));
        assertTrue(ex.getMessage().contains("contact details"));
        verify(lessorProfiles, never()).getOrCreateProfileForUser(any());
    }

    @Test
    void legacyPlaceholderNameRejectsSubmission() {
        User placeholderOwner = new User();
        placeholderOwner.setId(5L);
        placeholderOwner.setFullName("Lessor 5");
        placeholderOwner.setPhoneNumber("+91 98260 12345");
        when(users.findById(5L)).thenReturn(Optional.of(placeholderOwner));
        when(lessorProfiles.getProfileForUser(5L)).thenReturn(Optional.empty());
        var ex = assertThrows(IllegalArgumentException.class, () -> service.submit("owner@example.com", "d1"));
        assertTrue(ex.getMessage().contains("contact details"));
        verify(lessorProfiles, never()).getOrCreateProfileForUser(any());
    }

    @Test
    void manualLocalityCanSubmitButRemainsPrivateAndDoesNotCreateCanonicalData() {
        var original = validData();
        when(draftData.readData(draft)).thenReturn(new LandlordDraftData(original.basics(), original.pricing(),
                new LandlordDraftData.Location("Indore", null, "Rani Pura", "10 Private Road", "",
                        LocationResolution.MANUAL_PENDING, null, null, null), original.details()));
        var result = service.submit("owner@example.com", "d1");
        assertEquals(ListingWorkflowStatus.SUBMITTED, result.status());
        ArgumentCaptor<Listing> captured = ArgumentCaptor.forClass(Listing.class);
        verify(listings).saveAndFlush(captured.capture());
        assertEquals("Rani Pura", captured.getValue().getSector());
        assertNull(captured.getValue().getCanonicalLocalityId());
        assertEquals(LocationResolution.MANUAL_PENDING, captured.getValue().getLocationResolution());
        assertEquals(ListingStatus.PENDING, captured.getValue().getStatus());
        verify(locations, never()).requireMatchingLocality(anyString(), anyLong());
    }

    @Test
    void externalSelectionPersistsProviderReferenceWithoutPublishing() {
        var original = validData();
        var location = new LandlordDraftData.Location("Indore", null, "Rani Pura", "10 Private Road", "",
                LocationResolution.EXTERNAL_RESOLVED, "MAPTILER", "locality.1", "signed-token");
        when(draftData.readData(draft)).thenReturn(new LandlordDraftData(original.basics(), original.pricing(),
                location, original.details()));
        when(locations.validExternalSelection("Indore", "Rani Pura", "MAPTILER", "locality.1", "signed-token"))
                .thenReturn(true);
        service.submit("owner@example.com", "d1");
        ArgumentCaptor<Listing> captured = ArgumentCaptor.forClass(Listing.class);
        verify(listings).saveAndFlush(captured.capture());
        assertEquals("MAPTILER", captured.getValue().getLocationProvider());
        assertEquals("locality.1", captured.getValue().getLocationProviderPlaceId());
        assertNull(captured.getValue().getCanonicalLocalityId());
        assertEquals(ListingStatus.PENDING, captured.getValue().getStatus());
    }

    @Test
    void publishedRevisionSubmitsForReviewWithoutChangingLiveListing() {
        RentalDetails live = new RentalDetails();
        live.setId(88L);
        live.setOwnerUserId(5L);
        live.setTitle("Approved property");
        live.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        live.setStatus(ListingStatus.ACTIVE);
        live.setVersion(3L);
        draft.setPublishedPropertyId(88L);
        draft.setRevisionBaseVersion(3L);
        when(listings.lockOwnedId(88L, 5L)).thenReturn(Optional.of(88L));
        when(listings.findByIdAndOwnerUserId(88L, 5L)).thenReturn(Optional.of(live));

        var submitted = service.submit("owner@example.com", "d1");
        assertEquals(88L, submitted.listingId());
        assertEquals(ListingWorkflowStatus.SUBMITTED, submitted.status());
        assertEquals("REVIEW", draft.getStatus());
        assertEquals("Approved property", live.getTitle());
        assertEquals(ListingStatus.ACTIVE, live.getStatus());
        verify(listings, never()).saveAndFlush(any());
        verify(assets, never()).deleteAll(any());
        assertEquals(88L, service.submit("owner@example.com", "d1").listingId());
    }

    @Test
    void revisionSubmissionUsesPersistedVersionAndRetryDoesNotRenotify() {
        RentalDetails live = new RentalDetails();
        live.setId(88L);
        live.setOwnerUserId(5L);
        live.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        live.setVersion(3L);
        draft.setPublishedPropertyId(88L);
        draft.setRevisionBaseVersion(3L);
        LessorWorkflowNotificationService notifications = mock(LessorWorkflowNotificationService.class);
        service.setWorkflowNotifications(notifications);
        when(listings.lockOwnedId(88L, 5L)).thenReturn(Optional.of(88L));
        when(listings.findByIdAndOwnerUserId(88L, 5L)).thenReturn(Optional.of(live));
        when(drafts.saveAndFlush(draft)).thenAnswer(invocation -> {
            draft.setVersion(draft.getVersion() + 1);
            return draft;
        });

        service.submit("owner@example.com", "d1");
        verify(notifications).notifyRevisionSubmitted(live, "d1", 2);
        service.submit("owner@example.com", "d1");
        verifyNoMoreInteractions(notifications);

        draft.setStatus("DRAFT"); // Same persisted draft returned for changes and edited again.
        draft.setVersion(5);
        service.submit("owner@example.com", "d1");
        verify(notifications).notifyRevisionSubmitted(live, "d1", 6);
    }

    @Test
    void revisionSubmissionSucceedsWhenProfileHasNoLinkedUser() {
        RentalDetails live = new RentalDetails();
        live.setId(88L);
        live.setOwnerUserId(5L);
        live.setLessorProfileId(50L);
        live.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        live.setVersion(3L);
        draft.setPublishedPropertyId(88L);
        draft.setRevisionBaseVersion(3L);
        when(listings.lockOwnedId(88L, 5L)).thenReturn(Optional.of(88L));
        when(listings.findByIdAndOwnerUserId(88L, 5L)).thenReturn(Optional.of(live));
        LessorProfile unlinked = new LessorProfile();
        unlinked.setId(50L);
        LessorProfileRepository profiles = mock(LessorProfileRepository.class);
        SystemNotificationRepository notifications = mock(SystemNotificationRepository.class);
        when(profiles.findById(50L)).thenReturn(Optional.of(unlinked));
        service.setWorkflowNotifications(new LessorWorkflowNotificationService(notifications, profiles));

        assertEquals(88L, service.submit("owner@example.com", "d1").listingId());
        assertEquals("REVIEW", draft.getStatus());
        verify(notifications, never()).insertWorkflowIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void changesRequestedRevisionResubmitsSamePrivateListing() {
        RentalDetails pending = new RentalDetails();
        pending.setId(42L);
        pending.setOwnerUserId(5L);
        pending.setVersion(3L);
        pending.setWorkflowStatus(ListingWorkflowStatus.CHANGES_REQUIRED);
        pending.setStatus(ListingStatus.PENDING);
        pending.setReviewNote("Please clarify the date.");
        draft.setPublishedPropertyId(42L);
        draft.setRevisionBaseVersion(3L);
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(pending));
        when(assets.findByListingIdOrderByUploadedAtDesc(42L)).thenReturn(List.of());

        var result = service.submit("owner@example.com", "d1");
        assertEquals(42L, result.listingId());
        assertEquals("SUBMITTED", draft.getStatus());
        assertEquals(ListingWorkflowStatus.SUBMITTED, pending.getWorkflowStatus());
        assertEquals(ListingStatus.PENDING, pending.getStatus());
        assertNull(pending.getReviewNote());
        verify(listings).saveAndFlush(pending);
    }

    @Test
    void submissionPreservesLandlordMediaOrderOnPermanentAssets() {
        PropertyDraftMedia cover = rows.get(0);
        cover.setSortOrder(2);
        cover.setRoomTag("LIVING_ROOM");
        PropertyDraftMedia first = new PropertyDraftMedia();
        first.setMediaId("photo-first");
        first.setContentType("image/jpeg");
        first.setUploadStatus("UPLOADED");
        first.setCloudinaryUrl("https://example.com/first.jpg");
        first.setSortOrder(0);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(cover, first));

        service.submit("owner@example.com", "d1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropertyMediaAsset>> saved = ArgumentCaptor.forClass(List.class);
        verify(assets).saveAll(saved.capture());
        assertEquals("https://example.com/first.jpg", saved.getValue().get(0).getMediaUrl());
        assertEquals(0, saved.getValue().get(0).getSortOrder());
        assertEquals("https://example.com/photo.jpg", saved.getValue().get(1).getMediaUrl());
        assertEquals(1, saved.getValue().get(1).getSortOrder());
        assertEquals(RoomTag.LIVING_ROOM, saved.getValue().get(1).getRoomTag());
    }

    private LandlordDraftData validData() {
        return new LandlordDraftData(
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK"),
                new LandlordDraftData.Pricing(new BigDecimal("20000"), BigDecimal.ZERO),
                new LandlordDraftData.Location("Indore", 10L, "Vijay Nagar", "10 Private Road", "Near park"),
                new LandlordDraftData.Details(LocalDate.now(), "UNFURNISHED", 850.0, 2, 5, "Balcony", "Bright flat"));
    }
}
