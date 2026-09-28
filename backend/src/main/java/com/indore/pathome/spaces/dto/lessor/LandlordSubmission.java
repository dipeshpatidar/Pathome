package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;

import java.time.LocalDateTime;

public record LandlordSubmission(Long listingId, String draftId, String title,
                                 ListingWorkflowStatus status, LocalDateTime submittedAt) {}
