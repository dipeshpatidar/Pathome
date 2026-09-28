package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordManagementServiceTest {
    private final LandlordCapabilityService capabilities = mock(LandlordCapabilityService.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final PropertyDraftMediaRepository media = mock(PropertyDraftMediaRepository.class);
    private final PropertyMediaAssetRepository assets = mock(PropertyMediaAssetRepository.class);
    private final LandlordDraftService draftService = mock(LandlordDraftService.class);
    private final LandlordManagementService service = new LandlordManagementService(capabilities, listings,
            new ListingWorkflowService(), drafts, media, assets, draftService,
            new ObjectMapper().findAndRegisterModules());
    private RentalDetails listing;

    @BeforeEach
    void setUp() {
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(5L);
        when(capabilities.requireLandlordUserId("other@example.com")).thenReturn(8L);
        listing = new RentalDetails();
        listing.setId(42L);
        listing.setOwnerUserId(5L);
        listing.setVersion(3L);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setTitle("Approved home");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setRentalMode(RentalMode.LONG_TERM_RENTAL);
        listing.setBhkCount("2BHK");
        listing.setCity("Indore");
        listing.setSector("Vijay Nagar");
        listing.setCanonicalLocalityId(10L);
        listing.setAddress("Private road");
        listing.setMonthlyRent(new BigDecimal("20000"));
        listing.setSecurityDeposit(BigDecimal.ZERO);
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void ownerCanPauseAndArchiveWithVersionWhileOtherOwnerCannot() {
        assertThrows(EntityNotFoundException.class,
                () -> service.changeStatus("other@example.com", 42L, 3L, ListingWorkflowStatus.PAUSED));
        assertThrows(DraftConflictException.class,
                () -> service.changeStatus("owner@example.com", 42L, 2L, ListingWorkflowStatus.PAUSED));
        var paused = service.changeStatus("owner@example.com", 42L, 3L, ListingWorkflowStatus.PAUSED);
        assertEquals(ListingWorkflowStatus.PAUSED, paused.status());
        assertEquals(ListingStatus.CLOSED, listing.getStatus());
        var archived = service.changeStatus("owner@example.com", 42L, 3L, ListingWorkflowStatus.ARCHIVED);
        assertEquals(ListingWorkflowStatus.ARCHIVED, archived.status());
        assertThrows(DraftConflictException.class,
                () -> service.changeStatus("owner@example.com", 42L, 3L, ListingWorkflowStatus.PAUSED));
    }

    @Test
    void revisionCopiesApprovedDataAndMediaButDoesNotMutateLiveListing() {
        PropertyMediaAsset cover = new PropertyMediaAsset(42L, "https://example.com/live.jpg",
                MediaType.IMAGE, RoomTag.GENERAL, "cover");
        cover.setIsPrimaryCover(true);
        cover.setCloudinaryPublicId("live-public-id");
        when(assets.findByListingIdOrderByUploadedAtDesc(42L)).thenReturn(List.of(cover));
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(draftService.get(eq("owner@example.com"), anyString())).thenAnswer(call ->
                new LandlordDraftResponse(call.getArgument(1), "DRAFT", 1, 0, null, null, null, 42L, null));

        var result = service.startRevision("owner@example.com", 42L);
        assertTrue(result.draftId().startsWith("landlord-"));
        var draft = org.mockito.ArgumentCaptor.forClass(PropertyUploadDraft.class);
        verify(drafts).saveAndFlush(draft.capture());
        assertEquals(42L, draft.getValue().getPublishedPropertyId());
        assertEquals(3L, draft.getValue().getRevisionBaseVersion());
        assertTrue(draft.getValue().getPayload().contains("Private road"));
        var inherited = org.mockito.ArgumentCaptor.forClass(PropertyDraftMedia.class);
        verify(media).save(inherited.capture());
        assertTrue(inherited.getValue().getReusedFromListing());
        assertEquals("live-public-id", inherited.getValue().getCloudinaryPublicId());
        assertEquals("Approved home", listing.getTitle());
        verify(listings, never()).saveAndFlush(any());
    }
}
