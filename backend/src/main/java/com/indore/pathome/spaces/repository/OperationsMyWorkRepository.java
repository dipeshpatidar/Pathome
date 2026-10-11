package com.indore.pathome.spaces.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.LinkedRequestVersion;
import com.indore.pathome.spaces.dto.OperationsMyWorkItem;
import com.indore.pathome.spaces.dto.OperationsRootType;
import com.indore.pathome.spaces.dto.OperationsStandaloneRequestDetailView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-statement PostgreSQL snapshots for coordinator-owned work.
 * Scope, ownership and pagination are resolved inside PostgreSQL; rows are never filtered in Java.
 */
@Repository
public class OperationsMyWorkRepository {
    private static final TypeReference<List<LinkedRequestVersion>> LINKED_VERSIONS = new TypeReference<>() {};

    /**
     * Current, effective OPS_COORDINATE Team grants evaluated against database time, plus the
     * active Team/City pairs that make one of those grants usable right now.
     */
    private static final String SCOPE_CTES = """
            request_clock AS MATERIALIZED (SELECT clock_timestamp() AS db_now),
            authorized_team_grants AS MATERIALIZED (
                SELECT DISTINCT g.team_id
                FROM employee_profiles ep
                CROSS JOIN request_clock rc
                JOIN staff_access_grants g ON g.user_id = ep.user_id
                WHERE ep.user_id = :userId
                  AND ep.staff_active = TRUE
                  AND g.capability = 'OPS_COORDINATE'
                  AND g.scope_type = 'TEAM'
                  AND g.revoked_at IS NULL
                  AND g.effective_at <= rc.db_now
                  AND (g.expires_at IS NULL OR rc.db_now < g.expires_at)
            ),
            eligible_teams AS MATERIALIZED (
                SELECT DISTINCT t.id AS team_id, c.id AS city_id,
                       c.display_name AS city_display_name, t.display_name AS team_display_name
                FROM authorized_team_grants ag
                JOIN operating_teams t ON t.id = ag.team_id AND t.active = TRUE
                JOIN supported_cities c ON c.id = t.city_id AND c.active = TRUE
            ),
            """;

    /**
     * Claimed standalone Requests are their own root; once a Request is linked its Session is the
     * authoritative root, so no Request root survives linkage and no root can duplicate.
     *
     * Needs-attention truth is restricted to persisted states the repository already treats as
     * requiring coordinator action: an owned Request still awaiting coordination, a draft without a
     * confirmed schedule, a scheduled Visit whose tenant confirmation decision is still outstanding
     * (the same PENDING convention the outcome lifecycle uses for ACTION_REQUIRED), repair-required,
     * provisional no-show and interrupted Visits.
     *
     * UNAVAILABLE is deliberately excluded: the frozen lifecycle leaves it terminal for the owner, so
     * surfacing it as needing attention would invent unsupported actionable work. It remains visible
     * in unfiltered My Work.
     */
    private static final String PAGE_SQL = "WITH " + SCOPE_CTES + """
            scoped_roots AS MATERIALIZED (
                SELECT 'REQUEST'::text AS target_type, r.id AS target_id, r.version AS expected_version,
                       r.supported_city_id AS city_id, c.display_name AS city_display_name,
                       r.operating_team_id AS team_id, t.display_name AS team_display_name,
                       r.status::text AS status,
                       NULL::timestamptz AS root_updated_at,
                       NULL::timestamptz AS root_scheduled_at,
                       NULL::text AS tenant_confirmation_state,
                       (r.status = 'RECEIVED') AS needs_attention
                FROM property_visit_requests r
                JOIN eligible_teams et ON et.team_id = r.operating_team_id
                                          AND et.city_id = r.supported_city_id
                JOIN supported_cities c ON c.id = r.supported_city_id AND c.active = TRUE
                JOIN operating_teams t ON t.id = r.operating_team_id AND t.active = TRUE
                                         AND t.city_id = r.supported_city_id
                WHERE r.session_id IS NULL
                  AND r.operational_scope_ready = TRUE
                  AND r.coordinator_user_id = :userId
                UNION ALL
                SELECT 'SESSION'::text AS target_type, s.id AS target_id, s.version AS expected_version,
                       s.supported_city_id AS city_id, c.display_name AS city_display_name,
                       s.operating_team_id AS team_id, t.display_name AS team_display_name,
                       s.status::text AS status,
                       s.updated_at AS root_updated_at,
                       s.scheduled_at AS root_scheduled_at,
                       s.tenant_confirmation_state AS tenant_confirmation_state,
                       ((s.status = 'DRAFT' AND s.scheduled_at IS NULL)
                        OR (s.status = 'SCHEDULED'
                            AND s.tenant_confirmation_state IN ('PENDING', 'PENDING_CONFIRMATION'))
                        OR s.status IN ('REPAIR_REQUIRED', 'PROVISIONAL_NO_SHOW', 'INTERRUPTED')) AS needs_attention
                FROM visit_sessions s
                JOIN eligible_teams et ON et.team_id = s.operating_team_id
                                          AND et.city_id = s.supported_city_id
                JOIN supported_cities c ON c.id = s.supported_city_id AND c.active = TRUE
                JOIN operating_teams t ON t.id = s.operating_team_id AND t.active = TRUE
                                         AND t.city_id = s.supported_city_id
                WHERE s.operational_scope_ready = TRUE
                  AND s.coordinator_user_id = :userId
            ),
            filtered_roots AS MATERIALIZED (
                SELECT * FROM scoped_roots
                WHERE CAST(:attentionOnly AS boolean) = FALSE OR needs_attention = TRUE
            ),
            totals AS (SELECT count(*)::bigint AS total_count FROM filtered_roots),
            page_roots AS MATERIALIZED (
                SELECT * FROM filtered_roots
                ORDER BY CASE target_type WHEN 'REQUEST' THEN 0 ELSE 1 END, target_id ASC
                OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
            ),
            linked_tokens AS (
                SELECT r.session_id AS target_id,
                       jsonb_agg(jsonb_build_object('requestId', r.id, 'version', r.version)
                                 ORDER BY r.id ASC)::text AS versions
                FROM property_visit_requests r
                JOIN page_roots p ON p.target_type = 'SESSION' AND p.target_id = r.session_id
                GROUP BY r.session_id
            )
            SELECT EXISTS (SELECT 1 FROM eligible_teams) AS caller_eligible,
                   totals.total_count, p.target_type, p.target_id, p.expected_version,
                   p.city_id, p.city_display_name, p.team_id, p.team_display_name, p.status,
                   p.root_updated_at, p.root_scheduled_at, p.tenant_confirmation_state,
                   COALESCE(lt.versions, '[]') AS linked_request_versions
            FROM totals
            LEFT JOIN page_roots p ON TRUE
            LEFT JOIN linked_tokens lt ON lt.target_id = p.target_id AND p.target_type = 'SESSION'
            ORDER BY CASE p.target_type WHEN 'REQUEST' THEN 0 WHEN 'SESSION' THEN 1 ELSE 2 END,
                     p.target_id ASC
            """;

    private static final String REQUEST_DETAIL_SQL = "WITH " + SCOPE_CTES + """
            selected_root AS MATERIALIZED (
                SELECT r.id AS request_id, r.status::text AS status, r.version AS expected_version,
                       r.created_at AS created_at,
                       r.supported_city_id AS city_id, c.display_name AS city_display_name,
                       r.operating_team_id AS team_id, t.display_name AS team_display_name,
                       l.id AS listing_id, l.title AS listing_title, l.address AS listing_address,
                       l.status::text AS listing_status,
                       COALESCE(loc.city, l.city) AS listing_city, l.sector AS sector,
                       r.availability_start_at AS availability_start_at,
                       r.availability_end_at AS availability_end_at,
                       r.availability_zone_id AS availability_zone_id,
                       r.preferred_at AS preferred_at,
                       r.session_id AS session_id
                FROM property_visit_requests r
                JOIN listings l ON l.id = r.listing_id
                LEFT JOIN localities loc ON loc.id = l.canonical_locality_id
                JOIN eligible_teams et ON et.team_id = r.operating_team_id
                                          AND et.city_id = r.supported_city_id
                JOIN supported_cities c ON c.id = r.supported_city_id AND c.active = TRUE
                JOIN operating_teams t ON t.id = r.operating_team_id AND t.active = TRUE
                                         AND t.city_id = r.supported_city_id
                WHERE r.id = :requestId
                  AND r.session_id IS NULL
                  AND r.operational_scope_ready = TRUE
                  AND r.coordinator_user_id = :userId
            )
            SELECT EXISTS (SELECT 1 FROM eligible_teams) AS caller_eligible,
                   s.request_id, s.status, s.expected_version, s.created_at,
                   s.city_id, s.city_display_name, s.team_id, s.team_display_name,
                   s.listing_id, s.listing_title, s.listing_address, s.listing_status,
                   s.listing_city, s.sector, s.availability_start_at, s.availability_end_at,
                   s.availability_zone_id, s.preferred_at, s.session_id
            FROM (VALUES (1)) AS singleton(value)
            LEFT JOIN selected_root s ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OperationsMyWorkRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Snapshot page(long userId, long offset, int size, boolean attentionOnly) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("offset", offset)
                .addValue("size", size)
                .addValue("attentionOnly", attentionOnly);
        List<SnapshotRow> rows = jdbc.query(PAGE_SQL, parameters, (rs, rowNum) -> new SnapshotRow(
                rs.getBoolean("caller_eligible"), rs.getLong("total_count"),
                rs.getString("target_type"), nullableLong(rs, "target_id"),
                nullableLong(rs, "expected_version"), nullableLong(rs, "city_id"),
                rs.getString("city_display_name"), nullableLong(rs, "team_id"),
                rs.getString("team_display_name"), rs.getString("status"),
                nullableInstant(rs, "root_updated_at"), nullableInstant(rs, "root_scheduled_at"),
                rs.getString("tenant_confirmation_state"), rs.getString("linked_request_versions")));
        if (rows.isEmpty()) throw new IllegalStateException("My Work snapshot did not return its totals row");
        SnapshotRow first = rows.get(0);
        List<OperationsMyWorkItem> items = new ArrayList<>();
        for (SnapshotRow row : rows) {
            if (row.targetType() != null) items.add(toItem(row));
        }
        return new Snapshot(first.callerEligible(), first.totalCount(), List.copyOf(items));
    }

    public DetailSnapshot requestDetail(long userId, long requestId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("requestId", requestId);
        return jdbc.queryForObject(REQUEST_DETAIL_SQL, parameters, (rs, rowNum) -> {
            Long id = nullableLong(rs, "request_id");
            OperationsStandaloneRequestDetailView detail = id == null ? null
                    : new OperationsStandaloneRequestDetailView(id, rs.getString("status"),
                            nullableLong(rs, "expected_version"), nullableLocalDateTime(rs, "created_at"),
                            nullableLong(rs, "city_id"), rs.getString("city_display_name"),
                            nullableLong(rs, "team_id"), rs.getString("team_display_name"),
                            nullableLong(rs, "listing_id"), rs.getString("listing_title"),
                            rs.getString("listing_address"), rs.getString("listing_status"),
                            rs.getString("listing_city"), rs.getString("sector"),
                            nullableInstant(rs, "availability_start_at"),
                            nullableInstant(rs, "availability_end_at"),
                            rs.getString("availability_zone_id"), nullableInstant(rs, "preferred_at"),
                            nullableLong(rs, "session_id"));
            return new DetailSnapshot(rs.getBoolean("caller_eligible"), detail);
        });
    }

    private OperationsMyWorkItem toItem(SnapshotRow row) {
        try {
            List<LinkedRequestVersion> linked = objectMapper.readValue(row.linkedRequestVersions(), LINKED_VERSIONS);
            return new OperationsMyWorkItem(OperationsRootType.valueOf(row.targetType()), row.targetId(),
                    row.expectedVersion(), row.cityId(), row.cityDisplayName(), row.teamId(),
                    row.teamDisplayName(), row.status(), row.updatedAt(), row.scheduledAt(),
                    row.tenantConfirmationState(), List.copyOf(linked));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not map My Work version snapshot", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** created_at is TIMESTAMP WITHOUT TIME ZONE: keep the stored local-time meaning intact. */
    private static LocalDateTime nullableLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private record SnapshotRow(boolean callerEligible, long totalCount, String targetType, Long targetId,
            Long expectedVersion, Long cityId, String cityDisplayName, Long teamId,
            String teamDisplayName, String status, Instant updatedAt, Instant scheduledAt,
            String tenantConfirmationState, String linkedRequestVersions) {}

    public record Snapshot(boolean callerEligible, long totalCount, List<OperationsMyWorkItem> items) {}
    public record DetailSnapshot(boolean callerEligible, OperationsStandaloneRequestDetailView detail) {}
}
