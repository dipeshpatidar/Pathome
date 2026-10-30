package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyRevisionEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface PropertyRevisionEventRepository extends JpaRepository<PropertyRevisionEvent, Long> {
    List<PropertyRevisionEvent> findByListingIdOrderByOccurredAtDescIdDesc(Long listingId, Pageable pageable);

    @Query("select coalesce(max(e.id), 0) from PropertyRevisionEvent e where e.listingId = :listingId")
    long latestRowId(@Param("listingId") Long listingId);

    Optional<PropertyRevisionEvent> findByIdAndListingId(Long id, Long listingId);

    @Query("select e from PropertyRevisionEvent e where e.listingId = :listingId " +
            "and e.id <= :cutoff order by e.occurredAt desc, e.id desc")
    List<PropertyRevisionEvent> findActivityCohort(@Param("listingId") Long listingId,
                                                    @Param("cutoff") long cutoff,
                                                    Pageable pageable);

    @Query("select e from PropertyRevisionEvent e where e.listingId = :listingId " +
            "and e.id <= :cutoff and (e.occurredAt < :timestamp or " +
            "(e.occurredAt = :timestamp and (:anchorRank = 0 or e.id < :anchorRowId))) " +
            "order by e.occurredAt desc, e.id desc")
    List<PropertyRevisionEvent> findActivityAfterAnchor(
            @Param("listingId") Long listingId, @Param("cutoff") long cutoff,
            @Param("timestamp") java.time.Instant timestamp,
            @Param("anchorRank") int anchorRank, @Param("anchorRowId") long anchorRowId,
            Pageable pageable);
}

