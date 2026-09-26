package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SearchAliasCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SearchAliasCandidateRepository extends JpaRepository<SearchAliasCandidate, Long> {

    Optional<SearchAliasCandidate> findByCandidateTermAndCanonicalEntityTypeAndCanonicalEntityValueAndCanonicalCity(
            String candidateTerm, String canonicalEntityType, String canonicalEntityValue, String canonicalCity);

    /**
     * Find candidates eligible for auto-promotion.
     * Thresholds:
     *   - evidence_count >= minEvidence
     *   - selection_rate >= minSelectionRate
     *   - rejection_count = 0 (no ambiguity)
     *   - status = CANDIDATE (not yet promoted or rejected)
     */
    @Query("SELECT c FROM SearchAliasCandidate c " +
           "WHERE c.status = 'CANDIDATE' " +
           "  AND c.evidenceCount >= :minEvidence " +
           "  AND c.selectionRate >= :minSelectionRate " +
           "  AND c.rejectionCount = 0")
    List<SearchAliasCandidate> findEligibleForAutoPromotion(
            @Param("minEvidence") int minEvidence,
            @Param("minSelectionRate") double minSelectionRate);

    @Query("SELECT c FROM SearchAliasCandidate c " +
           "WHERE c.status = 'CANDIDATE' " +
           "  AND c.evidenceCount >= :minEvidence " +
           "  AND c.uniqueSessionCount >= :minUniqueSessions " +
           "  AND c.selectionRate >= :minSelectionRate " +
           "  AND c.rejectionCount = 0")
    List<SearchAliasCandidate> findEligibleForReview(
            @Param("minEvidence") int minEvidence,
            @Param("minUniqueSessions") int minUniqueSessions,
            @Param("minSelectionRate") double minSelectionRate);

    /**
     * Find candidates with competing canonical targets for the same candidate_term.
     * These MUST NOT be auto-promoted (ambiguous mapping).
     */
    @Query("SELECT c.candidateTerm FROM SearchAliasCandidate c " +
           "WHERE c.canonicalEntityType = :entityType " +
           "GROUP BY c.candidateTerm, c.canonicalEntityType " +
           "HAVING COUNT(DISTINCT c.canonicalEntityValue) > 1")
    List<String> findAmbiguousCandidateTerms(@Param("entityType") String entityType);

    List<SearchAliasCandidate> findByStatus(String status);

    long countByStatus(String status);
}
