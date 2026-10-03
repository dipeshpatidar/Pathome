package com.indore.pathome.spaces.dto;

import java.util.List;

public record TenantVisitOutcomeHistoryPage(
        List<TenantVisitSessionOutcomeView> sessions,
        int page,
        int size,
        int totalPages,
        long totalElements) {}
