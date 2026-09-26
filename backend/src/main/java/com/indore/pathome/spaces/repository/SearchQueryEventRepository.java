package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SearchQueryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SearchQueryEventRepository extends JpaRepository<SearchQueryEvent, Long> {

    /**
     * Count how many events with the same session_hash and location_candidate occurred
     * within a recent window. Used to bound per-session evidence contribution.
     */
    @Query("SELECT COUNT(e) FROM SearchQueryEvent e " +
           "WHERE e.sessionHash = :sessionHash " +
           "AND e.locationCandidate = :locationCandidate " +
           "AND e.occurredAt >= :since")
    long countRecentDuplicatesForSession(
            @Param("sessionHash") String sessionHash,
            @Param("locationCandidate") String locationCandidate,
            @Param("since") LocalDateTime since);

    /**
     * Aggregate zero-result patterns: which location candidates appear repeatedly as
     * zero-result events that have also resolved to the same canonical locality.
     * Used by the candidate-generation batch job.
     */
    @Query(value =
        "SELECT e.location_candidate AS candidateTerm, " +
        "       e.resolved_locality  AS canonicalEntityValue, " +
        "       e.resolved_city      AS canonicalCity, " +
        "       COUNT(*)             AS evidenceCount, " +
        "       COUNT(DISTINCT COALESCE(e.session_hash, 'anon-' || e.id)) AS uniqueSessionCount, " +
        "       SUM(CASE WHEN e.selected_type IS NOT NULL OR e.event_type = 'SUGGESTION_SELECTED' THEN 1 ELSE 0 END) AS successCount, " +
        "       AVG(CAST(e.fuzzy_confidence AS FLOAT))                        AS avgConfidence " +
        "FROM search_query_event e " +
        "WHERE e.location_candidate IS NOT NULL " +
        "  AND e.resolved_locality  IS NOT NULL " +
        "  AND e.resolution_method  IN ('FUZZY', 'ALIAS') " +
        "  AND e.occurred_at        >= :since " +
        "GROUP BY e.location_candidate, e.resolved_locality, e.resolved_city " +
        "HAVING COUNT(*) >= :minEvidence",
        nativeQuery = true)
    List<CandidateAggregationRow> aggregateFuzzyResolutionCandidates(
            @Param("since") LocalDateTime since,
            @Param("minEvidence") int minEvidence);

    long countByZeroResultTrue();

    long countByResolutionMethod(String resolutionMethod);

    long countByEventType(String eventType);

    /** Projection for candidate aggregation queries. */
    interface CandidateAggregationRow {
        String getCandidateTerm();
        String getCanonicalEntityValue();
        String getCanonicalCity();
        long getEvidenceCount();
        long getUniqueSessionCount();
        long getSuccessCount();
        Double getAvgConfidence();
    }
}
