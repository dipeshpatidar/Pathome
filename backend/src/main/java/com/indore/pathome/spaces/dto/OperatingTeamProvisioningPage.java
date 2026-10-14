package com.indore.pathome.spaces.dto;

import java.util.List;

public record OperatingTeamProvisioningPage(
        OperatingTeamProvisioningCityView city,
        List<OperatingTeamProvisioningView> items,
        long totalCount,
        int page,
        int size,
        long totalPages) {
}
