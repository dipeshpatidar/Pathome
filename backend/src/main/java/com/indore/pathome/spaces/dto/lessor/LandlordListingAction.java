package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;

public record LandlordListingAction(Long listingId, ListingWorkflowStatus status, Long version) {}
