package com.indore.pathome.spaces.dto;

import java.util.List;

public record TeamLocalityCoveragePage(
        List<TeamLocalityCoverageView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
