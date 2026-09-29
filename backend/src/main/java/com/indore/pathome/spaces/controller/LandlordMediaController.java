package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.service.LandlordMediaService;
import com.indore.pathome.spaces.service.LandlordMediaPromotionService;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/lessor/properties/drafts/{draftId}/media")
@PreAuthorize("isAuthenticated()")
public class LandlordMediaController {
    private final LandlordMediaService media;

    private final LandlordMediaPromotionService promotion;

    public LandlordMediaController(LandlordMediaService media, LandlordMediaPromotionService promotion) {
        this.media = media; this.promotion = promotion;
    }

    @PostMapping("/promote")
    public List<LandlordMediaItem> promote(Authentication auth, @PathVariable String draftId) {
        return promotion.promote(auth.getName(), draftId);
    }

    @GetMapping
    public List<LandlordMediaItem> list(Authentication auth, @PathVariable String draftId) {
        return media.list(auth.getName(), draftId);
    }

    @GetMapping("/{mediaId}/content")
    public ResponseEntity<InputStreamResource> stagedContent(Authentication auth, @PathVariable String draftId,
                                                               @PathVariable String mediaId) {
        LandlordMediaPromotionService.Content data = promotion.stagedContent(auth.getName(), draftId, mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.type())).contentLength(data.length())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(data.stream()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LandlordMediaItem upload(Authentication auth, @PathVariable String draftId,
                                   @RequestParam String mediaId, @RequestParam MultipartFile file) {
        return media.upload(auth.getName(), draftId, mediaId, file);
    }

    @PostMapping("/{mediaId}/recover")
    public LandlordMediaItem recover(Authentication auth, @PathVariable String draftId, @PathVariable String mediaId) {
        return media.recover(auth.getName(), draftId, mediaId);
    }

    @PutMapping("/{mediaId}/cover")
    public List<LandlordMediaItem> cover(Authentication auth, @PathVariable String draftId, @PathVariable String mediaId) {
        return media.makeCover(auth.getName(), draftId, mediaId);
    }

    @PutMapping("/{mediaId}/tag")
    public LandlordMediaItem tag(Authentication auth, @PathVariable String draftId,
                                 @PathVariable String mediaId, @RequestBody RoomTag tag) {
        return media.tag(auth.getName(), draftId, mediaId, tag);
    }

    @PutMapping("/order")
    public List<LandlordMediaItem> reorder(Authentication auth, @PathVariable String draftId,
                                           @RequestBody List<String> ids) {
        return media.reorder(auth.getName(), draftId, ids);
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable String draftId, @PathVariable String mediaId) {
        media.delete(auth.getName(), draftId, mediaId);
        return ResponseEntity.noContent().build();
    }
}
