package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;

public record StaffCapabilitySummary(
        StaffCapability capability,
        StaffScopeType scopeType,
        Long cityId,
        String cityDisplayName,
        Long teamId,
        String teamDisplayName) {
}
