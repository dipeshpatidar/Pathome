package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;
import java.time.Instant;

public record TenantVisitSessionItemOutcomeView(
        Integer position,
        String title,
        String address,
        String city,
        String sector,
        String outcome,
        String reasonLabel,
        String attribution,
        Instant correctedAt) {}
