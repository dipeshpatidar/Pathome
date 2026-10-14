package com.indore.pathome.spaces.dto;

/**
 * Package 3B human-authorized initial routing command.
 *
 * <p>The body carries identity and version expectations only. There is deliberately no coordinator,
 * session, scope-ready, transfer, scheduling or entitlement field: routing alone must never create a
 * Visit Session, assign a coordinator, schedule a visit or consume entitlement, so a client cannot
 * request any of those effects through this endpoint.
 */
public record InitialVisitRequestRoutingCommand(
        Long expectedRequestVersion,
        Long cityId,
        Long expectedCityVersion,
        Long teamId,
        Long expectedTeamVersion,
        String reasonCode) {
}
