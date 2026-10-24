package com.indore.pathome.spaces.dto;

public record TeamLocalityCoverageStateCommand(
        Long expectedVersion,
        boolean active,
        String reasonCode) {
}
