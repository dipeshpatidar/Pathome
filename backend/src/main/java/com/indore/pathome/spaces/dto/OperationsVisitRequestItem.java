package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

public record OperationsVisitRequestItem(
        Long requestId,
        String status,
        Long version,
        LocalDateTime createdAt,
        Long listingId,
        String listingTitle,
        String city,
        String sector) {}
