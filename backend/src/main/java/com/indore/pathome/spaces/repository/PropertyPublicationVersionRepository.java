package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyPublicationVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyPublicationVersionRepository extends JpaRepository<PropertyPublicationVersion, Long> {
    @Query("select coalesce(max(p.publicationVersion), 0) from PropertyPublicationVersion p where p.listingId = :listingId")
    long currentVersion(@Param("listingId") Long listingId);

    @Query("select coalesce(max(p.id), 0) from PropertyPublicationVersion p where p.listingId = :listingId")
    long latestRowId(@Param("listingId") Long listingId);

    Optional<PropertyPublicationVersion> findFirstByListingIdOrderByPublicationVersionDesc(Long listingId);

    Optional<PropertyPublicationVersion> findByListingIdAndPublicationVersion(Long listingId, Long publicationVersion);

    Optional<PropertyPublicationVersion> findByIdAndListingId(Long id, Long listingId);

    List<PropertyPublicationVersion> findByListingIdOrderByPublicationVersionDescIdDesc(
            Long listingId, Pageable pageable);

    @Query("select p from PropertyPublicationVersion p where p.listingId = :listingId " +
            "and p.id <= :cutoff order by p.publishedAt desc, p.id desc")
    List<PropertyPublicationVersion> findActivityCohort(@Param("listingId") Long listingId,
                                                        @Param("cutoff") long cutoff,
                                                        Pageable pageable);

    @Query("select p from PropertyPublicationVersion p where p.listingId = :listingId " +
            "and p.id <= :cutoff and (p.publishedAt < :timestamp or " +
            "(p.publishedAt = :timestamp and :anchorRank = 0 and p.id < :anchorRowId)) " +
            "order by p.publishedAt desc, p.id desc")
    List<PropertyPublicationVersion> findActivityAfterAnchor(
            @Param("listingId") Long listingId, @Param("cutoff") long cutoff,
            @Param("timestamp") java.time.Instant timestamp,
            @Param("anchorRank") int anchorRank, @Param("anchorRowId") long anchorRowId,
            Pageable pageable);
}

