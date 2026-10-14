package com.indore.pathome.spaces.dto;

import java.util.List;

public record IntakeRoutingTeamPage(
        List<IntakeRoutingTeamView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
