package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordListingPage;
import com.indore.pathome.spaces.dto.lessor.LandlordListingDetail;
import com.indore.pathome.spaces.service.LandlordPortfolioService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/properties")
@PreAuthorize("isAuthenticated()")
public class LandlordPortfolioController {
    private final LandlordPortfolioService portfolio;

    public LandlordPortfolioController(LandlordPortfolioService portfolio) { this.portfolio = portfolio; }

    @GetMapping
    public LandlordListingPage list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        return portfolio.list(auth.getName(), page);
    }

    @GetMapping("/{listingId}")
    public LandlordListingDetail get(Authentication auth, @PathVariable Long listingId) {
        return portfolio.get(auth.getName(), listingId);
    }
}
