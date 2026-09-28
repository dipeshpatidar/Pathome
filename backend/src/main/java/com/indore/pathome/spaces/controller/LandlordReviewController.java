package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordListingAction;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewPage;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewItem;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewDetail;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.service.LandlordReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/lessor-review")
@PreAuthorize("hasRole('ADMIN')")
public class LandlordReviewController {
    private final LandlordReviewService reviews;

    public LandlordReviewController(LandlordReviewService reviews) { this.reviews = reviews; }

    @GetMapping("/listings")
    public LandlordReviewPage listings(@RequestParam(defaultValue = "0") int page) { return reviews.listings(page); }

    @GetMapping("/revisions")
    public LandlordReviewPage revisions(@RequestParam(defaultValue = "0") int page) { return reviews.revisions(page); }

    @GetMapping("/listings/{listingId}")
    public LandlordReviewDetail listing(@PathVariable Long listingId) { return reviews.listingDetail(listingId); }

    @GetMapping("/revisions/{draftId}")
    public LandlordReviewDetail revision(@PathVariable String draftId) { return reviews.revisionDetail(draftId); }

    @PostMapping("/listings/{listingId}/start-review")
    public LandlordListingAction start(@PathVariable Long listingId) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.UNDER_REVIEW, null);
    }

    @PostMapping("/listings/{listingId}/approve")
    public LandlordListingAction approve(@PathVariable Long listingId) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.PUBLISHED, null);
    }

    @PostMapping("/listings/{listingId}/request-changes")
    public LandlordListingAction requestChanges(@PathVariable Long listingId, @RequestBody ReviewNoteRequest request) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.CHANGES_REQUIRED, request.note());
    }

    @PostMapping("/revisions/{draftId}/approve")
    public LandlordListingAction approveRevision(@PathVariable String draftId) {
        return reviews.approveRevision(draftId);
    }

    @PostMapping("/revisions/{draftId}/request-changes")
    public LandlordReviewItem requestRevisionChanges(@PathVariable String draftId,
                                                      @RequestBody ReviewNoteRequest request) {
        return reviews.requestRevisionChanges(draftId, request.note());
    }

    public record ReviewNoteRequest(String note) {}
}
