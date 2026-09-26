package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Privacy-safe structured telemetry for a single public search autocomplete event.
 *
 * <p>Raw free-form user text is NOT stored. Only normalised/extracted structured fields
 * are persisted: BHK, location candidate, resolved locality/city, resolution method,
 * suggestion count, and selection outcome.
 *
 * <p>Personal identifiers are never captured. The optional {@code sessionHash} is an opaque,
 * one-way hash of an anonymous session key; it is used only to bound per-session evidence
 * contribution and to detect repeated identical events from the same session.
 */
@Entity
@Table(name = "search_query_event", indexes = {
    @Index(name = "idx_sqe_occurred_at", columnList = "occurred_at"),
    @Index(name = "idx_sqe_location_candidate", columnList = "location_candidate"),
    @Index(name = "idx_sqe_resolved_locality", columnList = "resolved_locality, resolved_city"),
})
public class SearchQueryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** Event category: SUGGESTION_SHOWN | SUGGESTION_SELECTED | SEARCH_EXECUTED */
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType = "SUGGESTION_SHOWN";

    /** City sent by the client in the autocomplete request. May be null/empty if none selected. */
    @Column(name = "city_input", length = 120)
    private String cityInput;

    /** Normalised location text extracted by the parser after stripping BHK/type/furnishing tokens. */
    @Column(name = "location_candidate", length = 120)
    private String locationCandidate;

    /** Locality matched to canonical data, if any. */
    @Column(name = "resolved_locality", length = 120)
    private String resolvedLocality;

    /** City matched to canonical data, if any. */
    @Column(name = "resolved_city", length = 120)
    private String resolvedCity;

    /**
     * How the location was resolved.
     * Values: EXACT, PREFIX, ALIAS, FUZZY, UNRESOLVED, STRUCTURED (BHK/type only, no location text).
     */
    @Column(name = "resolution_method", length = 30)
    private String resolutionMethod;

    /** pg_trgm similarity score (0.000–1.000) when resolution_method = FUZZY; null otherwise. */
    @Column(name = "fuzzy_confidence", precision = 4, scale = 3)
    private BigDecimal fuzzyConfidence;

    /** Normalised BHK key extracted by parser, e.g. "4BHK", "2RK". Null if absent in query. */
    @Column(name = "bhk_key", length = 10)
    private String bhkKey;

    /** Property type key if parsed from query, e.g. "FLAT". Null if absent. */
    @Column(name = "property_type_key", length = 40)
    private String propertyTypeKey;

    /** Furnishing key if parsed from query, e.g. "SEMI_FURNISHED". Null if absent. */
    @Column(name = "furnishing_key", length = 20)
    private String furnishingKey;

    /** Number of suggestions returned to this autocomplete request. */
    @Column(name = "suggestion_count")
    private Short suggestionCount;

    /** Type of the suggestion the user selected. Null if no selection. */
    @Column(name = "selected_type", length = 20)
    private String selectedType;

    /** 0-based rank of the selected suggestion in the list. Null if no selection. */
    @Column(name = "selected_rank")
    private Short selectedRank;

    /** True if the user executed a search (submitted "Show homes"). */
    @Column(name = "search_executed")
    private Boolean searchExecuted;

    /** Number of results returned after search execution. Null if search was not executed. */
    @Column(name = "result_count")
    private Integer resultCount;

    /** True when search was executed and returned zero results. */
    @Column(name = "zero_result")
    private Boolean zeroResult;

    /**
     * Opaque one-way hash of an anonymous session identifier.
     * Never a raw session ID, JWT, or any user-linkable key.
     * Used only to detect repeated identical events from the same session.
     */
    @Column(name = "session_hash", length = 64)
    private String sessionHash;

    public SearchQueryEvent() {}

    public Long getId() { return id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getCityInput() { return cityInput; }
    public void setCityInput(String cityInput) { this.cityInput = cityInput; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getLocationCandidate() { return locationCandidate; }
    public void setLocationCandidate(String locationCandidate) { this.locationCandidate = locationCandidate; }
    public String getResolvedLocality() { return resolvedLocality; }
    public void setResolvedLocality(String resolvedLocality) { this.resolvedLocality = resolvedLocality; }
    public String getResolvedCity() { return resolvedCity; }
    public void setResolvedCity(String resolvedCity) { this.resolvedCity = resolvedCity; }
    public String getResolutionMethod() { return resolutionMethod; }
    public void setResolutionMethod(String resolutionMethod) { this.resolutionMethod = resolutionMethod; }
    public BigDecimal getFuzzyConfidence() { return fuzzyConfidence; }
    public void setFuzzyConfidence(BigDecimal fuzzyConfidence) { this.fuzzyConfidence = fuzzyConfidence; }
    public String getBhkKey() { return bhkKey; }
    public void setBhkKey(String bhkKey) { this.bhkKey = bhkKey; }
    public String getPropertyTypeKey() { return propertyTypeKey; }
    public void setPropertyTypeKey(String propertyTypeKey) { this.propertyTypeKey = propertyTypeKey; }
    public String getFurnishingKey() { return furnishingKey; }
    public void setFurnishingKey(String furnishingKey) { this.furnishingKey = furnishingKey; }
    public Short getSuggestionCount() { return suggestionCount; }
    public void setSuggestionCount(Short suggestionCount) { this.suggestionCount = suggestionCount; }
    public String getSelectedType() { return selectedType; }
    public void setSelectedType(String selectedType) { this.selectedType = selectedType; }
    public Short getSelectedRank() { return selectedRank; }
    public void setSelectedRank(Short selectedRank) { this.selectedRank = selectedRank; }
    public Boolean getSearchExecuted() { return searchExecuted; }
    public void setSearchExecuted(Boolean searchExecuted) { this.searchExecuted = searchExecuted; }
    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }
    public Boolean getZeroResult() { return zeroResult; }
    public void setZeroResult(Boolean zeroResult) { this.zeroResult = zeroResult; }
    public String getSessionHash() { return sessionHash; }
    public void setSessionHash(String sessionHash) { this.sessionHash = sessionHash; }
}
