package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

/**
 * Authoritative snapshot of one standalone Visit Request as seen by the intake-routing entry points.
 *
 * <p>Only operational identity and derived routing classification are exposed. Private tenant data
 * (addresses, phone numbers, notes, availability) is never part of this view.
 */
public record IntakeRoutingRequestView(
        Long requestId,
        Long requestVersion,
        String status,
        LocalDateTime createdAt,
        Long listingId,
        String listingTitle,
        Long cityId,
        Long teamId,
        Long coordinatorUserId,
        Long canonicalLocalityId,
        Long canonicalCityId,
        LocationState locationState,
        boolean teamReady,
        boolean standalone,
        boolean coordinatorAssigned,
        boolean initialRoutingEligible) {

    /**
     * Derived response classification of the Request's canonical location evidence. This is never a
     * persisted workflow state and never a substitute for the persisted ownership columns.
     */
    public enum LocationState {
        /** Persisted locality carries an unambiguous supported-City linkage consistent with Request scope. */
        CANONICAL,
        /** Missing locality, missing linkage, or evidence insufficient to establish a canonical City. */
        UNRESOLVED,
        /** Persisted canonical linkage contradicts the established Request scope. */
        CONFLICTING
    }
}
