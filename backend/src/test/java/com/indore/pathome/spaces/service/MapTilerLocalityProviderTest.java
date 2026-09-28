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
        var bhopal = mapper.readTree("""
                {"properties":{"country_code":"in"},"context":[{"id":"municipality.2","text":"Bhopal"}]}
                """);
        var foreign = mapper.readTree("""
                {"properties":{"country_code":"us"},"context":[{"id":"municipality.1","text":"Indore"}]}
                """);
        assertTrue(MapTilerLocalityProvider.insideCity(indore, "Indore"));
        assertFalse(MapTilerLocalityProvider.insideCity(bhopal, "Indore"));
        assertFalse(MapTilerLocalityProvider.insideCity(foreign, "Indore"));
    }
}
