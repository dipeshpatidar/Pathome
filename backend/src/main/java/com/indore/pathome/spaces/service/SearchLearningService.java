package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.SearchFeedbackRequest;
import com.indore.pathome.spaces.entity.SearchAlias;
import com.indore.pathome.spaces.entity.SearchAliasCandidate;
import com.indore.pathome.spaces.entity.SearchQueryEvent;
import com.indore.pathome.spaces.repository.SearchAliasCandidateRepository;
import com.indore.pathome.spaces.repository.SearchAliasRepository;
import com.indore.pathome.spaces.repository.SearchQueryEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Governed search feedback-learning service.
 *
 * <h2>Learning pipeline</h2>
 * <pre>
 * autocomplete request
 *   → captureEvent() [ASYNC, non-blocking]
 *     → search_query_event row (structured telemetry only; no raw text)
 *
 * suggestion click / explicit selection
 *   → captureFeedback() [ASYNC, non-blocking]
 *     → search_query_event row (event_type = SUGGESTION_SELECTED)
 *
 * aggregateCandidates() [scheduled: every 15 min]
 *   → read events with resolution_method IN (FUZZY, ALIAS)
 *   → upsert/update search_alias_candidate rows with unique_session_count and true selection_rate
 *
 * reviewEligibleCandidates() [scheduled: every hour]
 *   → Phase-1 Policy: auto-promotion is DISABLED (AUTO_PROMOTION_ENABLED = false)
 *   → marks qualifying candidates as ELIGIBLE_FOR_REVIEW
 *   → no active aliases are created automatically
 *
 * approveCandidate() [Admin Controlled]
 *   → candidate -> APPROVED
 *   → creates/updates search_alias (ACTIVE)
 *   → immediately updates in-memory cache without restart
 *
 * disableAlias() [Admin Controlled]
 *   → search_alias -> DISABLED
 *   → immediately evicts entry from in-memory cache without restart
 * </pre>
 *
 * <h2>Safety guarantees</h2>
 * <ul>
 *   <li>Telemetry write failures never break autocomplete (fire-and-forget async).</li>
 *   <li>One session cannot contribute more than {@value #MAX_EVENTS_PER_SESSION_PER_CANDIDATE}
 *       events for the same candidate within a {@value #SESSION_DEDUP_WINDOW_HOURS}-hour window.</li>
 *   <li>Independent sessions count distinctly (uniqueSessionCount >= {@value #MIN_UNIQUE_SESSIONS_FOR_PROMOTION}).</li>
 *   <li>Candidates with competing canonical targets are never promoted (ambiguity guard).</li>
 *   <li>Canonical business data (localities, city names, listings) is never mutated.</li>
 *   <li>Every alias can be disabled at any time with immediate cache eviction.</li>
 * </ul>
 */
@Service
public class SearchLearningService {

    private static final Logger log = LoggerFactory.getLogger(SearchLearningService.class);

    /** Minimum evidence events required before review/promotion is considered. */
    static final int MIN_EVIDENCE_FOR_PROMOTION = 10;

    /** Minimum independent sessions required before review/promotion is considered. */
    static final int MIN_UNIQUE_SESSIONS_FOR_PROMOTION = 3;

    /** Minimum selection rate (successful selections / evidence events) for promotion. */
    static final double MIN_SELECTION_RATE_FOR_PROMOTION = 0.70;

    /**
     * Phase-1 Governance Policy:
     * Uncontrolled auto-promotion is strictly DISABLED.
     * Candidates can automatically reach ELIGIBLE_FOR_REVIEW, but activating aliases
     * and modifying runtime search behavior requires controlled administrative approval.
     */
    public static final boolean AUTO_PROMOTION_ENABLED = false;

    /**
     * Maximum number of events a single session can contribute for the same candidate
     * within {@link #SESSION_DEDUP_WINDOW_HOURS}. Prevents session-level poisoning.
     */
    static final int MAX_EVENTS_PER_SESSION_PER_CANDIDATE = 3;

    /** Window (hours) for per-session deduplication. */
    static final int SESSION_DEDUP_WINDOW_HOURS = 1;

    /**
     * How far back (days) the aggregation query looks for new evidence.
     * A rolling window prevents stale signals from accumulating indefinitely.
     */
    private static final int AGGREGATION_LOOKBACK_DAYS = 30;

    /** L1 in-memory alias cache: normalised alias term → resolved locality name. */
    private final ConcurrentMap<String, String> localityAliasCache = new ConcurrentHashMap<>();

    private final SearchQueryEventRepository eventRepository;
    private final SearchAliasCandidateRepository candidateRepository;
    private final SearchAliasRepository aliasRepository;

    @Autowired
    public SearchLearningService(
            SearchQueryEventRepository eventRepository,
            SearchAliasCandidateRepository candidateRepository,
            SearchAliasRepository aliasRepository) {
        this.eventRepository = eventRepository;
        this.candidateRepository = candidateRepository;
        this.aliasRepository = aliasRepository;
    }

    // ─── Public Telemetry API ──────────────────────────────────────────────────

    /**
     * Persist a search telemetry event asynchronously.
     * Fire-and-forget: exceptions are caught and logged; caller is never blocked or faulted.
     */
    @Async
    public void captureEvent(SearchQueryEvent event) {
        try {
            if (event.getEventType() == null || event.getEventType().isBlank()) {
                event.setEventType("SUGGESTION_SHOWN");
            }
            // Per-session deduplication: cap contribution per candidate per session window
            if (event.getSessionHash() != null && event.getLocationCandidate() != null
                    && !event.getLocationCandidate().isBlank()) {
                LocalDateTime window = LocalDateTime.now().minusHours(SESSION_DEDUP_WINDOW_HOURS);
                long recent = eventRepository.countRecentDuplicatesForSession(
                        event.getSessionHash(), event.getLocationCandidate(), window);
                if (recent >= MAX_EVENTS_PER_SESSION_PER_CANDIDATE) {
                    log.debug("Search telemetry: skipping duplicate session event for candidate '{}' ({})",
                            event.getLocationCandidate(), event.getSessionHash());
                    return;
                }
            }
            eventRepository.save(event);
        } catch (Exception ex) {
            log.warn("Search telemetry capture failed (non-critical): {}", ex.getMessage());
        }
    }

    /**
     * Persist suggestion selection or search execution feedback asynchronously.
     * Hashes raw ephemeral session UUID into a privacy-safe one-way hash before persisting.
     */
    @Async
    public void captureFeedback(SearchFeedbackRequest request) {
        if (request == null) return;
        try {
            SearchQueryEvent event = new SearchQueryEvent();
            event.setEventType(request.eventType() != null && !request.eventType().isBlank()
                    ? request.eventType().trim().toUpperCase(Locale.ROOT)
                    : "SUGGESTION_SELECTED");
            event.setCityInput(request.canonicalCity() != null ? request.canonicalCity().trim() : null);
            event.setLocationCandidate(request.candidateTerm() != null
                    ? request.candidateTerm().trim().toLowerCase(Locale.ROOT)
                    : null);
            event.setResolvedLocality(request.canonicalLocality() != null ? request.canonicalLocality().trim() : null);
            event.setResolvedCity(request.canonicalCity() != null ? request.canonicalCity().trim() : null);
            event.setResolutionMethod(request.resolutionMethod() != null
                    ? request.resolutionMethod().trim().toUpperCase(Locale.ROOT)
                    : null);
            event.setFuzzyConfidence(request.fuzzyConfidence());
            event.setSelectedType(request.selectedType() != null ? request.selectedType().trim().toUpperCase(Locale.ROOT) : null);
            event.setSelectedRank(request.selectedRank());
            event.setBhkKey(request.bhkKey());
            event.setPropertyTypeKey(request.propertyTypeKey());
            event.setFurnishingKey(request.furnishingKey());
            event.setSearchExecuted(request.searchExecuted());
            event.setResultCount(request.resultCount());
            event.setZeroResult(request.zeroResult());

            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                event.setSessionHash(hashSessionId(request.sessionId().trim()));
            }

            captureEvent(event);
        } catch (Exception ex) {
            log.warn("Search feedback capture failed (non-critical): {}", ex.getMessage());
        }
    }

    // ─── Cache Resolution & Management ──────────────────────────────────────────

    /**
     * Resolve a normalised query term through the active alias cache.
     * Priority: city-scoped entry first, then city-agnostic fallback. O(1) in-memory lookup.
     */
    public String resolveLocalityAlias(String normalisedTerm, String cityKey) {
        if (normalisedTerm == null || normalisedTerm.isBlank()) return null;
        String scopedKey = cacheKey(normalisedTerm, cityKey);
        String agnosticKey = cacheKey(normalisedTerm, "");
        String hit = localityAliasCache.get(scopedKey);
        return hit != null ? hit : localityAliasCache.get(agnosticKey);
    }

    /**
     * Load all ACTIVE locality aliases into the in-memory cache.
     * Called at startup and after administrative changes.
     */
    @Transactional(readOnly = true)
    public void refreshAliasCache() {
        try {
            localityAliasCache.clear();
            List<SearchAlias> active = aliasRepository.findActiveLocalityAliasesForCity("");
            for (SearchAlias alias : active) {
                String key = cacheKey(alias.getAliasTerm(),
                        alias.getEntityCity() == null ? "" : alias.getEntityCity().toLowerCase(Locale.ROOT).strip());
                localityAliasCache.put(key, alias.getEntityValue());
            }
            log.info("Search alias cache refreshed: {} active locality aliases loaded.", active.size());
        } catch (Exception ex) {
            log.warn("Failed to refresh alias cache (non-critical): {}", ex.getMessage());
        }
    }

    /**
     * Immediately evict a specific alias from the in-memory cache without restart.
     */
    public void evictFromCache(String aliasTerm, String cityKey) {
        if (aliasTerm == null || aliasTerm.isBlank()) return;
        localityAliasCache.remove(cacheKey(aliasTerm, cityKey));
        localityAliasCache.remove(cacheKey(aliasTerm, ""));
    }

    // ─── Scheduled batch jobs ────────────────────────────────────────────────────

    /**
     * Scheduled aggregation job: runs every 15 minutes.
     * Computes candidate evidence, unique session count, and real selection rate.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    @Transactional
    public void aggregateCandidates() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(AGGREGATION_LOOKBACK_DAYS);
            List<SearchQueryEventRepository.CandidateAggregationRow> rows =
                    eventRepository.aggregateFuzzyResolutionCandidates(since, 2);

            Set<String> ambiguousTerms = Set.copyOf(
                    candidateRepository.findAmbiguousCandidateTerms("LOCALITY"));

            int created = 0, updated = 0;
            for (SearchQueryEventRepository.CandidateAggregationRow row : rows) {
                if (row.getCandidateTerm() == null || row.getCanonicalEntityValue() == null) continue;
                String term = row.getCandidateTerm();
                String canonical = row.getCanonicalEntityValue();
                String city = row.getCanonicalCity();
                String safeCityKey = city == null ? "" : city;

                if (ambiguousTerms.contains(term)) {
                    log.debug("Candidate '{}' skipped: ambiguous across multiple canonical targets.", term);
                    continue;
                }

                Optional<SearchAliasCandidate> existing =
                        candidateRepository.findByCandidateTermAndCanonicalEntityTypeAndCanonicalEntityValueAndCanonicalCity(
                                term, "LOCALITY", canonical, safeCityKey);

                if (existing.isPresent()) {
                    SearchAliasCandidate c = existing.get();
                    if (!"CANDIDATE".equals(c.getStatus()) && !"ELIGIBLE_FOR_REVIEW".equals(c.getStatus())) {
                        continue;
                    }
                    c.setEvidenceCount((int) row.getEvidenceCount());
                    c.setUniqueSessionCount((int) row.getUniqueSessionCount());
                    c.setSuccessfulSelectionCount((int) row.getSuccessCount());
                    if (row.getEvidenceCount() > 0) {
                        c.setSelectionRate(BigDecimal.valueOf(
                                (double) row.getSuccessCount() / row.getEvidenceCount())
                                .setScale(3, RoundingMode.HALF_UP));
                    }
                    if (row.getAvgConfidence() != null) {
                        c.setAverageConfidence(BigDecimal.valueOf(row.getAvgConfidence())
                                .setScale(3, RoundingMode.HALF_UP));
                    }
                    c.setLastSeenAt(LocalDateTime.now());
                    candidateRepository.save(c);
                    updated++;
                } else {
                    SearchAliasCandidate c = new SearchAliasCandidate();
                    c.setCandidateTerm(term);
                    c.setCanonicalEntityType("LOCALITY");
                    c.setCanonicalEntityValue(canonical);
                    c.setCanonicalCity(safeCityKey);
                    c.setEvidenceCount((int) row.getEvidenceCount());
                    c.setUniqueSessionCount((int) row.getUniqueSessionCount());
                    c.setSuccessfulSelectionCount((int) row.getSuccessCount());
                    if (row.getEvidenceCount() > 0) {
                        c.setSelectionRate(BigDecimal.valueOf(
                                (double) row.getSuccessCount() / row.getEvidenceCount())
                                .setScale(3, RoundingMode.HALF_UP));
                    }
                    if (row.getAvgConfidence() != null) {
                        c.setAverageConfidence(BigDecimal.valueOf(row.getAvgConfidence())
                                .setScale(3, RoundingMode.HALF_UP));
                    }
                    try {
                        candidateRepository.save(c);
                        created++;
                    } catch (DataIntegrityViolationException dup) {
                        log.debug("Candidate '{}' → '{}' already exists (race); skipping.", term, canonical);
                    }
                }
            }
            if (created > 0 || updated > 0) {
                log.info("Search candidate aggregation: {} created, {} updated.", created, updated);
            }
        } catch (Exception ex) {
            log.warn("Search candidate aggregation failed (non-critical): {}", ex.getMessage());
        }
    }

    /**
     * Scheduled review job: runs every hour.
     * In Phase-1: uncontrolled AUTO-PROMOTION is disabled.
     * Eligible candidates are marked as ELIGIBLE_FOR_REVIEW for human/admin inspection.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void promoteEligibleCandidates() {
        if (!AUTO_PROMOTION_ENABLED) {
            markEligibleCandidatesForReview();
            return;
        }

        // Future activation code if AUTO_PROMOTION_ENABLED is ever set to true in a future phase
        try {
            Set<String> ambiguousTerms = Set.copyOf(
                    candidateRepository.findAmbiguousCandidateTerms("LOCALITY"));

            List<SearchAliasCandidate> eligible = candidateRepository.findEligibleForReview(
                    MIN_EVIDENCE_FOR_PROMOTION, MIN_UNIQUE_SESSIONS_FOR_PROMOTION, MIN_SELECTION_RATE_FOR_PROMOTION);

            int promoted = 0;
            for (SearchAliasCandidate candidate : eligible) {
                if (ambiguousTerms.contains(candidate.getCandidateTerm())) {
                    continue;
                }
                String cityKey = candidate.getCanonicalCity() == null ? "" : candidate.getCanonicalCity();
                Optional<SearchAlias> existing = aliasRepository.findByAliasTermAndEntityTypeAndEntityCity(
                        candidate.getCandidateTerm(), candidate.getCanonicalEntityType(), cityKey);
                if (existing.isPresent()) {
                    candidate.setStatus("AUTO_PROMOTED");
                    candidate.setPromotedAt(LocalDateTime.now());
                    candidate.setPromotedBy("AUTO");
                    candidateRepository.save(candidate);
                    continue;
                }

                SearchAlias alias = new SearchAlias();
                alias.setAliasTerm(candidate.getCandidateTerm());
                alias.setEntityType(candidate.getCanonicalEntityType());
                alias.setEntityValue(candidate.getCanonicalEntityValue());
                alias.setEntityCity(cityKey.isBlank() ? null : cityKey);
                BigDecimal conf = candidate.getAverageConfidence() != null
                        ? candidate.getAverageConfidence().min(BigDecimal.ONE)
                        : BigDecimal.valueOf(0.85).setScale(3, RoundingMode.HALF_UP);
                alias.setConfidence(conf);
                alias.setSourceCandidateId(candidate.getId());
                alias.setStatus("ACTIVE");

                aliasRepository.save(alias);
                candidate.setStatus("AUTO_PROMOTED");
                candidate.setPromotedAt(LocalDateTime.now());
                candidate.setPromotedBy("AUTO");
                candidateRepository.save(candidate);
                putCacheEntry(alias.getAliasTerm(), alias.getEntityCity(), alias.getEntityValue());
                promoted++;
            }
            if (promoted > 0) {
                log.info("Search alias promotion: {} aliases promoted.", promoted);
            }
        } catch (Exception ex) {
            log.warn("Search alias promotion failed (non-critical): {}", ex.getMessage());
        }
    }

    /**
     * Mark qualifying candidates as ELIGIBLE_FOR_REVIEW without activating any aliases.
     */
    @Transactional
    public int markEligibleCandidatesForReview() {
        try {
            Set<String> ambiguousTerms = Set.copyOf(
                    candidateRepository.findAmbiguousCandidateTerms("LOCALITY"));

            List<SearchAliasCandidate> eligible = candidateRepository.findEligibleForReview(
                    MIN_EVIDENCE_FOR_PROMOTION, MIN_UNIQUE_SESSIONS_FOR_PROMOTION, MIN_SELECTION_RATE_FOR_PROMOTION);

            int marked = 0;
            for (SearchAliasCandidate candidate : eligible) {
                if (ambiguousTerms.contains(candidate.getCandidateTerm())) {
                    log.info("Candidate {} skipped review eligibility: ambiguous term '{}'.",
                            candidate.getId(), candidate.getCandidateTerm());
                    continue;
                }
                candidate.setStatus("ELIGIBLE_FOR_REVIEW");
                candidateRepository.save(candidate);
                marked++;
            }
            if (marked > 0) {
                log.info("Search alias governance: {} candidates marked ELIGIBLE_FOR_REVIEW (auto-promotion disabled).", marked);
            }
            return marked;
        } catch (Exception ex) {
            log.warn("Marking review-eligible candidates failed (non-critical): {}", ex.getMessage());
            return 0;
        }
    }

    // ─── Controlled Administrative Operations ───────────────────────────────────

    /**
     * Approve an alias candidate: creates/updates the active SearchAlias and immediately
     * updates the runtime in-memory cache without restart.
     */
    @Transactional
    public SearchAlias approveCandidate(Long candidateId, String approvedBy) {
        SearchAliasCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        if ("APPROVED".equals(candidate.getStatus())) {
            String cityKey = candidate.getCanonicalCity() == null ? "" : candidate.getCanonicalCity();
            return aliasRepository.findByAliasTermAndEntityTypeAndEntityCity(
                    candidate.getCandidateTerm(), candidate.getCanonicalEntityType(), cityKey).orElse(null);
        }
        if ("REJECTED".equals(candidate.getStatus())) {
            throw new IllegalStateException("Candidate has already been rejected: " + candidateId);
        }

        String cityKey = candidate.getCanonicalCity() == null ? "" : candidate.getCanonicalCity();
        Optional<SearchAlias> existing = aliasRepository.findByAliasTermAndEntityTypeAndEntityCity(
                candidate.getCandidateTerm(), candidate.getCanonicalEntityType(), cityKey);

        SearchAlias alias;
        if (existing.isPresent()) {
            alias = existing.get();
            alias.setStatus("ACTIVE");
            alias.setDisabledAt(null);
            alias.setDisabledReason(null);
            alias.setEntityValue(candidate.getCanonicalEntityValue());
            if (candidate.getAverageConfidence() != null) {
                alias.setConfidence(candidate.getAverageConfidence().min(BigDecimal.ONE));
            }
        } else {
            alias = new SearchAlias();
            alias.setAliasTerm(candidate.getCandidateTerm());
            alias.setEntityType(candidate.getCanonicalEntityType());
            alias.setEntityValue(candidate.getCanonicalEntityValue());
            alias.setEntityCity(cityKey.isBlank() ? null : cityKey);
            BigDecimal conf = candidate.getAverageConfidence() != null
                    ? candidate.getAverageConfidence().min(BigDecimal.ONE)
                    : BigDecimal.valueOf(0.95).setScale(3, RoundingMode.HALF_UP);
            alias.setConfidence(conf);
            alias.setSourceCandidateId(candidate.getId());
            alias.setStatus("ACTIVE");
        }
        SearchAlias savedAlias = aliasRepository.save(alias);

        candidate.setStatus("APPROVED");
        candidate.setPromotedAt(LocalDateTime.now());
        candidate.setPromotedBy(approvedBy != null && !approvedBy.isBlank() ? approvedBy : "ADMIN");
        candidate.setPromotionEvidence(String.format(
                "{\"evidenceCount\":%d,\"uniqueSessionCount\":%d,\"selectionRate\":%s,\"approvedBy\":\"%s\"}",
                candidate.getEvidenceCount(),
                candidate.getUniqueSessionCount(),
                candidate.getSelectionRate(),
                candidate.getPromotedBy()));
        candidateRepository.save(candidate);

        // Immediate cache update
        putCacheEntry(savedAlias.getAliasTerm(), savedAlias.getEntityCity(), savedAlias.getEntityValue());
        log.info("Alias approved: candidateId={} term='{}' -> '{}' (city={}, approvedBy={})",
                candidateId, savedAlias.getAliasTerm(), savedAlias.getEntityValue(),
                savedAlias.getEntityCity(), candidate.getPromotedBy());
        return savedAlias;
    }

    /**
     * Reject a candidate: marks status as REJECTED so it cannot be promoted.
     */
    @Transactional
    public SearchAliasCandidate rejectCandidate(Long candidateId, String rejectedBy, String reason) {
        SearchAliasCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        candidate.setStatus("REJECTED");
        candidate.setPromotedAt(LocalDateTime.now());
        candidate.setPromotedBy(rejectedBy != null && !rejectedBy.isBlank() ? rejectedBy : "ADMIN");
        candidate.setPromotionEvidence(String.format("{\"rejectionReason\":\"%s\"}", reason != null ? reason : ""));
        log.info("Candidate rejected: candidateId={} term='{}' rejectedBy='{}' reason='{}'",
                candidateId, candidate.getCandidateTerm(), candidate.getPromotedBy(), reason);
        return candidateRepository.save(candidate);
    }

    /**
     * Disable an active alias: marks status as DISABLED and immediately evicts it
     * from the in-memory cache without restart.
     */
    @Transactional
    public SearchAlias disableAlias(Long aliasId, String disabledReason) {
        SearchAlias alias = aliasRepository.findById(aliasId)
                .orElseThrow(() -> new IllegalArgumentException("Alias not found: " + aliasId));

        alias.setStatus("DISABLED");
        alias.setDisabledAt(LocalDateTime.now());
        alias.setDisabledReason(disabledReason != null ? disabledReason : "Administrative disable");
        SearchAlias saved = aliasRepository.save(alias);

        // Immediate cache eviction
        evictFromCache(alias.getAliasTerm(), alias.getEntityCity());

        if (alias.getSourceCandidateId() != null) {
            candidateRepository.findById(alias.getSourceCandidateId()).ifPresent(c -> {
                c.setStatus("DISABLED");
                candidateRepository.save(c);
            });
        }
        log.info("Alias disabled: aliasId={} term='{}' city='{}' reason='{}'",
                aliasId, alias.getAliasTerm(), alias.getEntityCity(), disabledReason);
        return saved;
    }

    // ─── Metrics / Observability ────────────────────────────────────────────────

    public record SearchLearningMetrics(
            long totalSearchEvents,
            long suggestionShownEvents,
            long suggestionSelectedEvents,
            long zeroResultSearches,
            long totalCandidates,
            long reviewEligibleCandidates,
            long approvedCandidates,
            long rejectedCandidates,
            long activeAliases,
            long disabledAliases,
            long aliasResolutionsCount,
            long fuzzyResolutionsCount
    ) {}

    public SearchLearningMetrics getMetrics() {
        return new SearchLearningMetrics(
                eventRepository.count(),
                eventRepository.countByEventType("SUGGESTION_SHOWN"),
                eventRepository.countByEventType("SUGGESTION_SELECTED"),
                eventRepository.countByZeroResultTrue(),
                candidateRepository.count(),
                candidateRepository.countByStatus("ELIGIBLE_FOR_REVIEW"),
                candidateRepository.countByStatus("APPROVED"),
                candidateRepository.countByStatus("REJECTED"),
                aliasRepository.countByStatus("ACTIVE"),
                aliasRepository.countByStatus("DISABLED"),
                eventRepository.countByResolutionMethod("ALIAS"),
                eventRepository.countByResolutionMethod("FUZZY")
        );
    }

    // ─── Helper utilities ────────────────────────────────────────────────────────

    /**
     * Build an opaque one-way hash of a raw session identifier.
     * The result is safe to store; it cannot be reversed to the original session key.
     */
    public static String hashSessionId(String rawSessionId) {
        if (rawSessionId == null || rawSessionId.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawSessionId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 43);
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private static String cacheKey(String aliasTerm, String cityKey) {
        String safeCity = cityKey == null ? "" : cityKey.toLowerCase(Locale.ROOT).strip();
        return aliasTerm.toLowerCase(Locale.ROOT).strip() + "|" + safeCity;
    }

    /** Package-visible for testing: direct cache population. */
    void putCacheEntry(String aliasTerm, String cityKey, String locality) {
        localityAliasCache.put(cacheKey(aliasTerm, cityKey), locality);
    }

    /** Package-visible for testing: check cache size. */
    int getCacheSize() { return localityAliasCache.size(); }
}
