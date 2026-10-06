package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;

import java.time.Instant;

public record StaffGrantCommand(StaffCapability capability,
                                StaffScopeType scopeType,
                                Long cityId,
                                Long teamId,
                                Instant effectiveAt,
                                Instant expiresAt,
                                String reasonCode) {
}
