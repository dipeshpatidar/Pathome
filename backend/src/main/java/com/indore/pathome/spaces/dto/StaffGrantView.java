package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;

import java.time.Instant;

public record StaffGrantView(Long id,
                             StaffCapability capability,
                             StaffScopeType scopeType,
                             Long cityId,
                             String cityDisplayName,
                             Long teamId,
                             String teamDisplayName,
                             Instant effectiveAt,
                             Instant expiresAt,
                             Instant revokedAt,
                             Instant createdAt,
                             StaffGrantProvisioningSource provisioningSource) {
}
