package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitSessionItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface VisitSessionItemRepository extends JpaRepository<VisitSessionItem, Long> {
    @EntityGraph(attributePaths = "listing")
    List<VisitSessionItem> findBySessionIdOrderByPositionAsc(Long sessionId);

    @EntityGraph(attributePaths = "listing")
    List<VisitSessionItem> findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(Long sessionId);

    @EntityGraph(attributePaths = "listing")
    List<VisitSessionItem> findBySessionIdInAndRemovedAtIsNullOrderBySessionIdAscPositionAsc(Collection<Long> sessionIds);

    @EntityGraph(attributePaths = "listing")
    Optional<VisitSessionItem> findBySessionIdAndId(Long sessionId, Long id);

    Optional<VisitSessionItem> findBySessionIdAndListingId(Long sessionId, Long listingId);

    boolean existsBySessionIdAndListingId(Long sessionId, Long listingId);

    long countBySessionIdAndRemovedAtIsNull(Long sessionId);

    @Query("select coalesce(max(i.position), 0) from VisitSessionItem i where i.session.id = :sessionId")
    int findMaximumPosition(@Param("sessionId") Long sessionId);
}
