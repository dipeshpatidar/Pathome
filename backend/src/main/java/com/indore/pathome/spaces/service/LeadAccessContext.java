package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadRoutingQueue;

import java.util.List;

record LeadAccessContext(
        boolean globalAdmin,
        List<Long> intakeCityIds,
        List<Long> routingCityIds,
        List<Long> ownedTeamIds,
        List<Long> supervisedTeamIds) {

    private static final String HIDDEN = "Lead not found";

    List<Long> visibleUnassignedCityIds() {
        return java.util.stream.Stream.concat(intakeCityIds.stream(), routingCityIds.stream())
                .distinct().sorted().toList();
    }

    boolean canList() {
        return !visibleUnassignedCityIds().isEmpty()
                || !ownedTeamIds.isEmpty() || !supervisedTeamIds.isEmpty();
    }

    boolean canRead(LeadRoutingQueue lead, Long actorId) {
        if (lead.getLegacyProvenance() == com.indore.pathome.spaces.entity.LeadLegacyProvenance.LEGACY_UNVERIFIED) {
            return false;
        }
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        Long ownerId = lead.getOwner() == null ? null : lead.getOwner().getId();
        if (teamId != null) {
            return supervisedTeamIds.contains(teamId)
                    || (ownedTeamIds.contains(teamId) && ownerId != null
                        && ownerId.equals(actorId));
        }
        return cityId != null && ownerId == null && visibleUnassignedCityIds().contains(cityId);
    }

    boolean canEdit(LeadRoutingQueue lead, Long actorId) {
        if (lead.getLegacyProvenance() == com.indore.pathome.spaces.entity.LeadLegacyProvenance.LEGACY_UNVERIFIED) {
            return false;
        }
        if (lead.getOperatingTeam() != null) {
            return lead.getOwner() != null && actorId.equals(lead.getOwner().getId())
                    && ownedTeamIds.contains(lead.getOperatingTeam().getId());
        }
        return lead.getOwner() == null && lead.getSupportedCity() != null
                && intakeCityIds.contains(lead.getSupportedCity().getId());
    }

    boolean canRevealContact(LeadRoutingQueue lead, Long actorId) {
        if (lead.getLegacyProvenance() == com.indore.pathome.spaces.entity.LeadLegacyProvenance.LEGACY_UNVERIFIED) {
            return false;
        }
        return lead.getOperatingTeam() != null && lead.getOwner() != null
                && actorId.equals(lead.getOwner().getId())
                && ownedTeamIds.contains(lead.getOperatingTeam().getId());
    }

    boolean canRoute(Long cityId) {
        return cityId != null && routingCityIds.contains(cityId);
    }

    boolean canAssign(Long teamId) {
        return teamId != null && supervisedTeamIds.contains(teamId);
    }

    static RuntimeException hidden() {
        return new jakarta.persistence.EntityNotFoundException(HIDDEN);
    }
}
