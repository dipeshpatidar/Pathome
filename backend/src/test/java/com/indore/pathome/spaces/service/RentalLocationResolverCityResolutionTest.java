package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Dedicated unit test for city resolution precedence and ambiguity handling:
 * 1. Explicit high-confidence city from query (EXPLICIT_QUERY)
 * 2. Authoritative locality owning city (LOCALITY_RESOLUTION)
 * 3. Selected UI city (SELECTED_UI)
 * 4. Configured neutral default (DEFAULT)
 */
class RentalLocationResolverCityResolutionTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private LocalityRepository localityRepository;

    @Mock
    private SearchLearningService searchLearningService;

    private RentalLocationResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new RentalLocationResolver(listingRepository, searchLearningService, localityRepository);
    }

    @Test
    void explicitQueryCityOverridesSelectedUiCity() {
        // Selected Indore + "2bhk flat in pune" -> Pune / EXPLICIT_QUERY
        RentalSearchQuery q1 = RentalSearchQuery.parse("2bhk flat in pune");
        var res1 = resolver.resolveCity(q1, "Indore");
        assertEquals("Pune", res1.city());
        assertEquals(RentalLocationResolver.CitySource.EXPLICIT_QUERY, res1.source());
        assertTrue(res1.supported());

        // Selected Pune + "2bhk flat in indore" -> Indore / EXPLICIT_QUERY
        RentalSearchQuery q2 = RentalSearchQuery.parse("2bhk flat in indore");
        var res2 = resolver.resolveCity(q2, "Pune");
        assertEquals("Indore", res2.city());
        assertEquals(RentalLocationResolver.CitySource.EXPLICIT_QUERY, res2.source());
        assertTrue(res2.supported());
    }

    @Test
    void authoritativeLocalityInfersOwningCity() {
        // Selected Indore + "3bhk baner" -> Pune / LOCALITY_RESOLUTION
        RentalSearchQuery q = RentalSearchQuery.parse("3bhk baner");
        var res = resolver.resolveCity(q, "Indore");
        assertEquals("Pune", res.city());
        assertEquals(RentalLocationResolver.CitySource.LOCALITY_RESOLUTION, res.source());
        assertTrue(res.supported());

        // Selected Indore + "mp nagar" -> Bhopal / LOCALITY_RESOLUTION
        RentalSearchQuery qBhopal = RentalSearchQuery.parse("mp nagar");
        var resBhopal = resolver.resolveCity(qBhopal, "Indore");
        assertEquals("Bhopal", resBhopal.city());
        assertEquals(RentalLocationResolver.CitySource.LOCALITY_RESOLUTION, resBhopal.source());
        assertTrue(resBhopal.supported());
    }

    @Test
    void ambiguousLocalityAcrossMultipleCitiesDoesNotGuess() {
        // Locality exists in both Indore and Bhopal in DB
        Locality indoreLoc = mock(Locality.class);
        when(indoreLoc.getCity()).thenReturn("Indore");
        Locality bhopalLoc = mock(Locality.class);
        when(bhopalLoc.getCity()).thenReturn("Bhopal");

        when(localityRepository.findAllBySectorNameIgnoreCase("gandhi nagar"))
                .thenReturn(List.of(indoreLoc, bhopalLoc));

        RentalSearchQuery q = RentalSearchQuery.parse("3bhk gandhi nagar");
        var res = resolver.resolveCity(q, "Indore");

        // Must preserve selected UI city and NOT guess
        assertEquals("Indore", res.city());
        assertEquals(RentalLocationResolver.CitySource.SELECTED_UI, res.source());

        // Must accurately return both cities in LocalityCityMatch
        var match = resolver.resolveAuthoritativeLocalityCity("gandhi nagar");
        assertNotNull(match);
        assertFalse(match.isUnique());
        assertEquals(2, match.matchingCities().size());
        assertTrue(match.matchingCities().contains("Indore"));
        assertTrue(match.matchingCities().contains("Bhopal"));
    }

    @Test
    void resolveCanonicalLocalityNamesPreservesAuthoritativeCasing() {
        assertEquals("Baner", resolver.resolveCanonicalLocalityName("baner", "pune"));
        assertEquals("MP Nagar", resolver.resolveCanonicalLocalityName("mp nagar", "bhopal"));
        assertEquals("Vijay Nagar", resolver.resolveCanonicalLocalityName("vijay nagar", "indore"));
    }

    @Test
    void selectedUiCityUsedWhenNoExplicitCityOrLocalityInference() {
        RentalSearchQuery q = RentalSearchQuery.parse("2bhk flat");
        var res = resolver.resolveCity(q, "Indore");
        assertEquals("Indore", res.city());
        assertEquals(RentalLocationResolver.CitySource.SELECTED_UI, res.source());
        assertTrue(res.supported());

        var resPune = resolver.resolveCity(q, "Pune");
        assertEquals("Pune", resPune.city());
        assertEquals(RentalLocationResolver.CitySource.SELECTED_UI, resPune.source());
        assertTrue(resPune.supported());
    }

    @Test
    void unsupportedExplicitCityDoesNotSilentlyFallbackToIndore() {
        RentalSearchQuery q = RentalSearchQuery.parse("2bhk flat in mumbai");
        var res = resolver.resolveCity(q, "Indore");
        assertEquals("Mumbai", res.city());
        assertEquals(RentalLocationResolver.CitySource.EXPLICIT_QUERY, res.source());
        assertFalse(res.supported(), "Mumbai should be marked unsupported");
    }

    @Test
    void configuredNeutralDefaultUsedWhenNoContextProvided() {
        RentalSearchQuery q = RentalSearchQuery.parse("");
        var res = resolver.resolveCity(q, null);
        assertEquals("Indore", res.city());
        assertEquals(RentalLocationResolver.CitySource.DEFAULT, res.source());
        assertTrue(res.supported());
    }
}
