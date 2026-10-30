package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import com.indore.pathome.spaces.service.BackofficeMediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/backoffice/properties/{listingId}/revisions/{draftId}/media")
public class BackofficeMediaController {
    private final BackofficeMediaService media;

    public BackofficeMediaController(BackofficeMediaService media) {
        this.media = media;
    }

    @GetMapping
    public ResponseEntity<List<BackofficeDtos.NewMedia>> list(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId) {
        return noStore(media.list(StaffAuthentication.userId(auth), listingId, draftId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BackofficeDtos.NewMedia> upload(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestParam String mediaId, @RequestHeader("If-Match") int version,
            @RequestParam MultipartFile file) {
        return noStore(media.upload(StaffAuthentication.userId(auth), listingId, draftId,
                mediaId, version, file));
    }

    @PostMapping("/{mediaId}/recover")
    public ResponseEntity<BackofficeDtos.NewMedia> recover(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @PathVariable String mediaId, @RequestHeader("If-Match") int version) {
        return noStore(media.recover(StaffAuthentication.userId(auth), listingId, draftId,
                mediaId, version));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

