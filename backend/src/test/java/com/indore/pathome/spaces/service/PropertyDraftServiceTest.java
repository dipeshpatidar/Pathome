package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.draft.DraftDetailDTO;
import com.indore.pathome.spaces.dto.draft.DraftMediaDTO;
import com.indore.pathome.spaces.dto.draft.DraftSummaryDTO;
import com.indore.pathome.spaces.dto.draft.SaveDraftRequest;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PropertyDraftServiceTest {

    private PropertyUploadDraftRepository draftRepository;
    private PropertyDraftMediaRepository draftMediaRepository;
    private MediaStagingService mediaStagingService;
    private ObjectMapper objectMapper;
    private ListingRepository listingRepository;
    private PropertyMediaAssetRepository mediaAssetRepository;
    private PropertyDraftService service;

    private static final String ADMIN_ID = "admin@pathome.in";
    private static final String OTHER_ADMIN_ID = "other_admin@pathome.in";
    private static final String DRAFT_ID = "draft-1234-abcd";

    @BeforeEach
    void setUp() {
        draftRepository = mock(PropertyUploadDraftRepository.class);
        draftMediaRepository = mock(PropertyDraftMediaRepository.class);
        mediaStagingService = mock(MediaStagingService.class);
        listingRepository = mock(ListingRepository.class);
        mediaAssetRepository = mock(PropertyMediaAssetRepository.class);
        objectMapper = new ObjectMapper();
        service = new PropertyDraftService(draftRepository, draftMediaRepository, mediaStagingService, objectMapper);
        service.setListingRepository(listingRepository);
        service.setMediaAssetRepository(mediaAssetRepository);
    }

    @Test
    void saveOrUpdateDraft_createsNewDraft() {
        SaveDraftRequest req = new SaveDraftRequest(
                DRAFT_ID, "SINGLE", "DRAFT", "2 BHK Flat • Nanda Nagar", 1, 1,
                "{\"rawPrompt\":\"2 BHK in Nanda Nagar\"}"
        );

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.empty());
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> {
            PropertyUploadDraft d = inv.getArgument(0);
            d.setId(1L);
            return d;
        });

        DraftDetailDTO result = service.saveOrUpdateDraft(ADMIN_ID, req);

        assertNotNull(result);
        assertEquals(DRAFT_ID, result.draftId());
        assertEquals("SINGLE", result.draftType());
        assertEquals("2 BHK Flat • Nanda Nagar", result.titleSummary());
        assertEquals(1, result.version());
        verify(draftRepository).save(any(PropertyUploadDraft.class));
    }

    @Test
    void saveOrUpdateDraft_updatesExistingDraftAndIncrementsVersion() {
        PropertyUploadDraft existing = new PropertyUploadDraft();
        existing.setId(1L);
        existing.setDraftId(DRAFT_ID);
        existing.setAdminId(ADMIN_ID);
        existing.setVersion(2);
        existing.setPayload("{\"rawPrompt\":\"old prompt\"}");

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(existing));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveDraftRequest req = new SaveDraftRequest(
                DRAFT_ID, "SINGLE", "DRAFT", "Updated Title", 1, 2,
                "{\"rawPrompt\":\"new prompt\"}"
        );

        DraftDetailDTO result = service.saveOrUpdateDraft(ADMIN_ID, req);

        assertNotNull(result);
        assertEquals(3, result.version());
        assertEquals("Updated Title", result.titleSummary());
    }

    @Test
    void saveOrUpdateDraft_throwsConflictOnStaleVersion() {
        PropertyUploadDraft existing = new PropertyUploadDraft();
        existing.setId(1L);
        existing.setDraftId(DRAFT_ID);
        existing.setAdminId(ADMIN_ID);
        existing.setVersion(3); // server has version 3

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(existing));

        // Client sends version 1
        SaveDraftRequest staleReq = new SaveDraftRequest(
                DRAFT_ID, "SINGLE", "DRAFT", "Stale Title", 1, 1,
                "{\"rawPrompt\":\"stale prompt\"}"
        );

        assertThrows(DraftConflictException.class, () -> service.saveOrUpdateDraft(ADMIN_ID, staleReq));
        verify(draftRepository, never()).save(any());
    }

    @Test
    void saveOrUpdateDraft_preventsCrossAdminOverwrite() {
        PropertyUploadDraft existing = new PropertyUploadDraft();
        existing.setId(1L);
        existing.setDraftId(DRAFT_ID);
        existing.setAdminId(OTHER_ADMIN_ID); // belongs to another admin

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(existing));

        SaveDraftRequest req = new SaveDraftRequest(
                DRAFT_ID, "SINGLE", "DRAFT", "Attack Title", 1, 1,
                "{\"rawPrompt\":\"hijack\"}"
        );

        assertThrows(EntityNotFoundException.class, () -> service.saveOrUpdateDraft(ADMIN_ID, req));
        verify(draftRepository, never()).save(any());
    }

    @Test
    void listDrafts_returnsSummariesSorted() {
        PropertyUploadDraft d1 = new PropertyUploadDraft();
        d1.setDraftId("draft-1");
        d1.setAdminId(ADMIN_ID);
        d1.setDraftType("SINGLE");
        d1.setTitleSummary("Draft 1");
        d1.setUpdatedAt(LocalDateTime.now());

        when(draftRepository.findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(ADMIN_ID, "DISCARDED"))
                .thenReturn(List.of(d1));
        when(draftMediaRepository.findAllByDraftIdAndAdminId("draft-1", ADMIN_ID))
                .thenReturn(List.of());

        List<DraftSummaryDTO> list = service.listDrafts(ADMIN_ID);

        assertEquals(1, list.size());
        assertEquals("draft-1", list.get(0).draftId());
    }

    @Test
    void discardDraft_removesStagedMediaAndDeletesRecord() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));

        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setMediaId("dm-1");
        media.setStagingObjectKey("drafts/admin/draft-1234-abcd/dm-1_photo.jpg");
        when(draftMediaRepository.findAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(List.of(media));

        service.discardDraft(ADMIN_ID, DRAFT_ID);

        verify(mediaStagingService).delete("drafts/admin/draft-1234-abcd/dm-1_photo.jpg");
        verify(draftMediaRepository).deleteAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID);
        verify(draftRepository).delete(draft);
    }

    @Test
    void stageDraftMedia_validatesSizeAndCoverRules() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));

        // Video cannot be cover
        MockMultipartFile videoFile = new MockMultipartFile(
                "file", "tour.mp4", "video/mp4", new byte[100]
        );
        assertThrows(IllegalArgumentException.class, () ->
                service.stageDraftMedia(ADMIN_ID, DRAFT_ID, videoFile, null, "LIVING_ROOM", true)
        );

        // Photo upload succeeds
        MockMultipartFile photoFile = new MockMultipartFile(
                "file", "living.jpg", "image/jpeg", new byte[500]
        );
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        DraftMediaDTO result = service.stageDraftMedia(ADMIN_ID, DRAFT_ID, photoFile, null, "LIVING_ROOM", true);
        assertNotNull(result);
        assertEquals("living.jpg", result.originalFilename());
        assertTrue(result.isCover());
        verify(mediaStagingService).stage(startsWith("drafts/"), any(), eq(500L), eq("image/jpeg"));
    }

    @Test
    void reconcileBatchDraft_removesOnlyPublishedCards() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setItemCount(3);
        draft.setVersion(1);
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-1\",\"title\":\"Prop 1\"},{\"id\":\"card-2\",\"title\":\"Prop 2\"},{\"id\":\"card-3\",\"title\":\"Prop 3\"}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Published card-1 and card-2; card-3 failed/pending
        DraftDetailDTO reconciled = service.reconcileBatchDraft(ADMIN_ID, DRAFT_ID, List.of("card-1", "card-2"));

        assertNotNull(reconciled);
        assertTrue(draft.getPayload().contains("card-3"));
        assertFalse(draft.getPayload().contains("card-1"));
        assertFalse(draft.getPayload().contains("card-2"));
        assertEquals(1, draft.getItemCount());
        verify(draftRepository).save(draft);
    }

    @Test
    void reconcileBatchDraft_withCompletedListings_storesCompletedListingsAndUpdatesTitle() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setItemCount(2);
        draft.setTitleSummary("Batch (2 properties)");
        draft.setVersion(1);
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-1\",\"title\":\"Prop 1\"},{\"id\":\"card-2\",\"title\":\"Prop 2\"}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> completedItem = Map.of(
                "cardId", "card-1",
                "listingId", 49L,
                "title", "Prop 1"
        );

        DraftDetailDTO reconciled = service.reconcileBatchDraft(ADMIN_ID, DRAFT_ID, List.of("card-1"), List.of(completedItem));

        assertNotNull(reconciled);
        assertEquals(1, draft.getItemCount());
        assertEquals("Batch — 1 property remaining", draft.getTitleSummary());
        assertTrue(draft.getPayload().contains("completedListings"));
        assertTrue(draft.getPayload().contains("49"));
        assertTrue(draft.getPayload().contains("card-2"));
        verify(draftRepository).save(draft);
    }

    @Test
    void reconcileBatchDraft_whenAllCardsPublished_tombstonesSuccessfullyWithoutPrematureWarning() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setItemCount(1);
        draft.setStatus("PUBLISHING");
        draft.setVersion(2);
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-1\",\"title\":\"Final Property\"}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setDraftId(DRAFT_ID);
        media.setAdminId(ADMIN_ID);
        media.setCardId("card-1");
        media.setStagingObjectKey("drafts/admin/card-1_img.webp");
        when(draftMediaRepository.findAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(List.of(media));

        DraftDetailDTO result = service.reconcileBatchDraft(ADMIN_ID, DRAFT_ID, List.of("card-1"));

        // When all cards are published, returns null (tombstone)
        assertNull(result);
        assertEquals("PUBLISHED", draft.getStatus());
        assertEquals("{}", draft.getPayload());
        assertEquals(0, draft.getItemCount());
        verify(mediaStagingService).delete("drafts/admin/card-1_img.webp");
        verify(draftMediaRepository).deleteAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID);
        verify(draftRepository).save(draft);
    }

    @Test
    void reconcileBatchDraft_doesNotDeleteStagedMediaBeforePayloadSaved() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setItemCount(2);
        draft.setVersion(1);
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-1\",\"title\":\"Prop 1\"},{\"id\":\"card-2\",\"title\":\"Prop 2\"}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        // Simulate DB failure during payload update
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenThrow(new RuntimeException("DB Connection Timeout"));

        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setDraftId(DRAFT_ID);
        media.setAdminId(ADMIN_ID);
        media.setCardId("card-1");
        media.setStagingObjectKey("drafts/admin/card-1_img.webp");
        when(draftMediaRepository.findAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(List.of(media));

        assertThrows(RuntimeException.class, () -> service.reconcileBatchDraft(ADMIN_ID, DRAFT_ID, List.of("card-1")));

        // Staging media must NOT have been deleted because payload save failed
        verify(draftMediaRepository, never()).delete(any(PropertyDraftMedia.class));
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void getDraft_autoReconcilesInterruptedCardsWithExistingListingsAndPermanentAssets() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setStatus("PUBLISHING");
        draft.setItemCount(1);
        draft.setVersion(5);
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-int\",\"title\":\"Interrupted Property\",\"stagedMedia\":[{\"id\":\"m-1\"},{\"id\":\"m-2\"}]}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Listing exists in DB for this card
        Listing existingListing = mock(Listing.class);
        when(existingListing.getId()).thenReturn(99L);
        when(existingListing.getTitle()).thenReturn("Interrupted Property");
        when(existingListing.getOriginDraftId()).thenReturn(DRAFT_ID + ":card-int");
        when(listingRepository.findByOriginDraftId(DRAFT_ID + ":card-int")).thenReturn(Optional.of(existingListing));

        // Both media items are present in property_media_assets
        PropertyMediaAsset asset1 = new PropertyMediaAsset();
        asset1.setId(1L);
        asset1.setListingId(99L);
        asset1.setUploadRequestId("m-1");
        asset1.setMediaUrl("https://cloudinary.com/m-1.webp");

        PropertyMediaAsset asset2 = new PropertyMediaAsset();
        asset2.setId(2L);
        asset2.setListingId(99L);
        asset2.setUploadRequestId("m-2");
        asset2.setMediaUrl("https://cloudinary.com/m-2.webp");

        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(99L)).thenReturn(List.of(asset1, asset2));

        DraftDetailDTO detail = service.getDraft(ADMIN_ID, DRAFT_ID);

        assertNotNull(detail);
        // Since card-int had all media completed, it was auto-reconciled and converted to PUBLISHED tombstone with completedListings summary
        assertEquals("PUBLISHED", detail.status());
        assertTrue(detail.payload().contains("\"completedListings\""));
        assertTrue(detail.payload().contains("\"listingId\":99"));
        assertFalse(detail.payload().contains("\"stagedCards\""));
        assertEquals(0, detail.itemCount());
    }

    @Test
    void getDraft_reconstructsMediaFromPermanentAssets_whenStagedMediaRowsDeleted() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setDraftType("BATCH");
        draft.setStatus("PUBLISHING");
        draft.setItemCount(1);
        draft.setVersion(3);
        // Card expected 3 media files, but only 2 reached permanent storage (partial publish)
        draft.setPayload("{\"stagedCards\":[{\"id\":\"card-partial\",\"title\":\"Partial Prop\",\"stagedMedia\":[{\"id\":\"m-1\"},{\"id\":\"m-2\"},{\"id\":\"m-3\"}]}]}");

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Listing exists in DB
        Listing existingListing = mock(Listing.class);
        when(existingListing.getId()).thenReturn(101L);
        when(existingListing.getTitle()).thenReturn("Partial Prop");
        when(existingListing.getOriginDraftId()).thenReturn(DRAFT_ID + ":card-partial");
        when(listingRepository.findByOriginDraftId(DRAFT_ID + ":card-partial")).thenReturn(Optional.of(existingListing));

        // Only 2 of 3 assets reached DB
        PropertyMediaAsset asset1 = new PropertyMediaAsset();
        asset1.setId(201L);
        asset1.setListingId(101L);
        asset1.setUploadRequestId("m-1");
        asset1.setMediaUrl("https://cloudinary.com/m-1.webp");

        PropertyMediaAsset asset2 = new PropertyMediaAsset();
        asset2.setId(202L);
        asset2.setListingId(101L);
        asset2.setUploadRequestId("m-2");
        asset2.setMediaUrl("https://cloudinary.com/m-2.webp");

        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(101L)).thenReturn(List.of(asset1, asset2));
        // Staged media table was wiped (0 rows)
        when(draftMediaRepository.findAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(List.of());

        DraftDetailDTO detail = service.getDraft(ADMIN_ID, DRAFT_ID);

        assertNotNull(detail);
        // Staged card remains because 1 media file is still pending
        assertEquals(1, detail.itemCount());
        // Media reconstructed from permanent assets!
        assertEquals(2, detail.media().size());
        assertEquals("m-1", detail.media().get(0).mediaId());
        assertEquals("https://cloudinary.com/m-1.webp", detail.media().get(0).previewUrl());
        assertEquals("m-2", detail.media().get(1).mediaId());
        assertEquals("https://cloudinary.com/m-2.webp", detail.media().get(1).previewUrl());
    }

    @Test
    void stageDraftMedia_autoEstablishesDraftIfAbsent() {
        // Draft does not exist yet on server
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.empty());
        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.empty());
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile photoFile = new MockMultipartFile(
                "file", "bedroom.jpg", "image/jpeg", new byte[300]
        );
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        DraftMediaDTO result = service.stageDraftMedia(ADMIN_ID, DRAFT_ID, photoFile, "c-1", "BEDROOM", false);

        assertNotNull(result);
        assertEquals("bedroom.jpg", result.originalFilename());
        // Verify server auto-created the draft row
        verify(draftRepository).save(argThat(d -> DRAFT_ID.equals(d.getDraftId()) && ADMIN_ID.equals(d.getAdminId())));
    }

    @Test
    void stageDraftMedia_cleansB2ObjectIfDbSaveFails() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));

        MockMultipartFile photoFile = new MockMultipartFile(
                "file", "balcony.jpg", "image/jpeg", new byte[400]
        );
        // Simulate DB failure on saving media entity
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenThrow(new RuntimeException("DB disk full"));

        assertThrows(RuntimeException.class, () ->
                service.stageDraftMedia(ADMIN_ID, DRAFT_ID, photoFile, "c-1", "BALCONY", false)
        );

        // Verify staged object is cleaned up from object storage
        verify(mediaStagingService).delete(startsWith("drafts/"));
    }

    @Test
    void purgeInactiveDrafts_draftInactiveFor15Days_purgesDraftAndStagedMedia() {
        PropertyUploadDraft expiredDraft = new PropertyUploadDraft();
        expiredDraft.setId(10L);
        expiredDraft.setDraftId("draft-expired-15d");
        expiredDraft.setAdminId(ADMIN_ID);
        expiredDraft.setStatus("DRAFT");
        expiredDraft.setUpdatedAt(LocalDateTime.now().minusDays(15));

        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setId(101L);
        media.setMediaId("dm-expired");
        media.setDraftId("draft-expired-15d");
        media.setAdminId(ADMIN_ID);
        media.setStagingObjectKey("drafts/admin/draft-expired-15d/dm-expired_old.jpg");

        when(draftRepository.findAllByStatusNotInAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(expiredDraft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId("draft-expired-15d", ADMIN_ID))
                .thenReturn(List.of(media));

        int purgedCount = service.purgeInactiveDrafts();

        assertEquals(1, purgedCount);
        verify(mediaStagingService).delete("drafts/admin/draft-expired-15d/dm-expired_old.jpg");
        verify(draftMediaRepository).deleteAllByDraftIdAndAdminId("draft-expired-15d", ADMIN_ID);
        verify(draftRepository).delete(expiredDraft);
    }

    @Test
    void purgeInactiveDrafts_draftInactiveFor14Days_isRetained() {
        // Query for drafts before now - 15 days returns empty for 14-day inactive draft
        when(draftRepository.findAllByStatusNotInAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());

        int purgedCount = service.purgeInactiveDrafts();

        assertEquals(0, purgedCount);
        verify(draftRepository, never()).delete(any());
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void purgeInactiveDrafts_skipsPublishedTombstoneDrafts() {
        PropertyUploadDraft publishedTombstone = new PropertyUploadDraft();
        publishedTombstone.setId(20L);
        publishedTombstone.setDraftId("draft-published-tombstone");
        publishedTombstone.setAdminId(ADMIN_ID);
        publishedTombstone.setStatus("PUBLISHED");
        publishedTombstone.setPublishedPropertyId(505L);
        publishedTombstone.setUpdatedAt(LocalDateTime.now().minusDays(20));

        when(draftRepository.findAllByStatusNotInAndUpdatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(publishedTombstone));

        int purgedCount = service.purgeInactiveDrafts();

        assertEquals(0, purgedCount);
        verify(draftRepository, never()).delete(publishedTombstone);
    }

    @Test
    void discardDraft_onPublishedDraft_preservesTombstone() {
        PropertyUploadDraft publishedDraft = new PropertyUploadDraft();
        publishedDraft.setDraftId("draft-published-keep");
        publishedDraft.setAdminId(ADMIN_ID);
        publishedDraft.setStatus("PUBLISHED");
        publishedDraft.setPublishedPropertyId(888L);

        when(draftRepository.findByDraftIdAndAdminId("draft-published-keep", ADMIN_ID))
                .thenReturn(Optional.of(publishedDraft));

        service.discardDraft(ADMIN_ID, "draft-published-keep");

        // Discard must NOT delete published draft tombstone or cause errors
        verify(draftRepository, never()).delete(any());
    }

    @Test
    void onPropertyPublished_purgesStagedMediaAndCreatesPublishedTombstone() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setStatus("DRAFT");
        draft.setPayload("{\"title\":\"2 BHK to Publish\"}");
        draft.setItemCount(1);

        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setMediaId("dm-to-clean");
        media.setDraftId(DRAFT_ID);
        media.setAdminId(ADMIN_ID);
        media.setStagingObjectKey("drafts/admin/d-1/media.jpg");

        when(draftMediaRepository.findAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(List.of(media));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(Optional.of(draft));

        service.onPropertyPublished(ADMIN_ID, DRAFT_ID);

        // Staged media must be purged from object storage and draft media table
        verify(mediaStagingService).delete("drafts/admin/d-1/media.jpg");
        verify(draftMediaRepository).deleteAllByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID);

        // Draft record converted to lightweight PUBLISHED tombstone
        assertEquals("PUBLISHED", draft.getStatus());
        assertEquals("{}", draft.getPayload());
        assertEquals(0, draft.getItemCount());
        verify(draftRepository).save(draft);
    }

    @Test
    void stageDraftMedia_resetsDraftUpdatedAt() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);
        LocalDateTime oldUpdatedAt = LocalDateTime.now().minusDays(10);
        draft.setUpdatedAt(oldUpdatedAt);

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(Optional.of(draft));
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[500]);
        service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "c-1", "BEDROOM", false);

        // Verify draft updatedAt is updated exclusively in DB via touchUpdatedAt, leaving managed entity clean
        verify(draftRepository).touchUpdatedAt(eq(DRAFT_ID), eq(ADMIN_ID), any(LocalDateTime.class));
        verify(draftRepository, never()).save(draft);
        assertEquals(oldUpdatedAt, draft.getUpdatedAt(), "Managed draft entity must remain clean in memory");
        assertEquals(1, draft.getVersion(), "Media staging must not increment parent draft @Version");
    }

    @Test
    void stageDraftMedia_multipleOperationsDoNotAdvanceParentOptimisticVersion() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);
        LocalDateTime oldUpdatedAt = LocalDateTime.now().minusHours(1);
        draft.setUpdatedAt(oldUpdatedAt);

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(Optional.of(draft));
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        // Stage multiple media files
        for (int i = 0; i < 5; i++) {
            MockMultipartFile file = new MockMultipartFile("file", "photo_" + i + ".jpg", "image/jpeg", new byte[500]);
            service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "c-1", "ROOM", false);
        }

        // Verify version remains completely unchanged at 1 and entity remains untouched
        assertEquals(1, draft.getVersion(), "Multiple media stagings must never advance parent draft version");
        assertEquals(oldUpdatedAt, draft.getUpdatedAt(), "Managed draft entity must remain clean across multiple stagings");
        verify(draftRepository, times(5)).touchUpdatedAt(eq(DRAFT_ID), eq(ADMIN_ID), any(LocalDateTime.class));
        verify(draftRepository, never()).save(draft);
    }

    @Test
    void saveOrUpdateDraft_legitimatePayloadEditStillAdvancesVersionAndProtectsOptimisticLock() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);
        draft.setPayload("{\"step\":1}");

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Legitimate save with version 1 -> advances version to 2
        SaveDraftRequest req1 = new SaveDraftRequest(DRAFT_ID, "BATCH", "DRAFT", "Updated Title", 2, 1, "{\"step\":2}");
        DraftDetailDTO res1 = service.saveOrUpdateDraft(ADMIN_ID, req1);
        assertEquals(2, res1.version(), "Legitimate content save must advance draft version");
        verify(draftRepository, times(1)).save(draft);

        // 2. Outdated client sends stale version 1 when server is at version 2 -> DraftConflictException
        SaveDraftRequest staleReq = new SaveDraftRequest(DRAFT_ID, "BATCH", "DRAFT", "Stale Title", 2, 1, "{\"step\":3}");
        DraftConflictException ex = assertThrows(DraftConflictException.class, () ->
                service.saveOrUpdateDraft(ADMIN_ID, staleReq)
        );
        assertEquals(2, ex.getServerVersion());
        assertTrue(ex.getMessage().contains("newer version"));
    }

    @Test
    void listDrafts_excludesPublishedDrafts() {
        PropertyUploadDraft activeDraft = new PropertyUploadDraft();
        activeDraft.setDraftId("draft-active");
        activeDraft.setAdminId(ADMIN_ID);
        activeDraft.setStatus("DRAFT");
        activeDraft.setPublishedPropertyId(null);

        PropertyUploadDraft publishedDraft = new PropertyUploadDraft();
        publishedDraft.setDraftId("draft-published");
        publishedDraft.setAdminId(ADMIN_ID);
        publishedDraft.setStatus("PUBLISHED");
        publishedDraft.setPublishedPropertyId(999L);

        when(draftRepository.findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(ADMIN_ID, "DISCARDED"))
                .thenReturn(List.of(activeDraft, publishedDraft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(anyString(), anyString())).thenReturn(List.of());

        List<DraftSummaryDTO> summaries = service.listDrafts(ADMIN_ID);

        assertEquals(1, summaries.size());
        assertEquals("draft-active", summaries.get(0).draftId());
    }

    @Test
    void multiDraft_sameAdmin_draftsAandB_surviveIndependently() {
        String draftA = "draft-single-aaaa";
        String draftB = "draft-single-bbbb";

        PropertyUploadDraft entityA = new PropertyUploadDraft();
        entityA.setDraftId(draftA);
        entityA.setAdminId(ADMIN_ID);
        entityA.setDraftType("SINGLE");
        entityA.setTitleSummary("Draft A: 2 BHK Flat");
        entityA.setPayload("{\"title\":\"2 BHK Flat\"}");
        entityA.setVersion(1);

        PropertyUploadDraft entityB = new PropertyUploadDraft();
        entityB.setDraftId(draftB);
        entityB.setAdminId(ADMIN_ID);
        entityB.setDraftType("SINGLE");
        entityB.setTitleSummary("Draft B: 3 BHK Villa");
        entityB.setPayload("{\"title\":\"3 BHK Villa\"}");
        entityB.setVersion(1);

        when(draftRepository.findByDraftId(draftA)).thenReturn(Optional.of(entityA));
        when(draftRepository.findByDraftId(draftB)).thenReturn(Optional.of(entityB));
        when(draftRepository.findByDraftIdAndAdminId(draftA, ADMIN_ID)).thenReturn(Optional.of(entityA));
        when(draftRepository.findByDraftIdAndAdminId(draftB, ADMIN_ID)).thenReturn(Optional.of(entityB));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Update Draft B to version 2 with new payload
        SaveDraftRequest updateB = new SaveDraftRequest(
                draftB, "SINGLE", "DRAFT", "Draft B: 3 BHK Villa (Updated)", 1, 1,
                "{\"title\":\"3 BHK Villa (Updated)\"}"
        );
        DraftDetailDTO updatedB = service.saveOrUpdateDraft(ADMIN_ID, updateB);

        assertEquals(2, updatedB.version());
        assertEquals("Draft B: 3 BHK Villa (Updated)", updatedB.titleSummary());

        // Verify Draft A remains at version 1 and its payload is unchanged
        DraftDetailDTO fetchedA = service.getDraft(ADMIN_ID, draftA);
        assertEquals(1, fetchedA.version());
        assertEquals("Draft A: 2 BHK Flat", fetchedA.titleSummary());
        assertEquals("{\"title\":\"2 BHK Flat\"}", fetchedA.payload());
    }

    @Test
    void discardDraft_isolation_onlyDeletesTargetDraftAndTargetMedia() {
        String draftA = "draft-single-keep";
        String draftB = "draft-single-discard";

        PropertyUploadDraft entityA = new PropertyUploadDraft();
        entityA.setDraftId(draftA);
        entityA.setAdminId(ADMIN_ID);

        PropertyUploadDraft entityB = new PropertyUploadDraft();
        entityB.setDraftId(draftB);
        entityB.setAdminId(ADMIN_ID);

        when(draftRepository.findByDraftIdAndAdminId(draftA, ADMIN_ID)).thenReturn(Optional.of(entityA));
        when(draftRepository.findByDraftIdAndAdminId(draftB, ADMIN_ID)).thenReturn(Optional.of(entityB));

        PropertyDraftMedia mediaB = new PropertyDraftMedia();
        mediaB.setDraftId(draftB);
        mediaB.setMediaId("dm-b-1");
        mediaB.setAdminId(ADMIN_ID);
        mediaB.setStagingObjectKey("drafts/admin/draft-single-discard/dm-b-1_pic.jpg");

        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftB, ADMIN_ID)).thenReturn(List.of(mediaB));

        // Discard Draft B
        service.discardDraft(ADMIN_ID, draftB);

        // Verify Draft B and its media deleted
        verify(mediaStagingService).delete("drafts/admin/draft-single-discard/dm-b-1_pic.jpg");
        verify(draftMediaRepository).deleteAllByDraftIdAndAdminId(draftB, ADMIN_ID);
        verify(draftRepository).delete(entityB);

        // Verify Draft A was NOT deleted
        verify(draftRepository, never()).delete(entityA);
        verify(draftMediaRepository, never()).deleteAllByDraftIdAndAdminId(eq(draftA), anyString());
    }

    @Test
    void stageDraftMedia_autosaveRace_completesSuccessfullyWhenVersionIncrementsDuringB2Upload() throws Exception {
        // Draft starts at version 1
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);
        draft.setPayload("{\"title\":\"Original version\"}");

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenAnswer(inv -> Optional.of(draft));
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> inv.getArgument(0));
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Simulate slow B2 upload during which an autosave commits and increments the draft version to 2
        doAnswer(invocation -> {
            // Concurrent autosave happens while B2 upload is in-flight:
            draft.setVersion(2);
            draft.setPayload("{\"title\":\"Updated by autosave during B2\"}");
            return null;
        }).when(mediaStagingService).stage(anyString(), any(), anyLong(), anyString());

        MockMultipartFile file = new MockMultipartFile("file", "hall.jpg", "image/jpeg", new byte[800]);
        DraftMediaDTO result = service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "card-1", "LIVING_ROOM", false);

        assertNotNull(result);
        assertEquals("hall.jpg", result.originalFilename());
        // Verify media was saved successfully despite version increment during B2 upload
        verify(draftMediaRepository).save(argThat(m -> "hall.jpg".equals(m.getOriginalFilename()) && DRAFT_ID.equals(m.getDraftId())));
        // Verify compensating cleanup was NOT called
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void stageDraftMedia_concurrentMediaAttachments_bothPersistWithoutLostUpdate() throws Exception {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenAnswer(inv -> Optional.of(draft));

        List<PropertyDraftMedia> savedMediaList = new CopyOnWriteArrayList<>();
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> {
            PropertyDraftMedia m = inv.getArgument(0);
            savedMediaList.add(m);
            return m;
        });
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<DraftMediaDTO> upload1 = () -> {
            latch.await();
            MockMultipartFile file1 = new MockMultipartFile("file", "photo1.jpg", "image/jpeg", new byte[500]);
            return service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file1, "card-1", "KITCHEN", false);
        };
        Callable<DraftMediaDTO> upload2 = () -> {
            latch.await();
            MockMultipartFile file2 = new MockMultipartFile("file", "photo2.jpg", "image/jpeg", new byte[500]);
            return service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file2, "card-1", "BEDROOM", false);
        };

        Future<DraftMediaDTO> f1 = executor.submit(upload1);
        Future<DraftMediaDTO> f2 = executor.submit(upload2);
        latch.countDown(); // Launch both concurrently

        DraftMediaDTO res1 = f1.get(5, TimeUnit.SECONDS);
        DraftMediaDTO res2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertNotNull(res1);
        assertNotNull(res2);
        assertNotEquals(res1.mediaId(), res2.mediaId());
        assertEquals(2, savedMediaList.size());
        // Verify no duplicate media records and no lost update
        assertTrue(savedMediaList.stream().anyMatch(m -> "photo1.jpg".equals(m.getOriginalFilename())));
        assertTrue(savedMediaList.stream().anyMatch(m -> "photo2.jpg".equals(m.getOriginalFilename())));
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void stageDraftMedia_multipleFiles_allProduceDistinctMediaRecords() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenAnswer(inv -> Optional.of(draft));

        List<PropertyDraftMedia> savedMediaList = new ArrayList<>();
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> {
            PropertyDraftMedia m = inv.getArgument(0);
            savedMediaList.add(m);
            return m;
        });
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        // Stage 5 files in sequence
        for (int i = 1; i <= 5; i++) {
            MockMultipartFile file = new MockMultipartFile("file", "img" + i + ".jpg", "image/jpeg", new byte[300]);
            DraftMediaDTO dto = service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "card-1", "ROOM", i == 1);
            assertNotNull(dto);
        }

        assertEquals(5, savedMediaList.size());
        long uniqueMediaIds = savedMediaList.stream().map(PropertyDraftMedia::getMediaId).distinct().count();
        assertEquals(5, uniqueMediaIds);
        // Verify exactly one media DB record per uploaded file
        verify(draftMediaRepository, times(5)).save(any(PropertyDraftMedia.class));
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void stageDraftMedia_permanentDbFailure_triggersCompensatingB2Cleanup() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(mediaStagingService.exists(anyString())).thenReturn(true);

        // Database permanently fails on media record save
        when(draftMediaRepository.save(any(PropertyDraftMedia.class)))
                .thenThrow(new RuntimeException("Database connection terminated"));

        MockMultipartFile file = new MockMultipartFile("file", "corrupt.jpg", "image/jpeg", new byte[400]);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "card-1", "LIVING_ROOM", false)
        );
        assertEquals("Database connection terminated", ex.getMessage());

        // Verify newly staged B2 object was deleted to prevent untracked orphans
        verify(mediaStagingService).delete(argThat(key -> key.contains(DRAFT_ID) && key.contains("corrupt.jpg")));
    }

    @Test
    void stageDraftMedia_adminIsolation_preventsAttachingToAnotherAdminsDraft() {
        PropertyUploadDraft otherDraft = new PropertyUploadDraft();
        otherDraft.setDraftId(DRAFT_ID);
        otherDraft.setAdminId(OTHER_ADMIN_ID);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(otherDraft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[500]);

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () ->
                service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "c-1", "BEDROOM", false)
        );
        assertTrue(ex.getMessage().contains("access denied"));

        // Verify B2 stage was never called for unauthorized request
        verify(mediaStagingService, never()).stage(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void stageDraftMedia_transientConcurrencyConflict_retriesAndSucceeds() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setVersion(1);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));

        AtomicInteger attempts = new AtomicInteger(0);
        when(draftMediaRepository.save(any(PropertyDraftMedia.class))).thenAnswer(inv -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                // Attempt 1 fails with transient optimistic locking conflict
                throw new ObjectOptimisticLockingFailureException(PropertyUploadDraft.class, 1L);
            }
            // Attempt 2 succeeds
            return inv.getArgument(0);
        });
        when(draftRepository.save(any(PropertyUploadDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "retry.jpg", "image/jpeg", new byte[500]);
        DraftMediaDTO result = service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "c-1", "BALCONY", false);

        assertNotNull(result);
        assertEquals(2, attempts.get());
        // Verify media was successfully persisted on attempt 2
        verify(draftMediaRepository, times(2)).save(any(PropertyDraftMedia.class));
        // Verify compensating cleanup was NOT called because retry succeeded
        verify(mediaStagingService, never()).delete(anyString());
    }

    @Test
    void stageDraftMedia_retryExhaustion_throwsIllegalStateExceptionAndCleansB2() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(mediaStagingService.exists(anyString())).thenReturn(true);

        // All 3 attempts throw transient lock conflict
        when(draftMediaRepository.save(any(PropertyDraftMedia.class)))
                .thenThrow(new CannotAcquireLockException("Lock timeout on draft media"));

        MockMultipartFile file = new MockMultipartFile("file", "exhausted.jpg", "image/jpeg", new byte[500]);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, "c-1", "BALCONY", false)
        );
        assertTrue(ex.getMessage().contains("after 3 attempts"));

        // Verify all 3 retry attempts were made
        verify(draftMediaRepository, times(3)).save(any(PropertyDraftMedia.class));
        // Verify compensating B2 cleanup was executed upon retry exhaustion
        verify(mediaStagingService).delete(argThat(key -> key.contains(DRAFT_ID) && key.contains("exhausted.jpg")));
    }

    @Test
    void getDraftMediaStream_succeedsWithoutHeadRequest() {
        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setMediaId("media-123");
        media.setDraftId(DRAFT_ID);
        media.setAdminId(ADMIN_ID);
        media.setStagingObjectKey("drafts/" + ADMIN_ID + "/" + DRAFT_ID + "/media-123-hall.jpg");
        media.setContentType("image/jpeg");
        media.setFileSizeBytes(1024L);
        media.setOriginalFilename("hall.jpg");

        when(draftMediaRepository.findByMediaIdAndAdminId("media-123", ADMIN_ID)).thenReturn(Optional.of(media));
        java.io.ByteArrayInputStream mockStream = new java.io.ByteArrayInputStream("fake-data".getBytes());
        when(mediaStagingService.retrieve(media.getStagingObjectKey())).thenReturn(mockStream);

        PropertyDraftService.StagedMediaStream result = service.getDraftMediaStream(ADMIN_ID, DRAFT_ID, "media-123");

        assertNotNull(result);
        assertEquals("image/jpeg", result.contentType());
        assertEquals(1024L, result.contentLength());
        assertEquals("hall.jpg", result.originalFilename());
        // Verify mediaStagingService.exists was NEVER called (redundant HEAD removed)
        verify(mediaStagingService, never()).exists(anyString());
        // Verify retrieve was called directly
        verify(mediaStagingService).retrieve(media.getStagingObjectKey());
    }

    @Test
    void getDraftMediaStream_throwsEntityNotFoundWhenB2ObjectMissing() {
        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setMediaId("media-missing");
        media.setDraftId(DRAFT_ID);
        media.setAdminId(ADMIN_ID);
        media.setStagingObjectKey("drafts/" + ADMIN_ID + "/" + DRAFT_ID + "/missing.jpg");

        when(draftMediaRepository.findByMediaIdAndAdminId("media-missing", ADMIN_ID)).thenReturn(Optional.of(media));
        when(mediaStagingService.retrieve(media.getStagingObjectKey()))
                .thenThrow(new IllegalStateException("Staged media object has expired or is missing"));

        assertThrows(EntityNotFoundException.class, () ->
                service.getDraftMediaStream(ADMIN_ID, DRAFT_ID, "media-missing")
        );
        // Verify exists was never called
        verify(mediaStagingService, never()).exists(anyString());
    }

    @Test
    void getDraftMediaStream_throwsEntityNotFoundWhenDraftIdMismatch() {
        PropertyDraftMedia media = new PropertyDraftMedia();
        media.setMediaId("media-other");
        media.setDraftId("different-draft-id");
        media.setAdminId(ADMIN_ID);

        when(draftMediaRepository.findByMediaIdAndAdminId("media-other", ADMIN_ID)).thenReturn(Optional.of(media));

        assertThrows(EntityNotFoundException.class, () ->
                service.getDraftMediaStream(ADMIN_ID, DRAFT_ID, "media-other")
        );
    }

    @Test
    void listDrafts_includesDraftsInPublishingStateWithPublishedPropertyId() {
        PropertyUploadDraft publishingDraft = new PropertyUploadDraft();
        publishingDraft.setDraftId("draft-publishing-1");
        publishingDraft.setAdminId(ADMIN_ID);
        publishingDraft.setStatus("PUBLISHING");
        publishingDraft.setPublishedPropertyId(40L);
        publishingDraft.setTitleSummary("3 BHK in Vijay Nagar");
        publishingDraft.setItemCount(1);
        publishingDraft.setVersion(2);
        publishingDraft.setPayload("{\"title\":\"3 BHK in Vijay Nagar\"}");

        PropertyUploadDraft publishedDraft = new PropertyUploadDraft();
        publishedDraft.setDraftId("draft-published-2");
        publishedDraft.setAdminId(ADMIN_ID);
        publishedDraft.setStatus("PUBLISHED");
        publishedDraft.setPublishedPropertyId(41L);

        when(draftRepository.findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(ADMIN_ID, "DISCARDED"))
                .thenReturn(List.of(publishingDraft, publishedDraft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId("draft-publishing-1", ADMIN_ID)).thenReturn(List.of());

        List<DraftSummaryDTO> summaries = service.listDrafts(ADMIN_ID);

        assertEquals(1, summaries.size(), "Only PUBLISHING draft should be listed; terminal PUBLISHED must be excluded");
        DraftSummaryDTO s = summaries.get(0);
        assertEquals("draft-publishing-1", s.draftId());
        assertEquals("PUBLISHING", s.status());
        assertEquals(40L, s.publishedPropertyId());
    }

    @Test
    void onPropertyPublished_validatesListingIdMismatch() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setStatus("PUBLISHING");
        draft.setPublishedPropertyId(40L);

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(Optional.of(draft));

        assertThrows(IllegalArgumentException.class, () ->
                service.onPropertyPublished(ADMIN_ID, DRAFT_ID, 999L)
        );
    }

    @Test
    void onPropertyPublished_idempotentWhenAlreadyPublished() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setStatus("PUBLISHED");
        draft.setPublishedPropertyId(40L);

        when(draftRepository.findByDraftIdAndAdminId(DRAFT_ID, ADMIN_ID))
                .thenReturn(Optional.of(draft));

        // Should return cleanly without throwing or touching repositories
        service.onPropertyPublished(ADMIN_ID, DRAFT_ID, 40L);

        verify(mediaStagingService, never()).delete(anyString());
        verify(draftMediaRepository, never()).deleteAllByDraftIdAndAdminId(anyString(), anyString());
    }

    @Test
    void saveOrUpdateDraft_rejectsModifyingPublishedDraft() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setAdminId(ADMIN_ID);
        draft.setStatus("PUBLISHED");
        draft.setVersion(1);

        when(draftRepository.findByDraftId(DRAFT_ID)).thenReturn(Optional.of(draft));

        SaveDraftRequest req = new SaveDraftRequest(DRAFT_ID, "SINGLE", "DRAFT", "New Title", 1, 1, "{\"title\":\"edit\"}");

        assertThrows(IllegalStateException.class, () ->
                service.saveOrUpdateDraft(ADMIN_ID, req)
        );
    }

    @Test
    void reassignUnassignedMediaToCard_updatesMatchingRecordsAndTouchesDraft() {
        String targetCardId = "staged-0-1789853815627";
        when(draftMediaRepository.reassignUnassignedMediaToCard(DRAFT_ID, ADMIN_ID, targetCardId)).thenReturn(3);

        int updated = service.reassignUnassignedMediaToCard(ADMIN_ID, DRAFT_ID, targetCardId);

        assertEquals(3, updated);
        verify(draftMediaRepository).reassignUnassignedMediaToCard(DRAFT_ID, ADMIN_ID, targetCardId);
        verify(draftRepository).touchUpdatedAt(eq(DRAFT_ID), eq(ADMIN_ID), any(LocalDateTime.class));
    }

    @Test
    void reassignUnassignedMediaToCard_rejectsBlankCardId() {
        assertThrows(IllegalArgumentException.class, () ->
                service.reassignUnassignedMediaToCard(ADMIN_ID, DRAFT_ID, "")
        );
        assertThrows(IllegalArgumentException.class, () ->
                service.reassignUnassignedMediaToCard(ADMIN_ID, DRAFT_ID, "   ")
        );
    }
}
