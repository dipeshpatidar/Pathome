package com.indore.pathome.spaces.dto.lessor;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyType;

import java.math.BigDecimal;
import java.time.Instant;

public record LandlordListingSummary(Long listingId, String title, PropertyType propertyType,
                                     String bhkCount, String city, String locality,
                                     BigDecimal monthlyRent, ListingWorkflowStatus status,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING)
                                     Instant updatedAt, String coverUrl,
                                     String openRevisionDraftId, String openRevisionStatus) {}
