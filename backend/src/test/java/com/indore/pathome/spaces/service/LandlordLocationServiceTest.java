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

    private Locality locality(Long id, String city, String name) {
        Locality locality = new Locality();
        locality.setId(id);
        locality.setCity(city);
        locality.setSectorName(name);
        return locality;
    }
}
