package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface PropertyVisitRequestRepository extends JpaRepository<PropertyVisitRequest, Long> {
    Optional<PropertyVisitRequest> findByTenantIdAndListingId(Long tenantId, Long listingId);

    @Query("select r.session.id from PropertyVisitRequest r where r.id = :requestId")
    Optional<Long> findSessionIdByRequestId(@Param("requestId") Long requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PropertyVisitRequest r where r.id = :id")
    Optional<PropertyVisitRequest> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PropertyVisitRequest r where r.id = :id and r.tenant.id = :tenantId")
    Optional<PropertyVisitRequest> findLockedByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<PropertyVisitRequest> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PropertyVisitRequest r where r.session.id = :sessionId order by r.id")
    List<PropertyVisitRequest> findLockedBySessionIdOrderByIdAsc(@Param("sessionId") Long sessionId);

    interface OperationsQueueRow {
        Long getId();
        String getStatus();
        Long getVersion();
        Long getSessionId();
        java.time.LocalDateTime getCreatedAt();
        Long getListingId();
        String getListingTitle();
        String getCity();
        String getSector();
    }

    @Query(value = """
            SELECT r.id AS id, r.status AS status, r.version AS version, r.session_id AS "sessionId",
                   r.created_at AS "createdAt",
                   l.id AS "listingId", l.title AS "listingTitle",
                   COALESCE(loc.city, l.city) AS city, l.sector AS sector
            FROM property_visit_requests r
            JOIN listings l ON l.id = r.listing_id
            LEFT JOIN localities loc ON loc.id = l.canonical_locality_id
            LEFT JOIN visit_sessions s ON s.id = r.session_id
            WHERE (:status IS NULL OR r.status = :status)
              AND (:city IS NULL OR lower(btrim(COALESCE(loc.city, l.city))) = lower(btrim(:city)))
              AND """ + OperationalVisibilitySql.REQUEST_VISIBLE + """
            ORDER BY CASE r.status
                         WHEN 'RECEIVED' THEN 0
                         WHEN 'COORDINATING' THEN 1
                         ELSE 2
                     END,
                     r.created_at DESC, r.id DESC
            """,
            countQuery = """
            SELECT count(*)
            FROM property_visit_requests r
            LEFT JOIN visit_sessions s ON s.id = r.session_id
            JOIN listings l ON l.id = r.listing_id
            LEFT JOIN localities loc ON loc.id = l.canonical_locality_id
            WHERE (:status IS NULL OR r.status = :status)
              AND (:city IS NULL OR lower(btrim(COALESCE(loc.city, l.city))) = lower(btrim(:city)))
              AND """ + OperationalVisibilitySql.REQUEST_VISIBLE,
            nativeQuery = true)
    Page<OperationsQueueRow> findVisibleOperationsQueue(@Param("userId") Long userId,
                                                         @Param("status") String status,
                                                         @Param("city") String city,
                                                         Pageable pageable);

    @EntityGraph(attributePaths = "listing")
    Page<PropertyVisitRequest> findByTenantId(Long tenantId, Pageable pageable);
}
