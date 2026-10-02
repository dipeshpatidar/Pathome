package com.indore.pathome.spaces.dto;

import java.util.List;

public record OperationsVisitRequestPage(
        List<OperationsVisitRequestItem> requests,
        long totalCount,
        int page,
        int size,
        int totalPages) {}
