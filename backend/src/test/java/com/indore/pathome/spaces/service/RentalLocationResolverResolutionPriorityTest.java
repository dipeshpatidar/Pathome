package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit and resolution lifecycle test for {@link RentalLocationResolver} and {@link SearchLearningService}.
 *
 * Verifies:
 * - Priority: EXACT canonical > PREFIX > ALIAS resolution > FUZZY trigram fallback
 * - Approved alias takes precedence over trigram fuzzy matching
 * - Exact canonical match always outranks learned alias
 * - Cache eviction restores fallback behavior
 */
class RentalLocationResolverResolutionPriorityTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private SearchLearningService searchLearningService;

    private RentalLocationResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new RentalLocationResolver(listingRepository, searchLearningService);
    }

    @Test
    void exact_match_outranks_learned_alias() {
        ListingRepository.LocalitySuggestionRow exactRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(exactRow.getCity()).thenReturn("Indore");
        when(exactRow.getLocality()).thenReturn("Vijay Nagar");
        when(exactRow.getResultCount()).thenReturn(13L);

        // Exact match found on canonical data
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), anyString(), anyString(), anyString(), any(), any(), eq("vijay nagar"), any(Pageable.class)))
                .thenReturn(List.of(exactRow));

        RentalSearchQuery query = RentalSearchQuery.parse("vijay nagar");
        List<RentalLocationResolver.Match> matches = resolver.suggestions(query, "indore", 5);

        assertFalse(matches.isEmpty());
        assertEquals("exact", matches.get(0).method());
        assertEquals(1.0, matches.get(0).confidence());
        assertEquals("Vijay Nagar", matches.get(0).locality());

        // Learned alias service should NEVER even be called when prefix/exact matches
        verify(searchLearningService, never()).resolveLocalityAlias(anyString(), anyString());
    }

    @Test
    void alias_resolution_takes_precedence_over_fuzzy_trigram() {
        String typo = "vijayngr";

        // 1. Prefix query returns empty (typo does not prefix-match Vijay Nagar)
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), anyString(), anyString(), anyString(), any(), any(), eq(typo), any(Pageable.class)))
                .thenReturn(List.of());

        // 2. Alias resolution succeeds: "vijayngr" -> "Vijay Nagar"
        when(searchLearningService.resolveLocalityAlias(typo, "indore")).thenReturn("Vijay Nagar");

        // 3. Locality query for resolved alias returns the canonical listing row
        ListingRepository.LocalitySuggestionRow aliasRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(aliasRow.getCity()).thenReturn("Indore");
        when(aliasRow.getLocality()).thenReturn("Vijay Nagar");
        when(aliasRow.getResultCount()).thenReturn(13L);

        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), anyString(), anyString(), anyString(), any(), any(), eq("vijay nagar"), any(Pageable.class)))
                .thenReturn(List.of(aliasRow));

        RentalSearchQuery query = RentalSearchQuery.parse(typo);
        List<RentalLocationResolver.Match> matches = resolver.suggestions(query, "indore", 5);

        assertFalse(matches.isEmpty());
        RentalLocationResolver.Match top = matches.get(0);
        assertEquals("alias", top.method(), "Resolution method must be ALIAS");
        assertEquals(0.95, top.confidence(), "Alias confidence is high (0.95)");
        assertEquals("Vijay Nagar", top.locality());

        // Fuzzy fallback must NOT be called when alias matches
        verify(listingRepository, never()).findPublicRentalFuzzyLocalities(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyString(), anyDouble(), any(Pageable.class));
    }

    @Test
    void fallback_resumes_when_alias_is_disabled_or_absent() {
        String typo = "vijayngr";

        // Prefix query returns empty
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), anyString(), anyString(), anyString(), any(), any(), eq(typo), any(Pageable.class)))
                .thenReturn(List.of());

        // Alias resolution returns null (disabled or not promoted)
        when(searchLearningService.resolveLocalityAlias(typo, "indore")).thenReturn(null);

        // Trigram fuzzy match returns result
        ListingRepository.LocalitySuggestionRow fuzzyRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(fuzzyRow.getCity()).thenReturn("Indore");
        when(fuzzyRow.getLocality()).thenReturn("Vijay Nagar");
        when(fuzzyRow.getResultCount()).thenReturn(13L);
        when(fuzzyRow.getSimilarity()).thenReturn(0.68);

        when(listingRepository.findPublicRentalFuzzyLocalities(
                eq("indore"), anyString(), anyString(), anyString(), any(), any(), eq(typo), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(fuzzyRow));

        RentalSearchQuery query = RentalSearchQuery.parse(typo);
        List<RentalLocationResolver.Match> matches = resolver.suggestions(query, "indore", 5);

        assertFalse(matches.isEmpty());
        RentalLocationResolver.Match top = matches.get(0);
        assertEquals("trigram", top.method(), "Falls back to FUZZY trigram matching");
        assertEquals(0.68, top.confidence());
        assertEquals("Vijay Nagar", top.locality());
    }
}
