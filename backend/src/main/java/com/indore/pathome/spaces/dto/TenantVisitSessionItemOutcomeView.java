package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;

public record TenantVisitSessionItemOutcomeView(
        Integer position,
        String title,
        String address,
        String city,
        String sector,
        VisitSessionItemOutcomeState outcome,
        VisitSessionItemSkipReason skipReason) {}
