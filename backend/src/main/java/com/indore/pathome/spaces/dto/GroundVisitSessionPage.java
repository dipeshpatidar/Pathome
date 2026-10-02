package com.indore.pathome.spaces.dto;

import java.util.List;

public record GroundVisitSessionPage(
        List<GroundVisitSessionView> sessions,
        long totalCount,
        int page,
        int size,
        int totalPages) {}
