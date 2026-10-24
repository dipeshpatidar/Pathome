package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VisitRequestRoutingTaskRepository extends JpaRepository<VisitRequestRoutingTask, Long> {
    interface RoutingTaskRow {
        Long getId();
        Long getRequestId();
        Long getRequestVersion();
        String getRequestStatus();
        Long getTeamId();
        Long getCoordinatorUserId();
        Long getCityId();
        Long getLocalityCityId();
        String getCityDisplayName();
        Long getLocalityId();
        String getLocalityDisplayName();
        Boolean getScopeReady();
        Long getListingId();
        String getListingTitle();
        Long getTaskVersion();
        String getTaskState();
        String getLastErrorCode();
        Long getResultTeamId();
        String getResultTeamName();
        Long getResultRequestVersion();
        java.time.Instant getEnrolledAt();
        java.time.Instant getUpdatedAt();
    }

    String TASK_COLUMNS = """
            SELECT task.id AS id, request.id AS "requestId", request.version AS "requestVersion",
                   request.status AS "requestStatus", request.operating_team_id AS "teamId",
                   request.coordinator_user_id AS "coordinatorUserId",
                   request.supported_city_id AS "cityId",
                   request.operational_scope_ready AS "scopeReady",
                   route_city.display_name AS "cityDisplayName",
                   listing.canonical_locality_id AS "localityId",
                   locality.sector_name AS "localityDisplayName",
                   locality.supported_city_id AS "localityCityId",
                   listing.id AS "listingId", listing.title AS "listingTitle",
                   task.version AS "taskVersion", task.state AS "taskState",
                   task.last_error_code AS "lastErrorCode",
                   result_team.id AS "resultTeamId", result_team.display_name AS "resultTeamName",
                   task.result_request_version AS "resultRequestVersion",
                   task.enrolled_at AS "enrolledAt", task.updated_at AS "updatedAt"
            FROM visit_request_routing_tasks task
            JOIN property_visit_requests request ON request.id = task.request_id
            JOIN listings listing ON listing.id = request.listing_id
            LEFT JOIN supported_cities route_city ON route_city.id = request.supported_city_id
            LEFT JOIN localities locality ON locality.id = listing.canonical_locality_id
            LEFT JOIN operating_teams result_team ON result_team.id = task.result_team_id
            """;

    String NON_GLOBAL_VISIBILITY = """
              AND request.supported_city_id IN (:cityIds)
              AND request.operational_scope_ready = TRUE
              AND locality.supported_city_id = request.supported_city_id
            """;

    Optional<VisitRequestRoutingTask> findByRequestId(Long requestId);

    boolean existsByRequestId(Long requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from VisitRequestRoutingTask task where task.id = :id")
    Optional<VisitRequestRoutingTask> findLockedById(@Param("id") Long id);

    @Query(value = """
            SELECT task.id FROM visit_request_routing_tasks task
            WHERE task.state IN ('PENDING', 'RETRY')
              AND task.available_at <= clock_timestamp()
            ORDER BY task.available_at, task.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueTaskIds(@Param("limit") int limit);

    @Query(value = TASK_COLUMNS + """
            WHERE task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')
            """ + NON_GLOBAL_VISIBILITY + """
            ORDER BY CASE task.state WHEN 'NEEDS_REVIEW' THEN 0 WHEN 'RETRY' THEN 1 ELSE 2 END,
                     task.updated_at, task.id
            """,
            countQuery = """
            SELECT count(*) FROM visit_request_routing_tasks task
            JOIN property_visit_requests request ON request.id = task.request_id
            JOIN listings listing ON listing.id = request.listing_id
            LEFT JOIN localities locality ON locality.id = listing.canonical_locality_id
            WHERE task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')
            """ + NON_GLOBAL_VISIBILITY,
            nativeQuery = true)
    Page<RoutingTaskRow> findRoutingPageByCityIds(@Param("cityIds") List<Long> cityIds, Pageable pageable);

    @Query(value = TASK_COLUMNS + """
            WHERE task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')
            ORDER BY CASE task.state WHEN 'NEEDS_REVIEW' THEN 0 WHEN 'RETRY' THEN 1 ELSE 2 END,
                     task.updated_at, task.id
            """,
            countQuery = """
            SELECT count(*) FROM visit_request_routing_tasks task
            WHERE task.state IN ('PENDING', 'RETRY', 'NEEDS_REVIEW')
            """, nativeQuery = true)
    Page<RoutingTaskRow> findRoutingPage(Pageable pageable);

    @Query(value = TASK_COLUMNS + " WHERE task.id = :taskId", nativeQuery = true)
    Optional<RoutingTaskRow> findRoutingTaskRow(@Param("taskId") Long taskId);
}
