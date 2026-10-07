package com.indore.pathome.spaces.dto;

import java.util.List;

public record ClaimableWorkPage(
        List<ClaimableWorkItem> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {}
