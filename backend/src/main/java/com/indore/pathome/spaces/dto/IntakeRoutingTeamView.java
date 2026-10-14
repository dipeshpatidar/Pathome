package com.indore.pathome.spaces.dto;

/** One eligible Operating Team for an explicitly selected canonical City. */
public record IntakeRoutingTeamView(
        Long id,
        Long cityId,
        String code,
        String displayName,
        boolean active,
        Long version) {
}
