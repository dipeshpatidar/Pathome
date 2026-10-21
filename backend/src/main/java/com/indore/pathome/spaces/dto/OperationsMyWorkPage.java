package com.indore.pathome.spaces.dto;

import java.util.List;

public record OperationsMyWorkPage(
        List<OperationsMyWorkItem> items,
        long totalCount,
        /** The same authorized queue population before optional user filters. */
        long unfilteredTotalCount,
        int page,
        int size,
        long totalPages) {}
