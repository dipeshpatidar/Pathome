package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcome;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VisitSessionItemOutcomeRepository extends JpaRepository<VisitSessionItemOutcome, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VisitSessionItemOutcome o where o.itemId = :itemId and o.sessionId = :sessionId")
    Optional<VisitSessionItemOutcome> findLockedByItemIdAndSessionId(
            @Param("itemId") Long itemId, @Param("sessionId") Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VisitSessionItemOutcome o where o.sessionId = :sessionId order by o.itemId")
    List<VisitSessionItemOutcome> findLockedBySessionIdOrderByItemId(@Param("sessionId") Long sessionId);

    List<VisitSessionItemOutcome> findBySessionIdOrderByPositionSnapshotAscItemIdAsc(Long sessionId);

    List<VisitSessionItemOutcome> findBySessionIdInOrderBySessionIdAscPositionSnapshotAscItemIdAsc(
            Collection<Long> sessionIds);
}
