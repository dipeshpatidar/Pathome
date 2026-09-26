package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.SearchAlias;
import com.indore.pathome.spaces.entity.SearchAliasCandidate;
import com.indore.pathome.spaces.repository.SearchAliasCandidateRepository;
import com.indore.pathome.spaces.repository.SearchAliasRepository;
import com.indore.pathome.spaces.service.SearchLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminSearchLearningController}.
 */
class AdminSearchLearningControllerTest {

    @Mock private SearchLearningService searchLearningService;
    @Mock private SearchAliasCandidateRepository candidateRepository;
    @Mock private SearchAliasRepository aliasRepository;
    @Mock private Principal principal;

    private AdminSearchLearningController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(principal.getName()).thenReturn("admin@pathome.com");
        controller = new AdminSearchLearningController(searchLearningService, candidateRepository, aliasRepository);
    }

    @Test
    void getCandidates_returns_candidates_from_repository() {
        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setCandidateTerm("vijaynagr");
        when(candidateRepository.findAll()).thenReturn(List.of(candidate));

        ResponseEntity<List<SearchAliasCandidate>> response = controller.getCandidates(null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("vijaynagr", response.getBody().get(0).getCandidateTerm());
    }

    @Test
    void getCandidates_with_status_filter() {
        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setStatus("ELIGIBLE_FOR_REVIEW");
        when(candidateRepository.findByStatus("ELIGIBLE_FOR_REVIEW")).thenReturn(List.of(candidate));

        ResponseEntity<List<SearchAliasCandidate>> response = controller.getCandidates("ELIGIBLE_FOR_REVIEW");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(candidateRepository, times(1)).findByStatus("ELIGIBLE_FOR_REVIEW");
    }

    @Test
    void approveCandidate_delegates_to_service_with_principal_username() {
        SearchAlias alias = new SearchAlias();
        alias.setAliasTerm("vijaynagr");
        alias.setStatus("ACTIVE");
        when(searchLearningService.approveCandidate(eq(10L), eq("admin@pathome.com"))).thenReturn(alias);

        ResponseEntity<SearchAlias> response = controller.approveCandidate(10L, principal);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("ACTIVE", response.getBody().getStatus());
        verify(searchLearningService, times(1)).approveCandidate(10L, "admin@pathome.com");
    }

    @Test
    void rejectCandidate_delegates_to_service_with_reason() {
        SearchAliasCandidate candidate = new SearchAliasCandidate();
        candidate.setStatus("REJECTED");
        when(searchLearningService.rejectCandidate(eq(15L), eq("admin@pathome.com"), eq("Too ambiguous")))
                .thenReturn(candidate);

        ResponseEntity<SearchAliasCandidate> response = controller.rejectCandidate(
                15L, Map.of("reason", "Too ambiguous"), principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("REJECTED", response.getBody().getStatus());
    }

    @Test
    void disableAlias_delegates_to_service_with_reason() {
        SearchAlias alias = new SearchAlias();
        alias.setStatus("DISABLED");
        when(searchLearningService.disableAlias(eq(25L), eq("Typo no longer needed"))).thenReturn(alias);

        ResponseEntity<SearchAlias> response = controller.disableAlias(
                25L, Map.of("reason", "Typo no longer needed"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DISABLED", response.getBody().getStatus());
    }

    @Test
    void getMetrics_returns_metrics_from_service() {
        SearchLearningService.SearchLearningMetrics metrics = new SearchLearningService.SearchLearningMetrics(
                100, 80, 20, 5, 12, 3, 2, 1, 4, 1, 15, 25);
        when(searchLearningService.getMetrics()).thenReturn(metrics);

        ResponseEntity<SearchLearningService.SearchLearningMetrics> response = controller.getMetrics();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(100, response.getBody().totalSearchEvents());
        assertEquals(5, response.getBody().zeroResultSearches());
        assertEquals(4, response.getBody().activeAliases());
    }

    @Test
    void triggerAggregation_invokes_service() {
        ResponseEntity<Map<String, String>> response = controller.triggerAggregation();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        verify(searchLearningService, times(1)).aggregateCandidates();
    }

    @Test
    void triggerCacheRefresh_invokes_service() {
        ResponseEntity<Map<String, String>> response = controller.triggerCacheRefresh();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        verify(searchLearningService, times(1)).refreshAliasCache();
    }
}
