package com.indore.pathome.spaces.dto;

/**
 * Candidate read for one Request: the authoritative Request snapshot, the explicitly selected
 * canonical City with its current version, and the paginated eligible Teams of that City.
 *
 * <p>Zero, one and many Teams are all represented the same way. No Team is ever pre-selected,
 * ranked or defaulted: routing always requires an explicit Team ID in the command.
 */
public record IntakeRoutingCandidatesView(
        IntakeRoutingRequestView request,
        OperatingTeamProvisioningCityView city,
        IntakeRoutingTeamPage teams) {
}
