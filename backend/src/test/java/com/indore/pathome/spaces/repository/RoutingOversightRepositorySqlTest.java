package com.indore.pathome.spaces.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingOversightRepositorySqlTest {

    @Test
    void unclaimedWorkMakesTheSingleDatabaseClockVisibleAndSharesItWithTheCount() {
        String sql = RoutingOversightRepository.AUTHORIZED_TEAMS;

        assertTrue(sql.contains("FROM request_clock clock"));
        assertTrue(sql.contains("g.effective_at <= clock.db_now"));
        assertTrue(sql.contains("clock.db_now < g.expires_at"));
        assertFalse(sql.contains("request_clock.db_now"));
        assertTrue(sql.contains("t.display_name AS team_display_name"));
        assertEquals(2, countOccurrences(sql, "t.team_display_name"),
                "both targets must read the authoritative CTE column");
        assertTrue(RoutingOversightRepository.PAGE_SQL.startsWith(sql));
        assertTrue(RoutingOversightRepository.PAGE_SQL.contains("count(*) OVER()"));
        assertTrue(RoutingOversightRepository.PAGE_SQL.contains(
                "ORDER BY created_at ASC, target_id ASC, target_type ASC"));
        assertTrue(sql.contains("JOIN visit_request_routing_tasks routing_task"),
                "Request visibility must require durable routing enrollment");
        assertTrue(sql.contains(
                "routing_task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')"),
                "terminal or superseded routing rows must not re-enter unclaimed work");
        assertTrue(sql.contains("JOIN localities request_locality"),
                "Request visibility must use the canonical locality");
        assertTrue(sql.contains(
                "request_locality.supported_city_id = r.supported_city_id"),
                "a foreign canonical locality must be concealed");
        assertTrue(sql.contains("JOIN localities session_locality"),
                "Session visibility must use its canonical locality");
        assertTrue(sql.contains(
                "session_locality.supported_city_id = s.supported_city_id"),
                "a Session with foreign canonical locality must be concealed");
        assertTrue(sql.contains("global_authority"),
                "global diagnostic scope must remain distinct from City routing scope");
    }

    private static int countOccurrences(String value, String token) {
        int count = 0;
        for (int index = value.indexOf(token); index >= 0;
             index = value.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }
}
