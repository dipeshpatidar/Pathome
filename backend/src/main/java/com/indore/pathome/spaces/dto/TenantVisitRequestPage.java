package com.indore.pathome.spaces.dto;

import java.util.List;

public record TenantVisitRequestPage(
        Long userId,
        List<TenantVisitRequestResponse> requests,
        long totalCount,
        int page,
        boolean hasMore
) {}
