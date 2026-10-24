package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

public record RoutingUnclaimedWorkView(
        String targetType,
        Long targetId,
        Long cityId,
        String cityDisplayName,
        Long teamId,
        String teamDisplayName,
        String status,
        LocalDateTime createdAt) {
}
