package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

/**
 * Who may reach the intake-routing entry points at all.
 *
 * <p>Global Staff Admin may discover and route eligible intake across every City, including unresolved
 * intake. A City Team Admin may reach only the eligible, canonically resolved intake that already
 * belongs to an authorized City. An ordinary OE, a Team supervisor or an {@code OPS_INTAKE}-only
 * grant holder receives no initial-routing permission from this helper. The resolved scope is
 * re-read from live database-backed facts on every call, and is deliberately separate from the
 * authority over any particular source Request: selecting a City never grants authority over the
 * Request being routed.
 */
final class IntakeRoutingAuthority {
    private IntakeRoutingAuthority() {}

    record Scope(boolean global, List<Long> cityIds) {
        boolean coversCity(Long cityId) {
            return cityId != null && cityIds.contains(cityId);
        }

        /**
         * Whether a Request whose persisted City is {@code sourceCityId} and whose Listing canonical
         * locality resolves to {@code canonicalLocalityCityId} is in scope for this caller.
         *
         * <p>Global Staff Admin is never narrowed: the diagnostic read of an unresolved or contradictory
         * source stays available to it. A City Team Admin owns only a source that is canonically resolved
         * to exactly one City it is actually authorized for; an unresolved, contradictory or foreign
         * source is out of scope and must be concealed exactly like a missing Request.
         */
        boolean coversCanonicalSource(Long sourceCityId, Long canonicalLocalityCityId) {
            if (global) return true;
            return sourceCityId != null && canonicalLocalityCityId != null
                    && sourceCityId.equals(canonicalLocalityCityId) && coversCity(sourceCityId);
        }
    }

    static Scope resolve(StaffAccessService access, Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        StaffCapabilitiesResponse capabilities = access.currentCapabilities(actorId);
        if (!capabilities.staffActive()) {
            throw new AccessDeniedException("Active staff profile is required");
        }
        boolean global = capabilities.capabilities().stream()
                .anyMatch(summary -> summary.capability() == StaffCapability.STAFF_ADMIN
                        && summary.scopeType() == StaffScopeType.GLOBAL);
        List<Long> cityIds = capabilities.capabilities().stream()
                .filter(summary -> summary.capability() == StaffCapability.CITY_TEAM_ADMIN
                        && summary.scopeType() == StaffScopeType.CITY && summary.cityId() != null)
                .map(StaffCapabilitySummary::cityId)
                .distinct()
                .toList();
        if (!global && cityIds.isEmpty()) {
            throw new AccessDeniedException("Current intake routing authority is required");
        }
        return new Scope(global, cityIds);
    }
}
