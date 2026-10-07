package com.indore.pathome.spaces.dto;

import java.util.List;

public record ClaimableWorkItem(
        ClaimableWorkTargetType targetType,
        Long targetId,
        Long expectedVersion,
        Long cityId,
        String cityDisplayName,
        Long teamId,
        String teamDisplayName,
        String status,
        List<LinkedRequestVersion> linkedRequestVersions) {}
