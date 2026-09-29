package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.security.GuestRequestGuard;
import com.indore.pathome.spaces.service.GuestMediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/lessor/guest/drafts/{draftId}/media")
public class GuestMediaController {
    private final GuestMediaService media;
    private final GuestRequestGuard guard;
    public GuestMediaController(GuestMediaService media, GuestRequestGuard guard) {
        this.media = media; this.guard = guard;
    }

    @GetMapping
    public List<LandlordMediaItem> list(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof) {
        guard.check(request); return media.list(draftId, proof);
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LandlordMediaItem upload(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof,
            @RequestParam String mediaId, @RequestParam MultipartFile file) {
        guard.check(request); return media.upload(draftId, proof, mediaId, file, request.getRemoteAddr());
    }
    @PostMapping("/{mediaId}/recover")
    public LandlordMediaItem recover(HttpServletRequest request, @PathVariable String draftId, @PathVariable String mediaId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof) {
        guard.check(request); return media.recover(draftId, proof, mediaId);
    }
    @PutMapping("/{mediaId}/cover")
    public List<LandlordMediaItem> cover(HttpServletRequest request, @PathVariable String draftId, @PathVariable String mediaId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof) {
        guard.check(request); return media.cover(draftId, proof, mediaId);
    }
    @PutMapping("/{mediaId}/tag")
    public LandlordMediaItem tag(HttpServletRequest request, @PathVariable String draftId, @PathVariable String mediaId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof, @RequestBody RoomTag tag) {
        guard.check(request); return media.tag(draftId, proof, mediaId, tag);
    }
    @PutMapping("/order")
    public List<LandlordMediaItem> reorder(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof, @RequestBody List<String> ids) {
        guard.check(request); return media.reorder(draftId, proof, ids);
    }
    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String draftId, @PathVariable String mediaId,
            @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof) {
        guard.check(request); media.delete(draftId, proof, mediaId); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{mediaId}/content")
    public ResponseEntity<InputStreamResource> content(HttpServletRequest request, @PathVariable String draftId,
            @PathVariable String mediaId, @CookieValue(value = GuestDraftController.COOKIE, required = false) String proof) {
        guard.check(request);
        GuestMediaService.Content data = media.content(draftId, proof, mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.type())).contentLength(data.length())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(data.stream()));
    }
}
