package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.VisitSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OeDiscoveryCursorTest {
    private static final String SECRET = "cursor-test-jwt-secret-32-bytes-minimum";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void cursorBindsActorSessionScopeFilterSizeAndLifetime() {
        VisitSession session = session(1L, 2L, 3L, 4L);
        String cursor = OeDiscoveryCursor.encode(SECRET, 5L, session, "ravi", 20, 101L, NOW);
        assertEquals(101L, OeDiscoveryCursor.decode(SECRET, cursor, 5L,
                session, "ravi", 20, NOW.plusSeconds(600)));
        assertInvalid(cursor, 6L, session, "ravi", 20, NOW);
        assertInvalid(cursor, 5L, session(9L, 2L, 3L, 4L), "ravi", 20, NOW);
        assertInvalid(cursor, 5L, session(1L, 3L, 3L, 4L), "ravi", 20, NOW);
        assertInvalid(cursor, 5L, session(1L, 2L, 9L, 4L), "ravi", 20, NOW);
        assertInvalid(cursor, 5L, session(1L, 2L, 3L, 9L), "ravi", 20, NOW);
        assertInvalid(cursor, 5L, session, "other", 20, NOW);
        assertInvalid(cursor, 5L, session, "ravi", 21, NOW);
        assertInvalid(cursor, 5L, session, "ravi", 20, NOW.plusSeconds(601));
        assertInvalid(cursor, 5L, session, "ravi", 20, NOW.minusSeconds(1));
        assertInvalid(cursor + "A", 5L, session, "ravi", 20, NOW);
        assertInvalid(cursor.replace(".", "=."), 5L, session, "ravi", 20, NOW);
        assertInvalid("garbage", 5L, session, "ravi", 20, NOW);
    }

    private void assertInvalid(String cursor, Long actor, VisitSession session,
            String query, int size, Instant at) {
        assertThrows(IllegalArgumentException.class, () -> OeDiscoveryCursor.decode(
                SECRET, cursor, actor, session, query, size, at));
    }

    private VisitSession session(Long id, Long version, Long cityId, Long teamId) {
        VisitSession session = new VisitSession();
        session.setId(id);
        session.setVersion(version);
        SupportedCity city = mock(SupportedCity.class);
        OperatingTeam team = mock(OperatingTeam.class);
        when(city.getId()).thenReturn(cityId);
        when(team.getId()).thenReturn(teamId);
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        return session;
    }
}
