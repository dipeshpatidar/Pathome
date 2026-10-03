package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSessionOutcomeReport;
import com.indore.pathome.spaces.dto.GroundPendingVisitOutcomeView;
import com.indore.pathome.spaces.dto.VisitSessionOutcomeExceptionView;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VisitSessionOutcomeReportRepository extends JpaRepository<VisitSessionOutcomeReport, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VisitSessionOutcomeReport r where r.sessionId = :sessionId")
    Optional<VisitSessionOutcomeReport> findLockedBySessionId(@Param("sessionId") Long sessionId);

    List<VisitSessionOutcomeReport> findAllBySessionIdIn(Collection<Long> sessionIds);

    @Query("select new com.indore.pathome.spaces.dto.GroundPendingVisitOutcomeView("
            + "s.id, s.status, s.scheduledAt, s.finishedAt, s.city, "
            + "(select count(o) from VisitSessionItemOutcome o where o.sessionId = s.id "
            + "and o.outcomeState = com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState.UNRECORDED), "
            + "(select count(o) from VisitSessionItemOutcome o where o.sessionId = s.id)) "
            + "from VisitSessionOutcomeReport r join r.session s "
            + "where r.state = com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState.OPEN "
            + "and s.status = com.indore.pathome.spaces.entity.VisitSessionStatus.COMPLETED "
            + "and s.representative.id = :groundExecutiveId "
            + "order by s.finishedAt desc, s.id desc")
    Page<GroundPendingVisitOutcomeView> findGroundPendingOutcomes(
            @Param("groundExecutiveId") Long groundExecutiveId, Pageable pageable);

    @Query("select new com.indore.pathome.spaces.dto.VisitSessionOutcomeExceptionView("
            + "r.sessionId, r.state, s.status, s.city, s.finishedAt, r.scopeCapturedAt, r.updatedAt, null, "
            + "(select count(o) from VisitSessionItemOutcome o where o.sessionId = r.sessionId), "
            + "(select count(o) from VisitSessionItemOutcome o where o.sessionId = r.sessionId "
            + "and o.outcomeState = com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState.UNRECORDED), "
            + "(select count(o) from VisitSessionItemOutcome o where o.sessionId = r.sessionId "
            + "and o.outcomeState = com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState.VISITED)) "
            + "from VisitSessionOutcomeReport r join r.session s "
            + "where (r.state = com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState.OPEN "
            + "and s.status = com.indore.pathome.spaces.entity.VisitSessionStatus.COMPLETED "
            + "and s.finishedAt <= :finishedBefore) "
            + "or (r.state = com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState.FINALIZED "
            + "and not exists (select o.itemId from VisitSessionItemOutcome o where o.sessionId = r.sessionId "
            + "and o.outcomeState = com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState.VISITED)) "
            + "order by s.finishedAt asc, r.sessionId asc")
    Page<VisitSessionOutcomeExceptionView> findOperationsExceptions(
            @Param("finishedBefore") java.time.Instant finishedBefore, Pageable pageable);
}
