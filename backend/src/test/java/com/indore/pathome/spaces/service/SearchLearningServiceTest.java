package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.SearchFeedbackRequest;
import com.indore.pathome.spaces.entity.SearchAlias;
import com.indore.pathome.spaces.entity.SearchAliasCandidate;
import com.indore.pathome.spaces.entity.SearchQueryEvent;
import com.indore.pathome.spaces.repository.SearchAliasCandidateRepository;
import com.indore.pathome.spaces.repository.SearchAliasRepository;
import com.indore.pathome.spaces.repository.SearchQueryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 tests for {@link SearchLearningService}.
 *
 * Verifies Phase-1 Governance guarantees:
 * - Telemetry capture is non-blocking and failure-isolated
 * - Per-session deduplication bounds event spam
 * - Selection telemetry records genuine user choice signals with hashed sessions
 * - Aggregation tracks distinct independent sessions (uniqueSessionCount)
 * - Auto-promotion is disabled in Phase-1: candidates become ELIGIBLE_FOR_REVIEW
 * - Admin approval creates active SearchAlias and immediately populates runtime cache
 * - Admin disable marks alias DISABLED and immediately evicts runtime cache
 * - Admin rejection prevents future promotion
 * - SearchLearningService has zero dependencies on canonical entities (Listings/Localities)
 */
class SearchLearningServiceTest {

    @Mock private SearchQueryEventRepository eventRepository;
    @Mock private SearchAliasCandidateRepository candidateRepository;
    @Mock private SearchAliasRepository aliasRepository;

    private SearchLearningService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SearchLearningService(eventRepository, candidateRepository, aliasRepository);
    }

    // ─── Telemetry capture & Deduplication ─────────────────────────────────────

    @Test
    void captureEvent_persists_structured_telemetry_event() {
        when(eventRepository.countRecentDuplicatesForSession(any(), any(), any())).thenReturn(0L);
        SearchQueryEvent event = new SearchQueryEvent();
        event.setLocationCandidate("vijay nagar");
        event.setSessionHash("abc");
        event.setBhkKey("3BHK");

        service.captureEvent(event);

        verify(eventRepository, times(1)).save(event);
        assertEquals("SUGGESTION_SHOWN", event.getEventType(), "Default event_type should be SUGGESTION_SHOWN");
    }

    @Test
    void captureEvent_skips_duplicate_session_contribution_within_window() {
        when(eventRepository.countRecentDuplicatesForSession(
                eq("sess123"), eq("vijay nagar"), any(LocalDateTime.class)))
                .thenReturn((long) SearchLearningService.MAX_EVENTS_PER_SESSION_PER_CANDIDATE);

        SearchQueryEvent event = new SearchQueryEvent();
        event.setSessionHash("sess123");
        event.setLocationCandidate("vijay nagar");

        service.captureEvent(event);

        verify(eventRepository, never()).save(any());
    }

    @Test
    void captureEvent_swallows_repository_exception_and_never_propagates() {
        when(eventRepository.countRecentDuplicatesForSession(any(), any(), any())).thenReturn(0L);
        when(eventRepository.save(any())).thenThrow(new RuntimeException("DB unavailable"));

        SearchQueryEvent event = new SearchQueryEvent();
        event.setLocationCandidate("sector23");
        event.setSessionHash("s");

        // Must NOT throw; fire-and-forget guarantee
        assertDoesNotThrow(() -> service.captureEvent(event));
    }

    @Test
    void captureFeedback_hashes_session_id_and_records_selection_event() {
        when(eventRepository.countRecentDuplicatesForSession(any(), any(), any())).thenReturn(0L);

        SearchFeedbackRequest request = new SearchFeedbackRequest(
                "SUGGESTION_SELECTED",
                "vijaynagr",
                "Vijay Nagar",
                "Indore",
                "LOCALITY",
                (short) 0,
                "FUZZY",
                BigDecimal.valueOf(0.85),
                "ephemeral-uuid-12345",
                "2BHK",
                "FLAT",
                null,
                false,
                null,
                false
        );

        service.captureFeedback(request);

        ArgumentCaptor<SearchQueryEvent> captor = ArgumentCaptor.forClass(SearchQueryEvent.class);
        verify(eventRepository, times(1)).save(captor.capture());
        SearchQueryEvent saved = captor.getValue();

        assertEquals("SUGGESTION_SELECTED", saved.getEventType());
        assertEquals("vijaynagr", saved.getLocationCandidate());
        assertEquals("Vijay Nagar", saved.getResolvedLocality());
        assertEquals("Indore", saved.getResolvedCity());
        assertEquals("LOCALITY", saved.getSelectedType());
        assertEquals((short) 0, saved.getSelectedRank());
        assertNotNull(saved.getSessionHash());
        assertFalse(saved.getSessionHash().contains("ephemeral-uuid-12345"),
                "Raw session UUID must never be stored in plaintext");
    }

    // ─── Session hash utility ───────────────────────────────────────────────────

    @Test
    void hashSessionId_returns_non_null_fixed_length_opaque_string() {
        String hash = SearchLearningService.hashSessionId("test-session-key-abc123");
        assertNotNull(hash);
        assertEquals(43, hash.length(), "SHA-256 URL-safe base64 without padding is 43 chars");
        assertFalse(hash.contains("test"), "Hash must not contain raw session value");
    }

    @Test
    void hashSessionId_is_deterministic_for_same_input() {
        assertEquals(
                SearchLearningService.hashSessionId("same-key"),
                SearchLearningService.hashSessionId("same-key"));
    }

    @Test
    void hashSessionId_returns_null_for_blank_input() {
        assertNull(SearchLearningService.hashSessionId(null));
        assertNull(SearchLearningService.hashSessionId(""));
        assertNull(SearchLearningService.hashSessionId("   "));
    }

    // ─── Alias cache resolution ─────────────────────────────────────────────────

    @Test
    void resolveLocalityAlias_returns_canonical_for_known_alias() {
        service.putCacheEntry("vijaynagr", "indore", "Vijay Nagar");
        assertEquals("Vijay Nagar", service.resolveLocalityAlias("vijaynagr", "indore"));
    }

    @Test
    void resolveLocalityAlias_returns_null_for_unknown_term() {
        assertNull(service.resolveLocalityAlias("unknownplace", "indore"));
    }

    @Test
    void resolveLocalityAlias_falls_back_to_agnostic_entry_when_no_city_scoped_hit() {
        service.putCacheEntry("sector23", "", "Sector 23");
        assertEquals("Sector 23", service.resolveLocalityAlias("sector23", "bhopal"));
    }

    @Test
    void refreshAliasCache_loads_active_aliases_and_clears_stale_entries() {
        service.putCacheEntry("stale-term", "indore", "Old Locality");
        assertEquals(1, service.getCacheSize());

        SearchAlias alias = new SearchAlias();
        alias.setAliasTerm("vijaynagar");
        alias.setEntityValue("Vijay Nagar");
        alias.setEntityCity("indore");
        alias.setEntityType("LOCALITY");
        alias.setStatus("ACTIVE");

        when(aliasRepository.findActiveLocalityAliasesForCity("")).thenReturn(List.of(alias));

        service.refreshAliasCache();

        assertEquals(1, service.getCacheSize(), "Stale entry cleared; new alias loaded");
        assertEquals("Vijay Nagar", service.resolveLocalityAlias("vijaynagar", "indore"));
        assertNull(service.resolveLocalityAlias("stale-term", "indore"),
                "Stale entry must be gone after refresh");
    }

    // ─── Candidate aggregation with Unique Sessions ──────────────────────────────

    @Test
    void aggregateCandidates_creates_new_candidate_with_unique_session_count() {
        SearchQueryEventRepository.CandidateAggregationRow row = mock(SearchQueryEventRepository.CandidateAggregationRow.class);
        when(row.getCandidateTerm()).thenReturn("vijay nagr");
        when(row.getCanonicalEntityValue()).thenReturn("Vijay Nagar");
        when(row.getCanonicalCity()).thenReturn("Indore");
        when(row.getEvidenceCount()).thenReturn(5L);
        when(row.getUniqueSessionCount()).thenReturn(4L);
        when(row.getSuccessCount()).thenReturn(4L);
        when(row.getAvgConfidence()).thenReturn(0.82);

        when(eventRepository.aggregateFuzzyResolutionCandidates(any(), anyInt())).thenReturn(List.of(row));
        when(candidateRepository.findAmbiguousCandidateTerms("LOCALITY")).thenReturn(List.of());
        when(candidateRepository.findByCandidateTermAndCanonicalEntityTypeAndCanonicalEntityValueAndCanonicalCity(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service.aggregateCandidates();

        ArgumentCaptor<SearchAliasCandidate> captor = ArgumentCaptor.forClass(SearchAliasCandidate.class);
        verify(candidateRepository, times(1)).save(captor.capture());
        SearchAliasCandidate saved = captor.getValue();
        assertEquals("vijay nagr", saved.getCandidateTerm());
        assertEquals("Vijay Nagar", saved.getCanonicalEntityValue());
        assertEquals("Indore", saved.getCanonicalCity());
        assertEquals(5, saved.getEvidenceCount());
        assertEquals(4, saved.getUniqueSessionCount(), "Unique session count must be recorded");
        assertEquals(4, saved.getSuccessfulSelectionCount());
        assertEquals("CANDIDATE", saved.getStatus());
        assertEquals(BigDecimal.valueOf(0.800).setScale(3), saved.getSelectionRate());
    }

    @Test
    void aggregateCandidates_skips_ambiguous_candidate_terms() {
        SearchQueryEventRepository.CandidateAggregationRow row = mock(SearchQueryEventRepository.CandidateAggregationRow.class);
        when(row.getCandidateTerm()).thenReturn("vn");
        when(row.getCanonicalEntityValue()).thenReturn("Vijay Nagar");
        when(row.getCanonicalCity()).thenReturn("Indore");

        when(eventRepository.aggregateFuzzyResolutionCandidates(any(), anyInt())).thenReturn(List.of(row));
        when(candidateRepository.findAmbiguousCandidateTerms("LOCALITY")).thenReturn(List.of("vn"));

        service.aggregateCandidates();

        verify(candidateRepository, never()).save(any());
    }

    // ─── Phase-1 Governance: Auto-Promotion is Disabled ─────────────────────────

    @Test
    void promoteEligibleCandidates_marks_eligible_for_review_and_never_auto_promotes() {
        assertFalse(SearchLearningService.AUTO_PROMOTION_ENABLED,
                "Phase-1 Requirement: AUTO_PROMOTION_ENABLED must be false");

        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setId(101L);
        candidate.setCandidateTerm("vijaynagr");
        candidate.setCanonicalEntityType("LOCALITY");
        candidate.setCanonicalEntityValue("Vijay Nagar");
        candidate.setCanonicalCity("indore");
        candidate.setEvidenceCount(15);
        candidate.setUniqueSessionCount(5);
        candidate.setSuccessfulSelectionCount(12);
        candidate.setRejectionCount(0);
        candidate.setSelectionRate(BigDecimal.valueOf(0.800));
        candidate.setStatus("CANDIDATE");

        when(candidateRepository.findAmbiguousCandidateTerms("LOCALITY")).thenReturn(List.of());
        when(candidateRepository.findEligibleForReview(
                SearchLearningService.MIN_EVIDENCE_FOR_PROMOTION,
                SearchLearningService.MIN_UNIQUE_SESSIONS_FOR_PROMOTION,
                SearchLearningService.MIN_SELECTION_RATE_FOR_PROMOTION))
                .thenReturn(List.of(candidate));

        service.promoteEligibleCandidates();

        // Must NOT save any active alias
        verify(aliasRepository, never()).save(any(SearchAlias.class));

        // Must update candidate to ELIGIBLE_FOR_REVIEW
        assertEquals("ELIGIBLE_FOR_REVIEW", candidate.getStatus(),
                "Candidate meeting criteria must be marked ELIGIBLE_FOR_REVIEW, not AUTO_PROMOTED");
        verify(candidateRepository, times(1)).save(candidate);
    }

    // ─── Controlled Approval & Rejection ────────────────────────────────────────

    @Test
    void approveCandidate_creates_active_alias_and_updates_cache_immediately() {
        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setId(42L);
        candidate.setCandidateTerm("vijaynagr");
        candidate.setCanonicalEntityType("LOCALITY");
        candidate.setCanonicalEntityValue("Vijay Nagar");
        candidate.setCanonicalCity("indore");
        candidate.setEvidenceCount(12);
        candidate.setUniqueSessionCount(4);
        candidate.setSuccessfulSelectionCount(10);
        candidate.setSelectionRate(BigDecimal.valueOf(0.833));
        candidate.setAverageConfidence(BigDecimal.valueOf(0.88));
        candidate.setStatus("ELIGIBLE_FOR_REVIEW");

        when(candidateRepository.findById(42L)).thenReturn(Optional.of(candidate));
        when(aliasRepository.findByAliasTermAndEntityTypeAndEntityCity("vijaynagr", "LOCALITY", "indore"))
                .thenReturn(Optional.empty());
        when(aliasRepository.save(any(SearchAlias.class))).thenAnswer(inv -> inv.getArgument(0));

        SearchAlias alias = service.approveCandidate(42L, "admin_user");

        assertNotNull(alias);
        assertEquals("vijaynagr", alias.getAliasTerm());
        assertEquals("Vijay Nagar", alias.getEntityValue());
        assertEquals("indore", alias.getEntityCity());
        assertEquals("ACTIVE", alias.getStatus());
        assertEquals(42L, alias.getSourceCandidateId());

        // Verify candidate updated
        assertEquals("APPROVED", candidate.getStatus());
        assertEquals("admin_user", candidate.getPromotedBy());
        assertNotNull(candidate.getPromotedAt());

        // Verify runtime cache is updated IMMEDIATELY without restart
        assertEquals("Vijay Nagar", service.resolveLocalityAlias("vijaynagr", "indore"),
                "Runtime cache must immediately resolve newly approved alias");
    }

    @Test
    void rejectCandidate_marks_candidate_rejected() {
        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setId(99L);
        candidate.setStatus("CANDIDATE");

        when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(SearchAliasCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        SearchAliasCandidate rejected = service.rejectCandidate(99L, "reviewer", "Too ambiguous");

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("reviewer", rejected.getPromotedBy());
        assertTrue(rejected.getPromotionEvidence().contains("Too ambiguous"));
    }

    // ─── Controlled Disable & Immediate Cache Eviction ──────────────────────────

    @Test
    void disableAlias_marks_disabled_and_evicts_cache_immediately() {
        // Pre-warm cache with active alias
        service.putCacheEntry("vijaynagr", "indore", "Vijay Nagar");
        assertEquals("Vijay Nagar", service.resolveLocalityAlias("vijaynagr", "indore"));

        SearchAlias alias = new SearchAlias();
        alias.setAliasTerm("vijaynagr");
        alias.setEntityCity("indore");
        alias.setEntityType("LOCALITY");
        alias.setEntityValue("Vijay Nagar");
        alias.setStatus("ACTIVE");
        alias.setSourceCandidateId(55L);

        SearchAliasCandidate sourceCandidate = new SearchAliasCandidate();
        sourceCandidate.setId(55L);
        sourceCandidate.setStatus("APPROVED");

        when(aliasRepository.findById(77L)).thenReturn(Optional.of(alias));
        when(aliasRepository.save(any(SearchAlias.class))).thenAnswer(inv -> inv.getArgument(0));
        when(candidateRepository.findById(55L)).thenReturn(Optional.of(sourceCandidate));

        SearchAlias disabled = service.disableAlias(77L, "Reported incorrect");

        assertEquals("DISABLED", disabled.getStatus());
        assertNotNull(disabled.getDisabledAt());
        assertEquals("Reported incorrect", disabled.getDisabledReason());
        assertEquals("DISABLED", sourceCandidate.getStatus());

        // Verify cache eviction IMMEDIATELY without restart
        assertNull(service.resolveLocalityAlias("vijaynagr", "indore"),
                "Disabled alias must be evicted from cache immediately");
    }

    // ─── Architectural & Canonical Data Safety ──────────────────────────────────

    @Test
    void canonicalDataProtection_service_has_zero_canonical_repository_dependencies() {
        // Enforce that SearchLearningService does NOT inject ListingRepository, Locality, City, etc.
        for (java.lang.reflect.Field field : SearchLearningService.class.getDeclaredFields()) {
            String typeName = field.getType().getSimpleName();
            assertFalse(typeName.contains("Listing"), "SearchLearningService must never depend on Listing entities/repos");
            assertFalse(typeName.contains("Locality"), "SearchLearningService must never depend on canonical Locality repos");
            assertFalse(typeName.contains("User"), "SearchLearningService must never depend on User repos");
        }
    }

    // ─── Promotion thresholds (contract tests) ─────────────────────────────────

    @Test
    void promotionThresholds_are_conservatively_high() {
        assertTrue(SearchLearningService.MIN_EVIDENCE_FOR_PROMOTION >= 10,
                "Minimum evidence must be >= 10 to prevent premature alias creation");
        assertTrue(SearchLearningService.MIN_UNIQUE_SESSIONS_FOR_PROMOTION >= 3,
                "Independent sessions must be >= 3");
        assertTrue(SearchLearningService.MIN_SELECTION_RATE_FOR_PROMOTION >= 0.70,
                "Minimum selection rate must be >= 0.70 to ensure alias quality");
    }
}
