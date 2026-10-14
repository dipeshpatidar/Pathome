package com.indore.pathome.spaces.dto;

import java.util.List;

public record IntakeRoutingRequestPage(
        List<IntakeRoutingRequestView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
