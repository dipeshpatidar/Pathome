package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapTilerLocalityProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingKeyDisablesProviderWithoutNetworkRequest() {
        assertTrue(new MapTilerLocalityProvider("", mapper).search("Indore", "rani pura").isEmpty());
    }

    @Test
    void requiresIndianCountryAndMatchingMunicipalityContext() throws Exception {
        var indore = mapper.readTree("""
                {"properties":{"country_code":"in"},"context":[{"id":"municipality.1","text":"Indore"}]}
                """);
        var indoreCity = mapper.readTree("""
                {"properties":{"country_code":"in"},"context":[{"id":"municipality.220054","text":"Indore City"}]}
                """);
        var indoreSubregion = mapper.readTree("""
                {"properties":{"country_code":"in"},"context":[{"id":"subregion.603","text":"Indore"}]}
                """);
        var bhopal = mapper.readTree("""
                {"properties":{"country_code":"in"},"context":[{"id":"municipality.2","text":"Bhopal"}]}
                """);
        var foreign = mapper.readTree("""
                {"properties":{"country_code":"us"},"context":[{"id":"municipality.1","text":"Indore"}]}
                """);
        assertTrue(MapTilerLocalityProvider.insideCity(indore, "Indore"));
        assertTrue(MapTilerLocalityProvider.insideCity(indoreCity, "Indore"));
        assertTrue(MapTilerLocalityProvider.insideCity(indoreSubregion, "Indore"));
        assertFalse(MapTilerLocalityProvider.insideCity(bhopal, "Indore"));
        assertFalse(MapTilerLocalityProvider.insideCity(foreign, "Indore"));
    }

    @Test
    void relevanceRejectsIdenticalCityOrMismatchedGarbage() {
        assertTrue(MapTilerLocalityProvider.isRelevant("Ranipura", "rani pura", "Indore"));
        assertTrue(MapTilerLocalityProvider.isRelevant("Ranipura", "ranipura", "Indore"));
        assertFalse(MapTilerLocalityProvider.isRelevant("Indore", "xyzabc999", "Indore"));
        assertFalse(MapTilerLocalityProvider.isRelevant("Indore City", "xyzabc999", "Indore"));
        assertFalse(MapTilerLocalityProvider.isRelevant("Indore - Kampel Rd", "xyzabc999", "Indore"));
    }

    @Test
    void liveProviderIntegrationWhenConfigured() {
        String key = System.getenv("MAPTILER_API_KEY");
        if (key == null || key.isBlank()) return;

        var provider = new MapTilerLocalityProvider(key, mapper);
        var raniPuraResults = provider.search("Indore", "rani pura");
        assertFalse(raniPuraResults.isEmpty(), "Expected at least one MapTiler result for rani pura");
        assertEquals("Ranipura", raniPuraResults.get(0).name());
        assertEquals("Indore", raniPuraResults.get(0).city());
        assertEquals("MAPTILER", raniPuraResults.get(0).source());
        assertNotNull(raniPuraResults.get(0).placeId());

        var garbageResults = provider.search("Indore", "xyzabc999");
        assertTrue(garbageResults.isEmpty(), "Garbage input must not fabricate confident results");
    }
}
