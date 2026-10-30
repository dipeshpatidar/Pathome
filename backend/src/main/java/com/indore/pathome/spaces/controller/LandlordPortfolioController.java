package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordListingPage;
import com.indore.pathome.spaces.dto.lessor.LandlordListingDetail;
import com.indore.pathome.spaces.dto.lessor.LandlordListingAction;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.service.LandlordManagementService;
import com.indore.pathome.spaces.service.LandlordPortfolioService;
import com.indore.pathome.spaces.service.PropertyRemovalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/properties")
@PreAuthorize("isAuthenticated()")
public class LandlordPortfolioController {
    private final LandlordPortfolioService portfolio;
    private final LandlordManagementService management;
    private final PropertyRemovalService removals;

    public LandlordPortfolioController(LandlordPortfolioService portfolio, LandlordManagementService management,
                                       PropertyRemovalService removals) {
        this.portfolio = portfolio;
        this.management = management;
        this.removals = removals;
    }

    @GetMapping
    public LandlordListingPage list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        return portfolio.list(auth.getName(), page);
    }

    @GetMapping("/{listingId}")
    public LandlordListingDetail get(Authentication auth, @PathVariable Long listingId) {
        return portfolio.get(auth.getName(), listingId);
    }

    @PostMapping("/{listingId}/revision")
    public LandlordDraftResponse revise(Authentication auth, @PathVariable Long listingId) {
        return management.startRevision(auth.getName(), listingId);
    }

    @PostMapping("/{listingId}/pause")
    public LandlordListingAction pause(Authentication auth, @PathVariable Long listingId,
                                        @RequestHeader("If-Match") Long version) {
        return management.changeStatus(auth.getName(), listingId, version, ListingWorkflowStatus.PAUSED);
    }

    @PostMapping("/{listingId}/archive")
    public void archive(Authentication auth, @PathVariable Long listingId,
                        @RequestHeader("If-Match") Long version) {
        removals.legacyArchiveDenied(StaffAuthentication.userId(auth), listingId);
    }

    @PostMapping("/{listingId}/resume-review")
    public LandlordListingAction resume(Authentication auth, @PathVariable Long listingId,
                                         @RequestHeader("If-Match") Long version) {
        return management.changeStatus(auth.getName(), listingId, version, ListingWorkflowStatus.SUBMITTED);
    }
}
