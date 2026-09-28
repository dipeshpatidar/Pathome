package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CityRegistryTest {

    @Test
    void supportedCitiesContainOnlyAuthoritativeCities() {
        var supported = CityRegistry.getSupportedCities();
        assertTrue(supported.contains("indore"));
        assertTrue(supported.contains("bhopal"));
        assertTrue(supported.contains("pune"));

        // Locality and project names must NOT be supported cities
        assertFalse(supported.contains("areea"));
        assertFalse(supported.contains("prestige"));
        assertFalse(supported.contains("vijay"));
        assertFalse(supported.contains("vijay nagar"));

        assertTrue(CityRegistry.isCitySupported("Indore"));
        assertTrue(CityRegistry.isCitySupported("Bhopal"));
        assertTrue(CityRegistry.isCitySupported("Pune"));
        assertFalse(CityRegistry.isCitySupported("Areea"));
        assertFalse(CityRegistry.isCitySupported("Prestige"));
        assertFalse(CityRegistry.isCitySupported("Vijay"));
    }

    @Test
    void syncFromDatabaseDoesNotDeriveSupportedCitiesFromLocalities() {
        LocalityRepository localities = mock(LocalityRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        when(localities.findDistinctCities()).thenReturn(List.of("Areea", "Prestige", "Vijay", "Indore"));

        CityRegistry.syncFromDatabase(localities, listings);

        var supported = CityRegistry.getSupportedCities();
        assertFalse(supported.contains("areea"));
        assertFalse(supported.contains("prestige"));
        assertFalse(supported.contains("vijay"));
        assertTrue(supported.contains("indore"));
    }

    @Test
    void landlordLocationServiceReturnsOnlyAuthoritativeSupportedCities() {
        LocalityRepository localities = mock(LocalityRepository.class);
        SearchLearningService aliases = mock(SearchLearningService.class);
        LandlordLocationService service = new LandlordLocationService(localities, aliases);

        List<String> cities = service.cities();
        assertEquals(List.of("Bhopal", "Indore", "Pune"), cities);
        assertFalse(cities.contains("Areea"));
        assertFalse(cities.contains("Prestige"));
        assertFalse(cities.contains("Vijay"));
    }
}
