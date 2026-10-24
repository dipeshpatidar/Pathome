package com.indore.pathome.spaces.dto;

import java.util.List;

public record VisitRequestRoutingTaskPage(
        List<VisitRequestRoutingTaskView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
