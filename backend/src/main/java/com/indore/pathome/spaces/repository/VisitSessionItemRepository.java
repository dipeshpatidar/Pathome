package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSessionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisitSessionItemRepository extends JpaRepository<VisitSessionItem, Long> {
    List<VisitSessionItem> findBySessionIdOrderByPositionAsc(Long sessionId);
}
