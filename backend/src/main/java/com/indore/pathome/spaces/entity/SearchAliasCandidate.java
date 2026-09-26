package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An aggregated alias candidate discovered through search telemetry patterns.
 *
 * <p>A candidate is generated when repeated search events show a consistent mapping
 * from a user-typed variant (e.g., "vijaynagr") to a canonical entity
 * (e.g., locality "Vijay Nagar" in "Indore"). Candidates must cross evidence thresholds
 * before they can be promoted to active {@link SearchAlias} records.
 *
 * <p>Canonical business data (locality master names, city names, listing statuses) is
 * never mutated by the learning system. Learning creates aliases; it does not rewrite facts.
 */
@Entity
@Table(name = "search_alias_candidate", indexes = {
    @Index(name = "idx_sac_candidate_term", columnList = "candidate_term"),
    @Index(name = "idx_sac_status", columnList = "status"),
    @Index(name = "idx_sac_entity", columnList = "canonical_entity_type, canonical_entity_value")
})
public class SearchAliasCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalised form of the term typed by the user (e.g., "vijaynagr"). */
    @Column(name = "candidate_term", nullable = false, length = 120)
    private String candidateTerm;

    /**
     * The kind of canonical entity this candidate resolves to.
     * Values: LOCALITY, CITY, PROPERTY_TYPE, FURNISHING.
     */
    @Column(name = "canonical_entity_type", nullable = false, length = 30)
    private String canonicalEntityType;

    /** The canonical entity's value (e.g., "Vijay Nagar"). */
    @Column(name = "canonical_entity_value", nullable = false, length = 120)
    private String canonicalEntityValue;

    /** City scope when {@code canonicalEntityType} is LOCALITY. Null otherwise. */
    @Column(name = "canonical_city", length = 120)
    private String canonicalCity;

    /** Total number of telemetry events contributing to this candidate. */
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount = 0;

    /** Total number of distinct privacy-safe sessions contributing to this candidate. */
    @Column(name = "unique_session_count", nullable = false)
    private int uniqueSessionCount = 0;

    /** Times the suggested mapping was accepted (user selected the canonical entity). */
    @Column(name = "successful_selection_count", nullable = false)
    private int successfulSelectionCount = 0;

    /** Times a competing mapping was selected, signalling possible ambiguity. */
    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount = 0;

    /**
     * Computed selection rate = successfulSelectionCount / evidenceCount.
     * Updated by the aggregation service, not by real-time requests.
     */
    @Column(name = "selection_rate", precision = 4, scale = 3)
    private BigDecimal selectionRate;

    /** Average fuzzy similarity from contributing fuzzy resolution events. */
    @Column(name = "average_confidence", precision = 4, scale = 3)
    private BigDecimal averageConfidence;

    /**
     * Lifecycle status.
     * <ul>
     *   <li>CANDIDATE  — under observation, not yet used in resolution</li>
     *   <li>APPROVED   — manually reviewed and approved for active resolution</li>
     *   <li>REJECTED   — manually rejected; will not be promoted</li>
     *   <li>AUTO_PROMOTED — automatically promoted by the aggregation service after thresholds</li>
     *   <li>DISABLED   — previously active but disabled (rollback)</li>
     * </ul>
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "CANDIDATE";

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    /** 'AUTO' or an admin identifier (never a secret). */
    @Column(name = "promoted_by", length = 120)
    private String promotedBy;

    /** JSON summary of the evidence thresholds that were satisfied at promotion time. */
    @Column(name = "promotion_evidence", columnDefinition = "TEXT")
    private String promotionEvidence;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public SearchAliasCandidate() {}

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCandidateTerm() { return candidateTerm; }
    public void setCandidateTerm(String candidateTerm) { this.candidateTerm = candidateTerm; }
    public String getCanonicalEntityType() { return canonicalEntityType; }
    public void setCanonicalEntityType(String canonicalEntityType) { this.canonicalEntityType = canonicalEntityType; }
    public String getCanonicalEntityValue() { return canonicalEntityValue; }
    public void setCanonicalEntityValue(String canonicalEntityValue) { this.canonicalEntityValue = canonicalEntityValue; }
    public String getCanonicalCity() { return canonicalCity; }
    public void setCanonicalCity(String canonicalCity) { this.canonicalCity = canonicalCity; }
    public int getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(int evidenceCount) { this.evidenceCount = evidenceCount; }
    public int getUniqueSessionCount() { return uniqueSessionCount; }
    public void setUniqueSessionCount(int uniqueSessionCount) { this.uniqueSessionCount = uniqueSessionCount; }
    public int getSuccessfulSelectionCount() { return successfulSelectionCount; }
    public void setSuccessfulSelectionCount(int successfulSelectionCount) { this.successfulSelectionCount = successfulSelectionCount; }
    public int getRejectionCount() { return rejectionCount; }
    public void setRejectionCount(int rejectionCount) { this.rejectionCount = rejectionCount; }
    public BigDecimal getSelectionRate() { return selectionRate; }
    public void setSelectionRate(BigDecimal selectionRate) { this.selectionRate = selectionRate; }
    public BigDecimal getAverageConfidence() { return averageConfidence; }
    public void setAverageConfidence(BigDecimal averageConfidence) { this.averageConfidence = averageConfidence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getPromotedAt() { return promotedAt; }
    public void setPromotedAt(LocalDateTime promotedAt) { this.promotedAt = promotedAt; }
    public String getPromotedBy() { return promotedBy; }
    public void setPromotedBy(String promotedBy) { this.promotedBy = promotedBy; }
    public String getPromotionEvidence() { return promotionEvidence; }
    public void setPromotionEvidence(String promotionEvidence) { this.promotionEvidence = promotionEvidence; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
