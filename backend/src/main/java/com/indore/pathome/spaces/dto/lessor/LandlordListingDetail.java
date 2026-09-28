package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;

/** Owner-only listing view with the same private-safe fields used for draft previews. */
public record LandlordListingDetail(Long listingId, ListingWorkflowStatus status, Long version,
                                    String openRevisionDraftId, String openRevisionStatus,
                                    String reviewNote, LandlordPreview preview) {}
