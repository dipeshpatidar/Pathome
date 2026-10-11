package com.indore.pathome.spaces.dto;

import java.util.List;

public record OperationsMyWorkPage(
        List<OperationsMyWorkItem> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {}
