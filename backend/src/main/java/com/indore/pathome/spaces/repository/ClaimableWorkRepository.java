package com.indore.pathome.spaces.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ClaimableWorkItem;
import com.indore.pathome.spaces.dto.ClaimableWorkTargetType;
import com.indore.pathome.spaces.dto.LinkedRequestVersion;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** One-statement PostgreSQL snapshots for the deliberately separate claimable-work relationship. */
@Repository
public class ClaimableWorkRepository {
    private static final TypeReference<List<LinkedRequestVersion>> LINKED_VERSIONS = new TypeReference<>() {};

    private static final String AUTHORIZED_TEAM_GRANTS = """
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
            )
            """;

    private static final String ELIGIBLE_TEAMS = """
            eligible_teams AS MATERIALIZED (
                SELECT DISTINCT t.id AS team_id, c.id AS city_id,
                       c.display_name AS city_display_name, t.display_name AS team_display_name
                FROM authorized_team_grants ag
                JOIN operating_teams t ON t.id = ag.team_id AND t.active = TRUE
                JOIN supported_cities c ON c.id = t.city_id AND c.active = TRUE
            )
            """;

    private static final String TARGETS = """
            targets AS MATERIALIZED (
                SELECT 'REQUEST'::text AS target_type, r.id AS target_id, r.version AS expected_version,
                       r.supported_city_id AS city_id, c.display_name AS city_display_name,
                       r.operating_team_id AS team_id, t.display_name AS team_display_name,
                       r.status::text AS status
                FROM property_visit_requests r
                JOIN eligible_teams et ON et.team_id = r.operating_team_id
                                          AND et.city_id = r.supported_city_id
                JOIN supported_cities c ON c.id = r.supported_city_id AND c.active = TRUE
                JOIN operating_teams t ON t.id = r.operating_team_id AND t.active = TRUE
                                         AND t.city_id = r.supported_city_id
                WHERE r.session_id IS NULL
                  AND r.operational_scope_ready = TRUE
                  AND r.coordinator_user_id IS NULL
                  AND r.status IN ('RECEIVED', 'COORDINATING', 'SCHEDULED')
                UNION ALL
                SELECT 'SESSION'::text AS target_type, s.id AS target_id, s.version AS expected_version,
                       s.supported_city_id AS city_id, c.display_name AS city_display_name,
                       s.operating_team_id AS team_id, t.display_name AS team_display_name,
                       s.status::text AS status
                FROM visit_sessions s
                JOIN eligible_teams et ON et.team_id = s.operating_team_id
                                          AND et.city_id = s.supported_city_id
                JOIN supported_cities c ON c.id = s.supported_city_id AND c.active = TRUE
                JOIN operating_teams t ON t.id = s.operating_team_id AND t.active = TRUE
                                         AND t.city_id = s.supported_city_id
                WHERE s.operational_scope_ready = TRUE
                  AND s.coordinator_user_id IS NULL
                  AND s.status IN ('DRAFT', 'SCHEDULED', 'STARTED', 'PROVISIONAL_NO_SHOW',
                                   'REPAIR_REQUIRED', 'INTERRUPTED')
            )
            """;

    private static final String PAGE_SQL =
            "WITH request_clock AS MATERIALIZED (SELECT clock_timestamp() AS db_now),\n"
                    + AUTHORIZED_TEAM_GRANTS + ",\n" + ELIGIBLE_TEAMS + ",\n" + TARGETS + ",\n" + """
            totals AS (SELECT count(*)::bigint AS total_count FROM targets),
            page_roots AS MATERIALIZED (
                SELECT * FROM targets
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
                   COALESCE(lt.versions, '[]') AS linked_request_versions
            FROM totals
            LEFT JOIN page_roots p ON TRUE
            LEFT JOIN linked_tokens lt ON lt.target_id = p.target_id AND p.target_type = 'SESSION'
            ORDER BY CASE p.target_type WHEN 'REQUEST' THEN 0 WHEN 'SESSION' THEN 1 ELSE 2 END,
                     p.target_id ASC
            """;

    private static final String METADATA_SQL =
            "WITH request_clock AS MATERIALIZED (SELECT clock_timestamp() AS db_now),\n"
                    + AUTHORIZED_TEAM_GRANTS + ",\n" + ELIGIBLE_TEAMS + ",\n" + TARGETS + ",\n" + """
            selected_root AS MATERIALIZED (
                SELECT * FROM targets
                WHERE target_type = :targetType AND target_id = :targetId
            ),
            linked_tokens AS (
                SELECT r.session_id AS target_id,
                       jsonb_agg(jsonb_build_object('requestId', r.id, 'version', r.version)
                                 ORDER BY r.id ASC)::text AS versions
                FROM property_visit_requests r
                JOIN selected_root p ON p.target_type = 'SESSION' AND p.target_id = r.session_id
                GROUP BY r.session_id
            )
            SELECT EXISTS (SELECT 1 FROM eligible_teams) AS caller_eligible,
                   p.target_type, p.target_id, p.expected_version,
                   p.city_id, p.city_display_name, p.team_id, p.team_display_name, p.status,
                   COALESCE(lt.versions, '[]') AS linked_request_versions
            FROM (VALUES (1)) AS singleton(value)
            LEFT JOIN selected_root p ON TRUE
            LEFT JOIN linked_tokens lt ON lt.target_id = p.target_id AND p.target_type = 'SESSION'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ClaimableWorkRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Snapshot page(long userId, long offset, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("offset", offset)
                .addValue("size", size);
        List<SnapshotRow> rows = jdbc.query(PAGE_SQL, parameters, (rs, rowNum) -> new SnapshotRow(
                rs.getBoolean("caller_eligible"), rs.getLong("total_count"),
                rs.getString("target_type"), nullableLong(rs, "target_id"),
                nullableLong(rs, "expected_version"), nullableLong(rs, "city_id"),
                rs.getString("city_display_name"), nullableLong(rs, "team_id"),
                rs.getString("team_display_name"), rs.getString("status"),
                rs.getString("linked_request_versions")));
        if (rows.isEmpty()) throw new IllegalStateException("Claimable page snapshot did not return its totals row");
        SnapshotRow first = rows.get(0);
        List<ClaimableWorkItem> items = new ArrayList<>();
        for (SnapshotRow row : rows) {
            if (row.targetType() != null) items.add(toItem(row));
        }
        return new Snapshot(first.callerEligible(), first.totalCount(), List.copyOf(items));
    }

    public MetadataSnapshot metadata(long userId, ClaimableWorkTargetType targetType, long targetId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("targetType", targetType.name())
                .addValue("targetId", targetId);
        return jdbc.queryForObject(METADATA_SQL, parameters, (rs, rowNum) -> {
            String type = rs.getString("target_type");
            SnapshotRow row = type == null ? null : new SnapshotRow(true, 0L, type,
                    nullableLong(rs, "target_id"), nullableLong(rs, "expected_version"),
                    nullableLong(rs, "city_id"), rs.getString("city_display_name"),
                    nullableLong(rs, "team_id"), rs.getString("team_display_name"),
                    rs.getString("status"), rs.getString("linked_request_versions"));
            return new MetadataSnapshot(rs.getBoolean("caller_eligible"), row == null ? null : toItem(row));
        });
    }

    private ClaimableWorkItem toItem(SnapshotRow row) {
        try {
            List<LinkedRequestVersion> linked = objectMapper.readValue(row.linkedRequestVersions(), LINKED_VERSIONS);
            return new ClaimableWorkItem(ClaimableWorkTargetType.valueOf(row.targetType()), row.targetId(),
                    row.expectedVersion(), row.cityId(), row.cityDisplayName(), row.teamId(),
                    row.teamDisplayName(), row.status(), List.copyOf(linked));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not map claimable-work version snapshot", exception);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record SnapshotRow(boolean callerEligible, long totalCount, String targetType, Long targetId,
            Long expectedVersion, Long cityId, String cityDisplayName, Long teamId,
            String teamDisplayName, String status, String linkedRequestVersions) {}

    public record Snapshot(boolean callerEligible, long totalCount, List<ClaimableWorkItem> items) {}
    public record MetadataSnapshot(boolean callerEligible, ClaimableWorkItem item) {}
}
