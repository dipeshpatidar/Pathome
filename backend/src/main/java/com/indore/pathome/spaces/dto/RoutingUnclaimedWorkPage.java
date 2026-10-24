package com.indore.pathome.spaces.dto;

import java.util.List;

public record RoutingUnclaimedWorkPage(
        List<RoutingUnclaimedWorkView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
