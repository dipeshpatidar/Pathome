package com.indore.pathome.spaces.dto;

/**
 * Package 3A governed Team creation command. The body carries identity and version expectations only:
 * there is deliberately no {@code active}, creator, grant or ownership field, so a client cannot
 * choose the state the server persists.
 */
public record CreateOperatingTeamCommand(
        String code,
        String displayName,
        Long expectedCityVersion,
        String reasonCode) {
}
