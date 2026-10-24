package com.indore.pathome.spaces.dto;

import java.util.List;

public record StaffAccountSearchPage(
        List<StaffAccountSearchView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
