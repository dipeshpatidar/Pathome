package com.indore.pathome.spaces.dto;

import java.util.Map;

/** Versioned ownership action for a Session and its linked Requests. */
public record VisitSessionOwnershipCommand(
        Long expectedSessionVersion,
        Map<Long, Long> expectedRequestVersions,
        Long coordinatorUserId,
        Long destinationTeamId,
        String reasonCode) {}
