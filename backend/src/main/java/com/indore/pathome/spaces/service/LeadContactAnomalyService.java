package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.crm.LeadContactAnomalyView;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scoped metadata review for unusual Lead-based lessor-contact volume. Counts alone never deny a
 * lawful reveal; only a current global Staff Admin may inspect the anomaly page.
 */
@Service
public class LeadContactAnomalyService {
    private static final int MINIMUM_REVEALS = 80;

    private final StaffAccessService staffAccess;
    private final DatabaseClock databaseClock;
    private final JdbcTemplate jdbc;

    public LeadContactAnomalyService(StaffAccessService staffAccess,
                                     DatabaseClock databaseClock,
                                     JdbcTemplate jdbc) {
        this.staffAccess = staffAccess;
        this.databaseClock = databaseClock;
        this.jdbc = jdbc;
    }

    public List<LeadContactAnomalyView> page(Long actorId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("page must be nonnegative and size must be 1..50");
        }
        staffAccess.requireAttributableUser(actorId);
        boolean globalAdmin = staffAccess.effectiveGrants(actorId).stream().anyMatch(grant ->
                grant.getCapability() == StaffCapability.STAFF_ADMIN
                        && grant.getScopeType() == StaffScopeType.GLOBAL);
        if (!globalAdmin) {
            throw new AccessDeniedException("Global Staff Admin authority is required");
        }
        Timestamp since = Timestamp.from(databaseClock.now().minus(Duration.ofHours(24)));
        long offset = (long) page * size;
        return jdbc.query("""
                select actor_user_id, city_id, team_id, count(*) as reveal_count,
                       max(occurred_at) as latest_at
                from operational_audit_events
                where action_code = 'LEAD_LESSOR_CONTACT_REVEALED'
                  and occurred_at >= ?
                group by actor_user_id, city_id, team_id
                having count(*) >= ?
                order by latest_at desc, actor_user_id asc
                limit ? offset ?
                """,
                (rows, rowNumber) -> new LeadContactAnomalyView(
                        rows.getLong("actor_user_id"),
                        (Long) rows.getObject("city_id", Long.class),
                        (Long) rows.getObject("team_id", Long.class),
                        rows.getInt("reveal_count"),
                        rows.getTimestamp("latest_at").toInstant()),
                since, MINIMUM_REVEALS, size, offset);
    }
}
