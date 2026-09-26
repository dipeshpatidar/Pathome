package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An active search alias that the resolution engine uses during autocomplete.
 *
 * <p>Promoted from a {@link SearchAliasCandidate} that has passed all evidence thresholds
 * and has been approved (manually or automatically). Every alias is fully auditable:
 * {@code sourceCandidateId} links back to the evidence record, and {@code disabledReason}
 * documents any rollback.
 *
 * <p>Search resolution priority: EXACT canonical match > PREFIX match > ALIAS resolution >
 * FUZZY trigram fallback. An alias therefore improves typo/abbreviation handling without
 * competing with authoritative exact or prefix hits.
 *
 * <p>Canonical business data is never mutated by aliases. An alias only maps a variant term
 * to an existing canonical value; it does not create new localities, cities, or property types.
 */
@Entity
@Table(name = "search_alias", indexes = {
    @Index(name = "idx_sa_alias_term", columnList = "alias_term"),
    @Index(name = "idx_sa_entity", columnList = "entity_type, entity_value")
})
public class SearchAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalised alias term to resolve (e.g., "vijaynagr"). */
    @Column(name = "alias_term", nullable = false, length = 120)
    private String aliasTerm;

    /** Entity type: LOCALITY, CITY, PROPERTY_TYPE, or FURNISHING. */
    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    /** Canonical entity value to resolve to (e.g., "Vijay Nagar"). */
    @Column(name = "entity_value", nullable = false, length = 120)
    private String entityValue;

    /** City scope when entity_type = LOCALITY. Ensures cross-city locality names remain distinct. */
    @Column(name = "entity_city", length = 120)
    private String entityCity;

    /** Confidence score (0.000–1.000) derived from candidate evidence. */
    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence = BigDecimal.ONE;

    /** Source candidate record for full audit traceability. */
    @Column(name = "source_candidate_id")
    private Long sourceCandidateId;

    /**
     * Alias lifecycle.
     * <ul>
     *   <li>ACTIVE — used by the resolver</li>
     *   <li>DISABLED — rolled back; preserved for audit</li>
     *   <li>SUPERSEDED — replaced by a higher-confidence alias</li>
     * </ul>
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "disabled_reason", length = 255)
    private String disabledReason;

    public SearchAlias() {}

    public Long getId() { return id; }
    public String getAliasTerm() { return aliasTerm; }
    public void setAliasTerm(String aliasTerm) { this.aliasTerm = aliasTerm; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityValue() { return entityValue; }
    public void setEntityValue(String entityValue) { this.entityValue = entityValue; }
    public String getEntityCity() { return entityCity; }
    public void setEntityCity(String entityCity) { this.entityCity = entityCity; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Long getSourceCandidateId() { return sourceCandidateId; }
    public void setSourceCandidateId(Long sourceCandidateId) { this.sourceCandidateId = sourceCandidateId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDisabledAt() { return disabledAt; }
    public void setDisabledAt(LocalDateTime disabledAt) { this.disabledAt = disabledAt; }
    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }
}
