package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

public record PropertyVisitRequestAcknowledgement(
        Long requestId,
        Long propertyId,
        String status,
        String message,
        LocalDateTime receivedAt
) {}
