package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.draft.DraftDetailDTO;
import com.indore.pathome.spaces.dto.draft.DraftMediaDTO;
import com.indore.pathome.spaces.dto.draft.DraftSummaryDTO;
import com.indore.pathome.spaces.dto.draft.SaveDraftRequest;
import com.indore.pathome.spaces.service.PropertyDraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin REST controller for managing property upload drafts, auto-saving,
 * media staging, and recovery across sessions.
 */
@RestController
@RequestMapping("/api/v1/admin/drafts")
@PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PropertyDraftController {

    private static final Logger log = LoggerFactory.getLogger(PropertyDraftController.class);

    private final PropertyDraftService draftService;

    @Autowired
    public PropertyDraftController(PropertyDraftService draftService) {
        this.draftService = Objects.requireNonNull(draftService, "draftService must not be null");
    }

    private String getCurrentAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank() && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
            return auth.getName();
        }
        throw new AccessDeniedException("Authenticated admin principal required");
    }

    /**
     * Lists active drafts belonging to the authenticated admin.
     */
    @GetMapping
    public ResponseEntity<List<DraftSummaryDTO>> listDrafts() {
        String adminId = getCurrentAdminId();
        List<DraftSummaryDTO> drafts = draftService.listDrafts(adminId);
        return ResponseEntity.ok(drafts);
    }

    /**
     * Retrieves full detail and staged media list for a specific draft.
     */
    @GetMapping("/{draftId}")
    public ResponseEntity<DraftDetailDTO> getDraft(@PathVariable String draftId) {
        String adminId = getCurrentAdminId();
        DraftDetailDTO draft = draftService.getDraft(adminId, draftId);
        return ResponseEntity.ok(draft);
    }

    /**
     * Auto-saves or creates a property upload draft.
     */
    @PostMapping
    public ResponseEntity<DraftDetailDTO> saveDraft(@RequestBody SaveDraftRequest request) {
        String adminId = getCurrentAdminId();
        DraftDetailDTO saved = draftService.saveOrUpdateDraft(adminId, request);
        return ResponseEntity.ok(saved);
    }

    /**
     * Updates an existing draft with optimistic concurrency check.
     */
    @PutMapping("/{draftId}")
    public ResponseEntity<DraftDetailDTO> updateDraft(
            @PathVariable String draftId,
            @RequestBody SaveDraftRequest request
    ) {
        String adminId = getCurrentAdminId();
        SaveDraftRequest merged = new SaveDraftRequest(
                draftId,
                request.draftType(),
                request.status(),
                request.titleSummary(),
                request.itemCount(),
                request.version(),
                request.payload()
        );
        DraftDetailDTO saved = draftService.saveOrUpdateDraft(adminId, merged);
        return ResponseEntity.ok(saved);
    }

    /**
     * Explicitly discards a draft, removing temporary media from object storage.
     */
    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> discardDraft(@PathVariable String draftId) {
        String adminId = getCurrentAdminId();
        draftService.discardDraft(adminId, draftId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams an unpublished draft media file to private staging storage.
     */
    @PostMapping(value = "/{draftId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DraftMediaDTO> stageMedia(
            @PathVariable String draftId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "cardId", required = false) String cardId,
            @RequestParam(value = "roomTag", required = false, defaultValue = "LIVING_ROOM") String roomTag,
            @RequestParam(value = "isCover", required = false, defaultValue = "false") Boolean isCover,
            @RequestParam(value = "mediaId", required = false) String mediaId
    ) {
        String adminId = getCurrentAdminId();
        DraftMediaDTO dto = (mediaId != null && !mediaId.isBlank())
                ? draftService.stageDraftMedia(adminId, draftId, file, cardId, roomTag, isCover, mediaId)
                : draftService.stageDraftMedia(adminId, draftId, file, cardId, roomTag, isCover);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    public ResponseEntity<DraftMediaDTO> stageMedia(
            String draftId,
            MultipartFile file,
            String cardId,
            String roomTag,
            Boolean isCover
    ) {
        String adminId = getCurrentAdminId();
        DraftMediaDTO dto = draftService.stageDraftMedia(adminId, draftId, file, cardId, roomTag, isCover);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /**
     * Streams back a staged draft media file for preview in the admin browser.
     */
    @GetMapping("/{draftId}/media/{mediaId}")
    public ResponseEntity<InputStreamResource> getMediaStream(
            @PathVariable String draftId,
            @PathVariable String mediaId
    ) {
        String adminId = getCurrentAdminId();
        PropertyDraftService.StagedMediaStream streamInfo = draftService.getDraftMediaStream(adminId, draftId, mediaId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(streamInfo.contentType()));
        if (streamInfo.contentLength() > 0) {
            headers.setContentLength(streamInfo.contentLength());
        }
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");

        return new ResponseEntity<>(new InputStreamResource(streamInfo.inputStream()), headers, HttpStatus.OK);
    }

    /**
     * Deletes a staged draft media file from storage and database.
     */
    @DeleteMapping("/{draftId}/media/{mediaId}")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable String draftId,
            @PathVariable String mediaId
    ) {
        String adminId = getCurrentAdminId();
        draftService.deleteDraftMedia(adminId, draftId, mediaId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reassigns unassigned media items in a draft to a target card ID.
     */
    @PostMapping("/{draftId}/reassign-media")
    public ResponseEntity<Map<String, Object>> reassignMedia(
            @PathVariable String draftId,
            @RequestBody Map<String, String> body
    ) {
        String adminId = getCurrentAdminId();
        String targetCardId = body != null ? body.get("targetCardId") : null;
        int count = draftService.reassignUnassignedMediaToCard(adminId, draftId, targetCardId);
        return ResponseEntity.ok(Map.of("draftId", draftId, "targetCardId", targetCardId != null ? targetCardId : "", "reassignedCount", count));
    }

    /**
     * Reconciles a batch draft after partial publication, retaining unpublished cards.
     */
    @PostMapping("/{draftId}/reconcile-batch")
    public ResponseEntity<?> reconcileBatch(
            @PathVariable String draftId,
            @RequestBody(required = false) Map<String, ?> body
    ) {
        String adminId = getCurrentAdminId();
        List<String> publishedCardIds = new ArrayList<>();
        if (body != null && body.get("publishedCardIds") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) publishedCardIds.add(s);
            }
        }
        List<Map<String, Object>> completedListings = new ArrayList<>();
        if (body != null && body.get("completedListings") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    completedListings.add((Map<String, Object>) m);
                }
            }
        }
        DraftDetailDTO remaining;
        if (!completedListings.isEmpty()) {
            remaining = draftService.reconcileBatchDraft(adminId, draftId, publishedCardIds, completedListings);
        } else {
            remaining = draftService.reconcileBatchDraft(adminId, draftId, publishedCardIds);
        }
        if (remaining == null) {
            return ResponseEntity.ok(Map.of("message", "Batch fully published. Draft cleared.", "cleared", true));
        }
        return ResponseEntity.ok(remaining);
    }

    /**
     * Cleans up a draft after confirmed single property publication.
     */
    @PostMapping("/{draftId}/published")
    public ResponseEntity<Void> markPublished(
            @PathVariable String draftId,
            @RequestBody(required = false) Map<String, Object> body) {
        String adminId = getCurrentAdminId();
        Long listingId = null;
        if (body != null && body.containsKey("listingId")) {
            Object raw = body.get("listingId");
            if (raw instanceof Number) {
                listingId = ((Number) raw).longValue();
            } else if (raw != null) {
                try {
                    listingId = Long.parseLong(raw.toString().trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        draftService.onPropertyPublished(adminId, draftId, listingId);
        return ResponseEntity.noContent().build();
    }
}
