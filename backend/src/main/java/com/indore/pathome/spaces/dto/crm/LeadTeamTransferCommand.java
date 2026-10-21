package com.indore.pathome.spaces.dto.crm;

public record LeadTeamTransferCommand(
        Long expectedVersion,
        Long destinationTeamId,
        Long expectedTeamVersion,
        Long targetCoordinatorUserId,
        String reasonCode) {
}
