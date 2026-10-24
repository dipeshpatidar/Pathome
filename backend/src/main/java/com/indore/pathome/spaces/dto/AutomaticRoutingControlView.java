package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record AutomaticRoutingControlView(
        boolean enabled,
        int protocolVersion,
        Long version,
        Instant updatedAt,
        Long updatedByUserId,
        String reasonCode) {
}
