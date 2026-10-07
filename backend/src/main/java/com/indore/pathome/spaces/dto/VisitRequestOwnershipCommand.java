package com.indore.pathome.spaces.dto;

import java.util.Map;

/** Versioned ownership action for one Request, or its linked Session root. */
public record VisitRequestOwnershipCommand(
        Long expectedRequestVersion,
        Long expectedSessionVersion,
        Map<Long, Long> expectedRequestVersions,
        Long coordinatorUserId,
        Long destinationTeamId,
        String reasonCode) {}
