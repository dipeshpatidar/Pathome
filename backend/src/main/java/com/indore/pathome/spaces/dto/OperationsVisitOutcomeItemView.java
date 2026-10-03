package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record OperationsVisitOutcomeItemView(
        Long itemId,
        Integer position,
        String title,
        String address,
        String city,
        String sector,
        String outcome,
        String skipReason,
        String privateNote,
        Instant recordedAt,
        Long recordedByUserId,
        Long version) {}
