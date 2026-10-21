package com.indore.pathome.spaces.dto.crm;

public record LeadRoutingCommand(
        Long expectedVersion,
        Long cityId,
        Long expectedCityVersion,
        Long teamId,
        Long expectedTeamVersion,
        String reasonCode) {
}
