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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VisitSession s where s.id = :id")
    Optional<VisitSession> findLockedById(@Param("id") Long id);

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

    Page<VisitSession> findByTenantId(Long tenantId, Pageable pageable);

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

}
