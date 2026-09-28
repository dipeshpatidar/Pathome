package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordLocationServiceTest {
    private LocalityRepository localities;
    private SearchLearningService aliases;
    private LandlordLocationService service;

    @BeforeEach
    void setUp() {
        localities = mock(LocalityRepository.class);
        aliases = mock(SearchLearningService.class);
        service = new LandlordLocationService(localities, aliases);
    }

    @Test
    void approvedAliasOnlyResolvesToExistingCanonicalLocality() {
        Locality vijayNagar = locality(10L, "Indore", "Vijay Nagar");
        when(aliases.resolveLocalityAlias("vijaynagr", "indore")).thenReturn("Vijay Nagar");
        when(localities.findByCityIgnoreCaseAndSectorNameIgnoreCase("Indore", "Vijay Nagar"))
                .thenReturn(Optional.of(vijayNagar));

        var choices = service.suggest("Indore", "vijaynagr");
        assertEquals(1, choices.size());
        assertEquals(10L, choices.get(0).id());
        assertEquals("alias", choices.get(0).match());
        verify(localities, never()).save(any());
    }

    @Test
    void unknownStaysUnknownAndOtherCityIsQualified() {
        when(localities.findOnboardingSuggestions(eq("Indore"), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(localities.findTop8BySectorNameIgnoreCaseOrderByCityAsc("Baner"))
                .thenReturn(List.of(locality(20L, "Pune", "Baner")));

        assertTrue(service.suggest("Indore", "not a locality").isEmpty());
        var alternatives = service.suggest("Indore", "Baner");
        assertEquals("different_city", alternatives.get(0).match());
        assertEquals("Pune", alternatives.get(0).city());
        verify(localities, never()).save(any());
    }

    @Test
    void submittedLocalityMustBelongToSelectedSupportedCity() {
        when(localities.findById(20L)).thenReturn(Optional.of(locality(20L, "Pune", "Baner")));
        assertThrows(IllegalArgumentException.class, () -> service.requireMatchingLocality("Indore", 20L));
        assertEquals("Baner", service.requireMatchingLocality("Pune", 20L).getSectorName());
        assertThrows(IllegalArgumentException.class, () -> service.requireMatchingLocality("Mumbai", 20L));
    }

    @Test
    void externalFallbackOnlyReturnsSelectedCityAndSignedSelectionCannotBeChanged() {
        ExternalLocalityProvider provider = (city, query) -> List.of(
                new ExternalLocalityProvider.Result("Rani Pura", "Indore", "MAPTILER", "locality.1"),
                new ExternalLocalityProvider.Result("Baner", "Pune", "MAPTILER", "locality.2"));
        var resolver = new LandlordLocationService(localities, aliases, provider, "test-secret");
        var choices = resolver.suggest("Indore", "rani pura");
        assertEquals(1, choices.size());
        assertEquals("external", choices.get(0).match());
        var choice = choices.get(0);
        assertTrue(resolver.validExternalSelection("Indore", choice.name(), "MAPTILER",
                choice.providerPlaceId(), choice.selectionToken()));
        assertFalse(resolver.validExternalSelection("Pune", choice.name(), "MAPTILER",
                choice.providerPlaceId(), choice.selectionToken()));
        assertFalse(resolver.validExternalSelection("Indore", "Invented", "MAPTILER",
                choice.providerPlaceId(), choice.selectionToken()));
        verify(localities, never()).save(any());
    }

    @Test
    void providerIsSkippedForInternalMatchAndUnavailableProviderLeavesManualPath() {
        var canonical = locality(3L, "Indore", "Vijay Nagar");
        when(localities.findOnboardingSuggestions(eq("Indore"), eq("vijay"), any(Pageable.class)))
                .thenReturn(List.of(canonical));
        ExternalLocalityProvider provider = mock(ExternalLocalityProvider.class);
        var resolver = new LandlordLocationService(localities, aliases, provider, "test-secret");
        assertEquals("canonical", resolver.suggest("Indore", "vijay").get(0).match());
        verifyNoInteractions(provider);
        assertTrue(resolver.suggest("Indore", "unknown locality").isEmpty());
    }

    @Test
    void fuzzyAndExternalSpellingsDeduplicateWithCanonicalFirst() {
        when(localities.findOnboardingSuggestions(eq("Indore"), eq("rani pura"), any(Pageable.class)))
                .thenReturn(List.of(locality(4L, "Indore", "Ranipura")));
        ExternalLocalityProvider provider = (city, query) -> List.of(
                new ExternalLocalityProvider.Result("Rani Pura", "Indore", "MAPTILER", "place.4"));
        var resolver = new LandlordLocationService(localities, aliases, provider, "test-secret");
        var suggestions = resolver.suggest("Indore", "rani pura");
        assertEquals(1, suggestions.size());
        assertEquals(4L, suggestions.get(0).id());
    }

    @Test
    void providerExceptionDoesNotBreakInternalOrManualChoices() {
        ExternalLocalityProvider provider = (city, query) -> { throw new IllegalStateException("unavailable"); };
        var resolver = new LandlordLocationService(localities, aliases, provider, "test-secret");
        assertTrue(resolver.suggest("Indore", "rani pura").isEmpty());
        verify(localities, never()).save(any());
    }

    private Locality locality(Long id, String city, String name) {
        Locality locality = new Locality();
        locality.setId(id);
        locality.setCity(city);
        locality.setSectorName(name);
        return locality;
    }

    @Test
    void liveMapTilerEndToEndResolutionWhenConfigured() {
        String key = System.getenv("MAPTILER_API_KEY");
        if (key == null || key.isBlank()) return;

        var maptiler = new MapTilerLocalityProvider(key, new com.fasterxml.jackson.databind.ObjectMapper());
        var resolver = new LandlordLocationService(localities, aliases, maptiler, "test-secret");

        // 1. Known locality: Vijay Nagar (mocked canonical) -> returns canonical
        Locality vijay = locality(10L, "Indore", "Vijay Nagar");
        when(localities.findByCityIgnoreCaseAndSectorNameIgnoreCase("Indore", "Vijay Nagar"))
                .thenReturn(Optional.of(vijay));
        var vijayChoices = resolver.suggest("Indore", "Vijay Nagar");
        assertEquals(1, vijayChoices.size());
        assertEquals("canonical", vijayChoices.get(0).match());

        // 2. Real locality not known internally: rani pura -> MapTiler returns Ranipura
        when(localities.findByCityIgnoreCaseAndSectorNameIgnoreCase("Indore", "rani pura"))
                .thenReturn(Optional.empty());
        when(localities.findOnboardingSuggestions(eq("Indore"), eq("rani pura"), any(Pageable.class)))
                .thenReturn(List.of());
        var raniPuraChoices = resolver.suggest("Indore", "rani pura");
        assertFalse(raniPuraChoices.isEmpty(), "Expected external resolution for rani pura");
        var choice = raniPuraChoices.get(0);
        assertEquals("Ranipura", choice.name());
        assertEquals("Indore", choice.city());
        assertEquals("external", choice.match());
        assertEquals("MAPTILER", choice.provider());
        assertNotNull(choice.providerPlaceId());
        assertTrue(resolver.validExternalSelection("Indore", choice.name(), choice.provider(),
                choice.providerPlaceId(), choice.selectionToken()));

        // 3. Garbage input: xyzabc999 -> rejected, empty list
        var garbageChoices = resolver.suggest("Indore", "xyzabc999");
        assertTrue(garbageChoices.isEmpty(), "Garbage input must not fabricate confident results");
    }
}
