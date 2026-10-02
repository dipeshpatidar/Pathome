package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitSessionRepository extends JpaRepository<VisitSession, Long> {
    Page<VisitSession> findByTenantId(Long tenantId, Pageable pageable);

    Page<VisitSession> findByStatusOrderByScheduledAtAsc(VisitSessionStatus status, Pageable pageable);

    Page<VisitSession> findByRepresentativeIdAndStatusOrderByScheduledAtAsc(
            Long representativeId, VisitSessionStatus status, Pageable pageable);
}
