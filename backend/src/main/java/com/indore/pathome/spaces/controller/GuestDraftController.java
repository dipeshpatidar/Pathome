package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.security.GuestRequestGuard;
import com.indore.pathome.spaces.service.GuestDraftService;
import com.indore.pathome.spaces.service.GuestMediaService;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/lessor/guest/drafts")
public class GuestDraftController {
    public static final String COOKIE = "pathome_guest_draft";
    private final GuestDraftService drafts;
    private final GuestMediaService media;
    private final LandlordSubmissionService submissions;
    private final GuestRequestGuard guard;

    public GuestDraftController(GuestDraftService drafts, GuestMediaService media,
                                LandlordSubmissionService submissions, GuestRequestGuard guard) {
        this.drafts = drafts; this.media = media; this.submissions = submissions; this.guard = guard;
    }

    @PostMapping
    public ResponseEntity<LandlordDraftResponse> create(HttpServletRequest request,
            @CookieValue(value = COOKIE, required = false) String proof,
            @RequestBody LandlordDraftData.Basics basics) {
        guard.check(request);
        GuestDraftService.Created created = drafts.create(basics, proof, request.getRemoteAddr());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (created.credential() != null) builder.header(HttpHeaders.SET_COOKIE,
                cookie(created.credential(), request, drafts.cookieAgeSeconds()).toString());
        return builder.body(created.draft());
    }

    @GetMapping("/resume")
    public ResponseEntity<LandlordDraftResponse> resume(HttpServletRequest request,
            @CookieValue(value = COOKIE, required = false) String proof) {
        guard.check(request);
        LandlordDraftResponse draft = drafts.resume(proof);
        return draft == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(draft);
    }

    @GetMapping("/{draftId}")
    public LandlordDraftResponse get(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = COOKIE, required = false) String proof) {
        guard.check(request);
        return drafts.get(draftId, proof);
    }

    @PatchMapping("/{draftId}/sections/basics")
    public LandlordDraftResponse basics(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = COOKIE, required = false) String proof, @RequestHeader("If-Match") int version,
            @RequestBody LandlordDraftData.Basics value) {
        guard.check(request); return drafts.updateBasics(draftId, proof, version, value);
    }
    @PatchMapping("/{draftId}/sections/pricing")
    public LandlordDraftResponse pricing(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = COOKIE, required = false) String proof, @RequestHeader("If-Match") int version,
            @RequestBody LandlordDraftData.Pricing value) {
        guard.check(request); return drafts.updatePricing(draftId, proof, version, value);
    }
    @PatchMapping("/{draftId}/sections/location")
    public LandlordDraftResponse location(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = COOKIE, required = false) String proof, @RequestHeader("If-Match") int version,
            @RequestBody LandlordDraftData.Location value) {
        guard.check(request); return drafts.updateLocation(draftId, proof, version, value);
    }
    @PatchMapping("/{draftId}/sections/details")
    public LandlordDraftResponse details(HttpServletRequest request, @PathVariable String draftId,
            @CookieValue(value = COOKIE, required = false) String proof, @RequestHeader("If-Match") int version,
            @RequestBody LandlordDraftData.Details value) {
        guard.check(request); return drafts.updateDetails(draftId, proof, version, value);
    }

    @GetMapping("/{draftId}/preview")
    public com.indore.pathome.spaces.dto.lessor.LandlordPreview preview(HttpServletRequest request,
            @PathVariable String draftId, @CookieValue(value = COOKIE, required = false) String proof) {
        guard.check(request); return submissions.previewGuest(drafts.require(draftId, proof), media.rows(draftId, proof));
    }

    @PostMapping("/{draftId}/claim")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LandlordDraftResponse> claim(HttpServletRequest request, Authentication auth,
            @PathVariable String draftId, @CookieValue(value = COOKIE, required = false) String proof) {
        guard.check(request);
        LandlordDraftResponse claimed = drafts.claim(draftId, proof, auth.getName());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", request, 0).toString()).body(claimed);
    }

    private ResponseCookie cookie(String value, HttpServletRequest request, long age) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(guard.secureCookie(request))
                .sameSite("Lax").path("/api/v1/lessor/guest").maxAge(age).build();
    }
}
