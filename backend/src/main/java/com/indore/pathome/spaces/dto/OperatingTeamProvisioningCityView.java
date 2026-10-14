package com.indore.pathome.spaces.dto;

public record OperatingTeamProvisioningCityView(
        Long id,
        String code,
        String displayName,
        boolean active,
        Long version) {
}
