package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSchedulingDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitSchedulingDecisionRepository extends JpaRepository<VisitSchedulingDecision, Long> {
    long countBySessionId(Long sessionId);
}
