package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record OperatingTeamProvisioningView(
        Long id,
        Long cityId,
        String code,
        String displayName,
        boolean active,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
