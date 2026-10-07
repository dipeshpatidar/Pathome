package com.indore.pathome.spaces.repository;

/** Shared PostgreSQL predicates for current, canonical operational visibility. */
public final class OperationalVisibilitySql {
    private OperationalVisibilitySql() {}

    public static final String STAFF_SESSION_VISIBLE = """
            EXISTS (
                SELECT 1
                FROM employee_profiles ep
                JOIN staff_access_grants g ON g.user_id = ep.user_id
                LEFT JOIN operating_teams grant_team ON grant_team.id = g.team_id
                LEFT JOIN supported_cities grant_city ON grant_city.id = COALESCE(g.city_id, grant_team.city_id)
                CROSS JOIN (SELECT clock_timestamp() AS db_now) scope_clock
                WHERE ep.user_id = :userId
                  AND ep.staff_active = TRUE
                  AND g.revoked_at IS NULL
                  AND g.effective_at <= scope_clock.db_now
                  AND (g.expires_at IS NULL OR scope_clock.db_now < g.expires_at)
                  AND (
                      (g.capability = 'STAFF_ADMIN' AND g.scope_type = 'GLOBAL')
                      OR (g.capability = 'OPS_SUPERVISE' AND g.scope_type = 'TEAM'
                          AND grant_team.active = TRUE AND grant_city.active = TRUE
                          AND grant_team.city_id = s.supported_city_id
                          AND s.operational_scope_ready = TRUE
                          AND s.operating_team_id = g.team_id)
                      OR (g.capability = 'OPS_COORDINATE' AND g.scope_type = 'TEAM'
                          AND grant_team.active = TRUE AND grant_city.active = TRUE
                          AND grant_team.city_id = s.supported_city_id
                          AND s.operational_scope_ready = TRUE
                          AND s.operating_team_id = g.team_id
                          AND s.coordinator_user_id = :userId)
                  )
            )
            """;

    public static final String GROUND_EXECUTIVE_SESSION_VISIBLE = """
            s.representative_user_id = :userId
            AND EXISTS (
                SELECT 1 FROM users ge_user
                JOIN employee_profiles ge_profile ON ge_profile.user_id = ge_user.id
            WHERE ge_user.id = :userId
                  AND ge_user.role = 'ROLE_GROUND_BOY'
                  AND ge_profile.role_type ILIKE 'GROUND_BOY'
                  AND ge_profile.staff_active = TRUE
            )
            AND EXISTS (
                SELECT 1 FROM supported_cities ge_city
                WHERE ge_city.id = s.supported_city_id
                  AND ge_city.active = TRUE
            )
            AND (
                s.operating_team_id IS NULL
                OR EXISTS (
                    SELECT 1 FROM operating_teams ge_team
                    WHERE ge_team.id = s.operating_team_id
                      AND ge_team.city_id = s.supported_city_id
                      AND ge_team.active = TRUE
                )
            )
            """;

    public static final String NOTIFICATION_SESSION_VISIBLE =
            "(" + STAFF_SESSION_VISIBLE
                    + " OR (:recipientRole = 'GROUND_BOY' AND "
                    + GROUND_EXECUTIVE_SESSION_VISIBLE + "))";

    /** Row-level predicate for the user's notification feed, counters, and read mutations. */
    public static final String NOTIFICATION_ROW_VISIBLE =
            "(n.authorization_class = 'RECIPIENT' OR "
                    + "(n.authorization_class = 'OPERATIONS_SESSION' "
                    + "AND n.operational_session_id IS NOT NULL "
                    + "AND EXISTS (SELECT 1 FROM visit_sessions s "
                    + "WHERE s.id = n.operational_session_id AND ("
                    + STAFF_SESSION_VISIBLE
                    + " OR (n.target_role = 'GROUND_BOY' AND "
                    + GROUND_EXECUTIVE_SESSION_VISIBLE
                    + ")))))";

    public static final String REQUEST_VISIBLE = " " + """
            EXISTS (
                SELECT 1
                FROM employee_profiles ep
                JOIN staff_access_grants g ON g.user_id = ep.user_id
                LEFT JOIN operating_teams grant_team ON grant_team.id = g.team_id
                LEFT JOIN supported_cities grant_city ON grant_city.id = COALESCE(g.city_id, grant_team.city_id)
                CROSS JOIN (SELECT clock_timestamp() AS db_now) scope_clock
                WHERE ep.user_id = :userId
                  AND ep.staff_active = TRUE
                  AND g.revoked_at IS NULL
                  AND g.effective_at <= scope_clock.db_now
                  AND (g.expires_at IS NULL OR scope_clock.db_now < g.expires_at)
                  AND (
                      (g.capability = 'STAFF_ADMIN' AND g.scope_type = 'GLOBAL')
                      OR (g.capability = 'OPS_INTAKE' AND g.scope_type = 'CITY'
                          AND grant_city.active = TRUE
                          AND r.session_id IS NULL
                          AND r.status = 'RECEIVED'
                          AND r.operational_scope_ready = TRUE
                          AND r.supported_city_id = g.city_id)
                      OR (g.capability = 'OPS_SUPERVISE' AND g.scope_type = 'TEAM'
                          AND grant_team.active = TRUE AND grant_city.active = TRUE
                          AND grant_team.city_id = s.supported_city_id
                          AND s.operational_scope_ready = TRUE
                          AND s.operating_team_id = g.team_id
                          AND r.session_id IS NOT NULL)
                      OR (g.capability = 'OPS_COORDINATE' AND g.scope_type = 'TEAM'
                          AND grant_team.active = TRUE AND grant_city.active = TRUE
                          AND grant_team.city_id = s.supported_city_id
                          AND s.operational_scope_ready = TRUE
                          AND s.operating_team_id = g.team_id
                          AND s.coordinator_user_id = :userId
                          AND r.session_id IS NOT NULL)
                  )
            )
            """;
}
