package com.indore.pathome.spaces.dto;

import java.util.List;

public record OperatingTeamProvisioningCityPage(
        List<OperatingTeamProvisioningCityView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
