package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Package 3B intake-routing reads.
 *
 * <p>Every statement filters and pages in PostgreSQL, selects only operational identity (never private
 * tenant detail), and derives location/ownership state from persisted rows rather than from anything a
 * caller sent. Eligibility is exactly the frozen initial-routing predicate: a standalone Request that
 * is {@code RECEIVED} and carries no coordinator and no Team.
 */
@Repository
public interface OperationalIntakeRoutingRepository extends JpaRepository<PropertyVisitRequest, Long> {

    /** Persisted facts one routing row exposes; every derived flag is computed from these. */
    interface RoutingRow {
        Long getId();
        Long getVersion();
        String getStatus();
        LocalDateTime getCreatedAt();
        Long getSessionId();
        Long getListingId();
        String getListingTitle();
        Long getCityId();
        Long getTeamId();
        Long getCoordinatorUserId();
        Boolean getScopeReady();
        Long getCanonicalLocalityId();
        Long getLocalityCityId();
        Boolean getCityActive();
        Boolean getTeamActive();
        Long getTeamCityId();
    }

    String SELECT_COLUMNS = """
            SELECT r.id AS id, r.version AS version, r.status AS status, r.created_at AS "createdAt",
                   r.session_id AS "sessionId", r.operational_scope_ready AS "scopeReady",
                   l.id AS "listingId", l.title AS "listingTitle",
                   r.supported_city_id AS "cityId", r.operating_team_id AS "teamId",
                   r.coordinator_user_id AS "coordinatorUserId",
                   l.canonical_locality_id AS "canonicalLocalityId",
                   loc.supported_city_id AS "localityCityId",
                   c.active AS "cityActive", t.active AS "teamActive", t.city_id AS "teamCityId"
            FROM property_visit_requests r
            JOIN listings l ON l.id = r.listing_id
            LEFT JOIN localities loc ON loc.id = l.canonical_locality_id
            LEFT JOIN supported_cities c ON c.id = r.supported_city_id
            LEFT JOIN operating_teams t ON t.id = r.operating_team_id
            """;

    String ELIGIBLE = """
            WHERE r.status = 'RECEIVED' AND r.session_id IS NULL
              AND r.coordinator_user_id IS NULL AND r.operating_team_id IS NULL
            """;

    String CITY_SCOPED = """
              AND r.supported_city_id IN (:cityIds)
              AND loc.supported_city_id = r.supported_city_id
            """;

    String GROUP_ORDER = " ORDER BY r.created_at DESC, r.id DESC";

    /** Global Staff Admin discovery: every eligible standalone Request, unresolved intake included. */
    @Query(value = SELECT_COLUMNS + ELIGIBLE + GROUP_ORDER,
            countQuery = "SELECT count(*) FROM property_visit_requests r " + ELIGIBLE,
            nativeQuery = true)
    Page<RoutingRow> findEligibleRequestPage(Pageable pageable);

    /**
     * City Team Admin discovery: only eligible intake that is already canonically resolved to one of
     * the caller's authorized Cities. Unresolved and conflicting intake is never returned here, so a
     * City admin can never acquire authority over unknown-city work by reading it first.
     */
    @Query(value = SELECT_COLUMNS + ELIGIBLE + CITY_SCOPED + GROUP_ORDER,
            countQuery = "SELECT count(*) FROM property_visit_requests r "
                    + "JOIN listings l ON l.id = r.listing_id "
                    + "LEFT JOIN localities loc ON loc.id = l.canonical_locality_id "
                    + ELIGIBLE + CITY_SCOPED,
            nativeQuery = true)
    Page<RoutingRow> findEligibleCityScopedRequestPage(@Param("cityIds") List<Long> cityIds, Pageable pageable);

    /**
     * Authoritative snapshot of one Request regardless of current eligibility, so a caller can read the
     * established routing outcome after a lost command response. Scope concealment is enforced by the
     * service, never by telling the client whether the row exists.
     */
    @Query(value = SELECT_COLUMNS + " WHERE r.id = :requestId", nativeQuery = true)
    Optional<RoutingRow> findRoutingRow(@Param("requestId") Long requestId);
}
