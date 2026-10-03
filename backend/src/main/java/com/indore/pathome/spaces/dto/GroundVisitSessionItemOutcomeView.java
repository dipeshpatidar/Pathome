package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;

import java.time.Instant;

public record GroundVisitSessionItemOutcomeView(
        Long itemId,
        Long listingId,
        Integer position,
        String title,
        String address,
        String city,
        String sector,
        VisitSessionItemOutcomeState outcome,
        VisitSessionItemSkipReason skipReason,
        String privateNote,
        Instant recordedAt) {}
