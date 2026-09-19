package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.draft.DraftDetailDTO;
import com.indore.pathome.spaces.dto.draft.DraftMediaDTO;
import com.indore.pathome.spaces.dto.draft.DraftSummaryDTO;
import com.indore.pathome.spaces.dto.draft.SaveDraftRequest;
import com.indore.pathome.spaces.service.PropertyDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PropertyDraftControllerTest {

    private PropertyDraftService draftService;
    private PropertyDraftController controller;

    private static final String ADMIN_ID = "admin@pathome.in";
    private static final String DRAFT_ID = "draft-1234-abcd";

    @BeforeEach
    void setUp() {
        draftService = mock(PropertyDraftService.class);
        controller = new PropertyDraftController(draftService);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn(ADMIN_ID);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);
    }

    @Test
    void listDrafts_returnsOkWithSummaries() {
        DraftSummaryDTO dto = new DraftSummaryDTO(
                DRAFT_ID, "SINGLE", "DRAFT", "2 BHK Flat", 1, 1, 2,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(draftService.listDrafts(ADMIN_ID)).thenReturn(List.of(dto));

        ResponseEntity<List<DraftSummaryDTO>> response = controller.listDrafts();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(DRAFT_ID, response.getBody().get(0).draftId());
    }

    @Test
    void getDraft_returnsOkWithDetail() {
        DraftDetailDTO detail = new DraftDetailDTO(
                DRAFT_ID, "SINGLE", "DRAFT", "2 BHK Flat", 1, 1,
                "{}", List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        when(draftService.getDraft(ADMIN_ID, DRAFT_ID)).thenReturn(detail);

        ResponseEntity<DraftDetailDTO> response = controller.getDraft(DRAFT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("2 BHK Flat", response.getBody().titleSummary());
    }

    @Test
    void saveDraft_returnsOkWithSavedDraft() {
        SaveDraftRequest request = new SaveDraftRequest(
                DRAFT_ID, "SINGLE", "DRAFT", "New Flat", 1, 1, "{}"
        );
        DraftDetailDTO detail = new DraftDetailDTO(
                DRAFT_ID, "SINGLE", "DRAFT", "New Flat", 1, 1,
                "{}", List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        when(draftService.saveOrUpdateDraft(eq(ADMIN_ID), any(SaveDraftRequest.class))).thenReturn(detail);

        ResponseEntity<DraftDetailDTO> response = controller.saveDraft(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("New Flat", response.getBody().titleSummary());
    }

    @Test
    void discardDraft_returnsNoContent() {
        doNothing().when(draftService).discardDraft(ADMIN_ID, DRAFT_ID);

        ResponseEntity<Void> response = controller.discardDraft(DRAFT_ID);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(draftService).discardDraft(ADMIN_ID, DRAFT_ID);
    }

    @Test
    void stageMedia_returnsCreatedWithMediaDto() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[100]);
        DraftMediaDTO mediaDto = new DraftMediaDTO(
                "dm-1", DRAFT_ID, null, "photo.jpg", 100L, "image/jpeg", "LIVING_ROOM",
                true, "/api/v1/admin/drafts/draft-1234-abcd/media/dm-1", LocalDateTime.now()
        );

        when(draftService.stageDraftMedia(ADMIN_ID, DRAFT_ID, file, null, "LIVING_ROOM", true))
                .thenReturn(mediaDto);

        ResponseEntity<DraftMediaDTO> response = controller.stageMedia(DRAFT_ID, file, null, "LIVING_ROOM", true);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("dm-1", response.getBody().mediaId());
        assertTrue(response.getBody().isCover());
    }

    @Test
    void getMediaStream_returnsStreamResource() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[]{1, 2, 3});
        PropertyDraftService.StagedMediaStream streamInfo = new PropertyDraftService.StagedMediaStream(
                bais, "image/jpeg", 3L, "photo.jpg"
        );

        when(draftService.getDraftMediaStream(ADMIN_ID, DRAFT_ID, "dm-1")).thenReturn(streamInfo);

        ResponseEntity<InputStreamResource> response = controller.getMediaStream(DRAFT_ID, "dm-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertNotNull(response.getBody());
    }

    @Test
    void reconcileBatch_returnsRemainingCardsOrCleared() {
        DraftDetailDTO remaining = new DraftDetailDTO(
                DRAFT_ID, "BATCH", "DRAFT", "Batch remaining", 1, 2,
                "{}", List.of(), LocalDateTime.now(), LocalDateTime.now()
        );

        when(draftService.reconcileBatchDraft(ADMIN_ID, DRAFT_ID, List.of("card-1")))
                .thenReturn(remaining);

        ResponseEntity<?> response = controller.reconcileBatch(DRAFT_ID, Map.of("publishedCardIds", List.of("card-1")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(remaining, response.getBody());
    }

    @Test
    void listDrafts_unauthenticated_throwsAccessDeniedException() {
        SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> controller.listDrafts());
    }
}
