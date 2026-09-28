package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LandlordListingSummary(Long listingId, String title, PropertyType propertyType,
                                     String bhkCount, String city, String locality,
                                     BigDecimal monthlyRent, ListingWorkflowStatus status,
                                     LocalDateTime updatedAt, String coverUrl,
                                     String openRevisionDraftId, String openRevisionStatus) {}
