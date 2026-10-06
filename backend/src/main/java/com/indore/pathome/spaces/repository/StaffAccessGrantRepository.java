package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.StaffAccessGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface StaffAccessGrantRepository extends JpaRepository<StaffAccessGrant, Long> {
    @Query(value = """
            SELECT g.*
            FROM staff_access_grants g
            JOIN employee_profiles p ON p.user_id = g.user_id AND p.staff_active = TRUE
            LEFT JOIN operating_teams t ON t.id = g.team_id
            LEFT JOIN supported_cities c ON c.id = COALESCE(g.city_id, t.city_id)
            CROSS JOIN (SELECT clock_timestamp() AS db_now) clock
            WHERE g.user_id = :userId
              AND g.revoked_at IS NULL
              AND g.effective_at <= clock.db_now
              AND (g.expires_at IS NULL OR clock.db_now < g.expires_at)
              AND (g.scope_type = 'GLOBAL'
                   OR (g.scope_type = 'CITY' AND c.active = TRUE)
                   OR (g.scope_type = 'TEAM' AND t.active = TRUE AND c.active = TRUE))
            ORDER BY g.capability, g.scope_type, g.city_id, g.team_id, g.effective_at
            """, nativeQuery = true)
    List<StaffAccessGrant> findEffectiveForUser(@Param("userId") Long userId);

    @Query(value = """
            SELECT COUNT(DISTINCT g.user_id)
            FROM staff_access_grants g
            JOIN employee_profiles p ON p.user_id = g.user_id AND p.staff_active = TRUE
            CROSS JOIN (SELECT clock_timestamp() AS db_now) clock
            WHERE g.capability = 'STAFF_ADMIN'
              AND g.scope_type = 'GLOBAL'
              AND g.revoked_at IS NULL
              AND g.effective_at <= clock.db_now
              AND (g.expires_at IS NULL OR clock.db_now < g.expires_at)
            """, nativeQuery = true)
    long countEffectiveGlobalAdmins();

    @Query(value = """
            SELECT COUNT(DISTINCT g.user_id)
            FROM staff_access_grants g
            JOIN employee_profiles p ON p.user_id = g.user_id AND p.staff_active = TRUE
            CROSS JOIN (SELECT clock_timestamp() AS db_now) clock
            WHERE g.capability = 'STAFF_ADMIN'
              AND g.scope_type = 'GLOBAL'
              AND g.user_id <> :excludedUserId
              AND g.revoked_at IS NULL
              AND g.effective_at <= clock.db_now
              AND (g.expires_at IS NULL OR clock.db_now < g.expires_at)
            """, nativeQuery = true)
    long countEffectiveGlobalAdminsExcluding(@Param("excludedUserId") Long excludedUserId);

    @Query(value = """
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
            FROM staff_access_grants g
            JOIN employee_profiles p ON p.user_id = g.user_id AND p.staff_active = TRUE
            CROSS JOIN (SELECT clock_timestamp() AS db_now) clock
            WHERE g.user_id = :userId
              AND g.capability = 'STAFF_ADMIN'
              AND g.scope_type = 'GLOBAL'
              AND g.revoked_at IS NULL
              AND g.effective_at <= clock.db_now
              AND (g.expires_at IS NULL OR clock.db_now < g.expires_at)
            """, nativeQuery = true)
    boolean hasEffectiveGlobalAdmin(@Param("userId") Long userId);

    @Query(value = """
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
            FROM staff_access_grants
            WHERE capability = 'STAFF_ADMIN' AND scope_type = 'GLOBAL'
            """, nativeQuery = true)
    boolean hasHistoricalGlobalAdminGrant();

    @Query(value = """
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
            FROM staff_access_grants g
            WHERE g.user_id = :userId
              AND g.capability = :capability
              AND g.scope_type = :scopeType
              AND g.city_id IS NOT DISTINCT FROM :cityId
              AND g.team_id IS NOT DISTINCT FROM :teamId
              AND g.effective_at < COALESCE(CAST(:expiresAt AS timestamptz), 'infinity'::timestamptz)
              AND LEAST(COALESCE(g.expires_at, 'infinity'::timestamptz),
                       COALESCE(g.revoked_at, 'infinity'::timestamptz)) > :effectiveAt
            """, nativeQuery = true)
    boolean existsOverlappingUnrevoked(@Param("userId") Long userId,
                                        @Param("capability") String capability,
                                        @Param("scopeType") String scopeType,
                                        @Param("cityId") Long cityId,
                                        @Param("teamId") Long teamId,
                                        @Param("effectiveAt") Instant effectiveAt,
                                        @Param("expiresAt") Instant expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from StaffAccessGrant grant where grant.id = :id")
    Optional<StaffAccessGrant> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from StaffAccessGrant grant where grant.user.id = :userId and grant.revokedAt is null order by grant.id")
    List<StaffAccessGrant> findUnrevokedLockedByUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from StaffAccessGrant grant where grant.user.id in :userIds order by grant.id")
    List<StaffAccessGrant> findLockedByUserIdsOrderById(@Param("userIds") Collection<Long> userIds);

    List<StaffAccessGrant> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
