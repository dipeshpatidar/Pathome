package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftPage;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.service.LandlordDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/properties/drafts")
@PreAuthorize("isAuthenticated()")
public class LandlordDraftController {
    private final LandlordDraftService drafts;

    public LandlordDraftController(LandlordDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping
    public LandlordDraftResponse create(Authentication auth, @RequestBody LandlordDraftData.Basics basics) {
        return drafts.create(auth.getName(), basics);
    }

    @GetMapping
    public LandlordDraftPage list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        return drafts.list(auth.getName(), page);
    }

    @GetMapping("/{draftId}")
    public LandlordDraftResponse get(Authentication auth, @PathVariable String draftId) {
        return drafts.get(auth.getName(), draftId);
    }

    @PatchMapping("/{draftId}/sections/basics")
    public LandlordDraftResponse basics(Authentication auth, @PathVariable String draftId,
                                         @RequestHeader("If-Match") int version,
                                         @RequestBody LandlordDraftData.Basics basics) {
        return drafts.updateBasics(auth.getName(), draftId, version, basics);
    }

    @PatchMapping("/{draftId}/sections/pricing")
    public LandlordDraftResponse pricing(Authentication auth, @PathVariable String draftId,
                                          @RequestHeader("If-Match") int version,
                                          @RequestBody LandlordDraftData.Pricing pricing) {
        return drafts.updatePricing(auth.getName(), draftId, version, pricing);
    }

    @PatchMapping("/{draftId}/sections/location")
    public LandlordDraftResponse location(Authentication auth, @PathVariable String draftId,
                                           @RequestHeader("If-Match") int version,
                                           @RequestBody LandlordDraftData.Location location) {
        return drafts.updateLocation(auth.getName(), draftId, version, location);
    }

    @PatchMapping("/{draftId}/sections/details")
    public LandlordDraftResponse details(Authentication auth, @PathVariable String draftId,
                                          @RequestHeader("If-Match") int version,
                                          @RequestBody LandlordDraftData.Details details) {
        return drafts.updateDetails(auth.getName(), draftId, version, details);
    }

    @DeleteMapping("/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(Authentication auth, @PathVariable String draftId) {
        drafts.discard(auth.getName(), draftId);
    }
}
