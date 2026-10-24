package com.indore.pathome.spaces.dto;

public record TeamLocalityCoverageCommand(
        Long cityId,
        Long localityId,
        Long teamId,
        String reasonCode) {
}
