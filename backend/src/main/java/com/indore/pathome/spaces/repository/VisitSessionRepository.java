package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.dto.GroundExecutiveReservation;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitSessionRepository extends JpaRepository<VisitSession, Long> {
    @Query(value = "SELECT s.* FROM visit_sessions s WHERE s.id = :sessionId AND "
            + OperationalVisibilitySql.STAFF_SESSION_VISIBLE, nativeQuery = true)
    Optional<VisitSession> findVisibleToStaffById(@Param("userId") Long userId,
                                                   @Param("sessionId") Long sessionId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM visit_sessions s WHERE s.id = :sessionId AND "
            + OperationalVisibilitySql.NOTIFICATION_SESSION_VISIBLE + ")", nativeQuery = true)
    boolean isVisibleForNotification(@Param("userId") Long userId,
                                     @Param("sessionId") Long sessionId,
                                     @Param("recipientRole") String recipientRole);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VisitSession s where s.id = :id")
    Optional<VisitSession> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VisitSession s where s.id = :id and s.tenant.id = :tenantId")
    Optional<VisitSession> findLockedByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Query("select (count(s) > 0) from VisitSession s "
            + "where s.representative.id = :representativeId and s.status in :statuses "
            + "and s.id <> :excludedSessionId and s.scheduledAt < :reservedEnd "
            + "and s.reservedEndAt > :reservedStart")
    boolean existsOverlappingReservation(@Param("representativeId") Long representativeId,
                                         @Param("statuses") Collection<VisitSessionStatus> statuses,
                                         @Param("excludedSessionId") Long excludedSessionId,
                                         @Param("reservedStart") Instant reservedStart,
                                         @Param("reservedEnd") Instant reservedEnd);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"representative"})
    Page<VisitSession> findByRepresentativeIdAndStatusOrderByScheduledAtAscIdAsc(
            Long representativeId, VisitSessionStatus status, Pageable pageable);

    Page<VisitSession> findByRepresentativeIdAndStatusInOrderByScheduledAtAscIdAsc(
            Long representativeId, Collection<VisitSessionStatus> statuses, Pageable pageable);

    Page<VisitSession> findByTenantId(Long tenantId, Pageable pageable);

    Page<VisitSession> findByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId, Pageable pageable);

    Optional<VisitSession> findByIdAndTenantId(Long id, Long tenantId);

    Page<VisitSession> findByStatusOrderByScheduledAtAsc(VisitSessionStatus status, Pageable pageable);

    long countByRepresentativeIdAndStatus(Long representativeId, VisitSessionStatus status);

    @Query("select new com.indore.pathome.spaces.dto.GroundExecutiveReservation("
            + "s.id, s.representative.id, s.scheduledAt, s.reservedEndAt, s.status) "
            + "from VisitSession s where s.representative.id = :representativeId "
            + "and s.status in :activeStatuses and s.scheduledAt < :windowEnd "
            + "and s.reservedEndAt > :windowStart order by s.scheduledAt, s.id")
    List<GroundExecutiveReservation> findActiveReservationsOverlapping(
            @Param("representativeId") Long representativeId,
            @Param("activeStatuses") Collection<VisitSessionStatus> activeStatuses,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    @Query("select s from VisitSession s where s.representative.id in :representativeIds "
            + "and s.status in :activeStatuses and s.scheduledAt < :windowEnd "
            + "and s.reservedEndAt > :windowStart and (:excludedSessionId is null or s.id <> :excludedSessionId) "
            + "order by s.representative.id, s.scheduledAt, s.id")
    List<VisitSession> findActiveItinerariesForRepresentatives(
            @Param("representativeIds") Collection<Long> representativeIds,
            @Param("activeStatuses") Collection<VisitSessionStatus> activeStatuses,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludedSessionId") Long excludedSessionId,
            org.springframework.data.domain.Pageable pageable);

    @Query("select s from VisitSession s where s.representative.id in :representativeIds "
            + "and s.status in :activeStatuses and s.reservedEndAt <= :windowStart "
            + "and (:excludedSessionId is null or s.id <> :excludedSessionId) "
            + "and not exists (select nearer.id from VisitSession nearer "
            + "where nearer.representative.id = s.representative.id and nearer.status in :activeStatuses "
            + "and (:excludedSessionId is null or nearer.id <> :excludedSessionId) "
            + "and nearer.reservedEndAt <= :windowStart and (nearer.reservedEndAt > s.reservedEndAt "
            + "or (nearer.reservedEndAt = s.reservedEndAt and nearer.scheduledAt > s.scheduledAt) "
            + "or (nearer.reservedEndAt = s.reservedEndAt and nearer.scheduledAt = s.scheduledAt "
            + "and nearer.id > s.id))) "
            + "order by s.representative.id, s.reservedEndAt desc, s.scheduledAt desc, s.id desc")
    List<VisitSession> findNearestActiveReservationsBefore(
            @Param("representativeIds") Collection<Long> representativeIds,
            @Param("activeStatuses") Collection<VisitSessionStatus> activeStatuses,
            @Param("windowStart") Instant windowStart,
            @Param("excludedSessionId") Long excludedSessionId);

    @Query("select s from VisitSession s where s.representative.id in :representativeIds "
            + "and s.status in :activeStatuses and s.scheduledAt >= :windowEnd "
            + "and (:excludedSessionId is null or s.id <> :excludedSessionId) "
            + "and not exists (select nearer.id from VisitSession nearer "
            + "where nearer.representative.id = s.representative.id and nearer.status in :activeStatuses "
            + "and (:excludedSessionId is null or nearer.id <> :excludedSessionId) "
            + "and nearer.scheduledAt >= :windowEnd and (nearer.scheduledAt < s.scheduledAt "
            + "or (nearer.scheduledAt = s.scheduledAt and nearer.id < s.id))) "
            + "order by s.representative.id, s.scheduledAt, s.id")
    List<VisitSession> findNearestActiveReservationsAfter(
            @Param("representativeIds") Collection<Long> representativeIds,
            @Param("activeStatuses") Collection<VisitSessionStatus> activeStatuses,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludedSessionId") Long excludedSessionId);

}
