package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

/**
 * Minimum operational metadata for one coordinator-owned root.
 * Deliberately carries no customer, lessor, Ground Executive or financial data.
 */
public record OperationsMyWorkItem(
        OperationsRootType targetType,
        Long targetId,
        Long expectedVersion,
        Long cityId,
        String cityDisplayName,
        Long teamId,
        String teamDisplayName,
        String status,
        Instant updatedAt,
        Instant scheduledAt,
        String tenantConfirmationState,
        List<LinkedRequestVersion> linkedRequestVersions) {}
