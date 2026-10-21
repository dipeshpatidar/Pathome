package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordListingAction;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewPage;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewItem;
import com.indore.pathome.spaces.dto.lessor.LandlordReviewDetail;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.service.ListingReviewAuthority;
import com.indore.pathome.spaces.service.LandlordReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The landlord listing-review surface.
 *
 * <p>Four logical operations, exposed as five HTTP routes, are part of the approved listing-review
 * authority extension and are additionally reachable by a caller holding a live effective
 * {@code STAFF_ADMIN@GLOBAL} grant: the submitted listing queue/detail, start-review,
 * canonical-locality linking and publication approval. Each passes through
 * {@link ListingReviewAuthority}, which resolves the authority from live database facts on every request.
 * The two revision reads remain live legacy-Administrator-only.
 *
 * <p>The remaining three operations - requesting listing changes, and approving or rejecting a landlord
 * revision - are deliberately <em>not</em> part of the extension and keep their existing
 * {@code ROLE_ADMIN}-only rule, both here and on the {@code /api/v1/admin/**} request matcher. They too
 * hand the service a verdict resolved from the authenticated principal, and only the legacy administrator
 * authority is admitted - here and again at the service boundary, which re-decides these three by the
 * narrow legacy rule under their own row lock - so a caller that reaches the service directly is refused
 * exactly as this rule refuses it.
 */
@RestController
@RequestMapping("/api/v1/admin/lessor-review")
@PreAuthorize("isAuthenticated()")
public class LandlordReviewController {
    private final LandlordReviewService reviews;
    private final ListingReviewAuthority reviewAuthority;

    public LandlordReviewController(LandlordReviewService reviews, ListingReviewAuthority reviewAuthority) {
        this.reviews = reviews;
        this.reviewAuthority = reviewAuthority;
    }

    @GetMapping("/listings")
    public LandlordReviewPage listings(@RequestParam(defaultValue = "0") int page, Authentication authentication) {
        return reviews.listings(page, reviewAuthority.require(authentication));
    }

    @GetMapping("/revisions")
    public LandlordReviewPage revisions(@RequestParam(defaultValue = "0") int page, Authentication authentication) {
        return reviews.revisions(page, reviewAuthority.requireLegacyAdministrator(authentication));
    }

    @GetMapping("/listings/{listingId}")
    public LandlordReviewDetail listing(@PathVariable Long listingId, Authentication authentication) {
        return reviews.listingDetail(listingId, reviewAuthority.require(authentication));
    }

    @GetMapping("/revisions/{draftId}")
    public LandlordReviewDetail revision(@PathVariable String draftId, Authentication authentication) {
        return reviews.revisionDetail(draftId, reviewAuthority.requireLegacyAdministrator(authentication));
    }

    @PostMapping("/listings/{listingId}/start-review")
    public LandlordListingAction start(@PathVariable Long listingId, Authentication authentication) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.UNDER_REVIEW, null,
                reviewAuthority.require(authentication));
    }

    @PostMapping("/listings/{listingId}/approve")
    public LandlordListingAction approve(@PathVariable Long listingId, Authentication authentication) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.PUBLISHED, null,
                reviewAuthority.require(authentication));
    }

    @PostMapping("/listings/{listingId}/link-locality")
    public LandlordListingAction linkLocality(@PathVariable Long listingId, @RequestBody LocalityLinkRequest request,
                                              Authentication authentication) {
        return reviews.linkLocality(listingId, request.canonicalLocalityId(),
                reviewAuthority.require(authentication));
    }

    @PostMapping("/listings/{listingId}/request-changes")
    @PreAuthorize("hasRole('ADMIN')")
    public LandlordListingAction requestChanges(@PathVariable Long listingId, @RequestBody ReviewNoteRequest request,
                                                Authentication authentication) {
        return reviews.decideListing(listingId, ListingWorkflowStatus.CHANGES_REQUIRED, request.note(),
                reviewAuthority.requireLegacyAdministrator(authentication));
    }

    @PostMapping("/revisions/{draftId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public LandlordListingAction approveRevision(@PathVariable String draftId, Authentication authentication) {
        return reviews.approveRevision(draftId, reviewAuthority.requireLegacyAdministrator(authentication));
    }

    @PostMapping("/revisions/{draftId}/request-changes")
    @PreAuthorize("hasRole('ADMIN')")
    public LandlordReviewItem requestRevisionChanges(@PathVariable String draftId,
                                                      @RequestBody ReviewNoteRequest request,
                                                      Authentication authentication) {
        return reviews.requestRevisionChanges(draftId, request.note(),
                reviewAuthority.requireLegacyAdministrator(authentication));
    }

    public record ReviewNoteRequest(String note) {}
    public record LocalityLinkRequest(Long canonicalLocalityId) {}
}
