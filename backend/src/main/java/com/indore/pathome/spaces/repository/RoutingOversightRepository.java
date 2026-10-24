package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.dto.RoutingUnclaimedWorkView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Metadata-only oversight of currently unclaimed Team work visible to City routing authority. */
@Repository
public class RoutingOversightRepository {
    static final String AUTHORIZED_TEAMS = """
            WITH request_clock AS MATERIALIZED (SELECT clock_timestamp() AS db_now),
            authorized_teams AS MATERIALIZED (
                SELECT t.id AS team_id, c.id AS city_id,
                       c.display_name AS city_display_name, t.display_name AS team_display_name,
                       bool_or(g.capability = 'STAFF_ADMIN' AND g.scope_type = 'GLOBAL')
                           AS global_authority
                FROM request_clock clock
                JOIN employee_profiles ep ON TRUE
                JOIN staff_access_grants g ON g.user_id = ep.user_id
                JOIN operating_teams t ON t.id = g.team_id OR g.scope_type IN ('CITY', 'GLOBAL')
                JOIN supported_cities c ON c.id = t.city_id
                WHERE ep.user_id = :userId
                  AND ep.staff_active = TRUE
                  AND c.active = TRUE
                  AND t.active = TRUE
                  AND g.revoked_at IS NULL
                  AND g.effective_at <= clock.db_now
                  AND (g.expires_at IS NULL OR clock.db_now < g.expires_at)
                  AND (
                      (g.capability = 'STAFF_ADMIN' AND g.scope_type = 'GLOBAL')
                      OR (g.capability IN ('OPS_ROUTING', 'CITY_TEAM_ADMIN')
                          AND g.scope_type = 'CITY' AND g.city_id = t.city_id)
                  )
                GROUP BY t.id, c.id, c.display_name, t.display_name
            ),
            targets AS MATERIALIZED (
                SELECT 'REQUEST'::text AS target_type, r.id AS target_id,
                       r.supported_city_id AS city_id, c.display_name AS city_display_name,
                       r.operating_team_id AS team_id, t.team_display_name,
                       r.status::text AS status, r.created_at::timestamp AS created_at
                FROM property_visit_requests r
                JOIN authorized_teams t ON t.team_id = r.operating_team_id
                                           AND t.city_id = r.supported_city_id
                JOIN listings request_listing ON request_listing.id = r.listing_id
                LEFT JOIN localities request_locality
                       ON request_locality.id = request_listing.canonical_locality_id
                LEFT JOIN visit_request_routing_tasks routing_task
                       ON routing_task.request_id = r.id
                JOIN supported_cities c ON c.id = r.supported_city_id AND c.active = TRUE
                JOIN operating_teams ot ON ot.id = r.operating_team_id AND ot.active = TRUE
                                          AND ot.city_id = r.supported_city_id
                WHERE r.session_id IS NULL
                  AND r.coordinator_user_id IS NULL
                  AND r.status IN ('RECEIVED', 'COORDINATING', 'SCHEDULED')
                  AND (
                      t.global_authority = TRUE
                      OR (
                          routing_task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')
                          AND r.operational_scope_ready = TRUE
                          AND request_locality.supported_city_id = r.supported_city_id
                      )
                  )
                UNION ALL
                SELECT 'SESSION'::text AS target_type, s.id AS target_id,
                       s.supported_city_id AS city_id, c.display_name AS city_display_name,
                       s.operating_team_id AS team_id, t.team_display_name,
                       s.status::text AS status, s.created_at::timestamp AS created_at
                FROM visit_sessions s
                JOIN authorized_teams t ON t.team_id = s.operating_team_id
                                           AND t.city_id = s.supported_city_id
                LEFT JOIN localities session_locality
                       ON session_locality.id = s.canonical_locality_id
                JOIN supported_cities c ON c.id = s.supported_city_id AND c.active = TRUE
                JOIN operating_teams ot ON ot.id = s.operating_team_id AND ot.active = TRUE
                                          AND ot.city_id = s.supported_city_id
                WHERE s.coordinator_user_id IS NULL
                  AND s.status IN ('DRAFT', 'SCHEDULED', 'STARTED', 'PROVISIONAL_NO_SHOW',
                                   'REPAIR_REQUIRED', 'INTERRUPTED')
                  AND (
                      t.global_authority = TRUE
                      OR (
                          s.operational_scope_ready = TRUE
                          AND session_locality.supported_city_id = s.supported_city_id
                      )
                  )
            )
            """;

    static final String PAGE_SQL = AUTHORIZED_TEAMS + """
            SELECT target_type, target_id, city_id, city_display_name,
                   team_id, team_display_name, status, created_at,
                   count(*) OVER() AS total_count
            FROM targets
            ORDER BY created_at ASC, target_id ASC, target_type ASC
            OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RoutingOversightRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageSnapshot page(long userId, long offset, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("offset", offset)
                .addValue("size", size);
        List<Row> rows = jdbc.query(PAGE_SQL, parameters, (rs, rowNum) -> new Row(
                rs.getString("target_type"), rs.getLong("target_id"), rs.getLong("city_id"),
                rs.getString("city_display_name"), rs.getLong("team_id"),
                rs.getString("team_display_name"), rs.getString("status"),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getLong("total_count")));
        if (rows.isEmpty()) {
            Long total = jdbc.queryForObject(
                    AUTHORIZED_TEAMS + " SELECT count(*) FROM targets",
                    parameters, Long.class);
            return new PageSnapshot(total == null ? 0L : total, List.of());
        }
        long total = rows.get(0).totalCount();
        return new PageSnapshot(total, rows.stream().map(Row::toView).toList());
    }

    private record Row(String targetType, Long targetId, Long cityId, String cityDisplayName,
                       Long teamId, String teamDisplayName, String status,
                       java.time.LocalDateTime createdAt, long totalCount) {
        private RoutingUnclaimedWorkView toView() {
            return new RoutingUnclaimedWorkView(targetType, targetId, cityId, cityDisplayName,
                    teamId, teamDisplayName, status, createdAt);
        }
    }

    public record PageSnapshot(long totalCount, List<RoutingUnclaimedWorkView> items) {}
}
