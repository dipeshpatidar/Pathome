package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LandlordLocationService {
    private final LocalityRepository localities;
    private final SearchLearningService aliases;

    public LandlordLocationService(LocalityRepository localities, SearchLearningService aliases) {
        this.localities = localities;
        this.aliases = aliases;
    }

    public record Option(Long id, String city, String name, String match) {}

    @Transactional(readOnly = true)
    public List<String> cities() {
        return CityRegistry.getSupportedCities().stream()
                .map(CityRegistry::canonicalCityName).sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<Option> suggest(String city, String input) {
        if (city == null || !CityRegistry.isCitySupported(city)) {
            throw new IllegalArgumentException("Choose a supported city");
        }
        if (input == null || input.isBlank()) return List.of();
        if (input.length() > 120) throw new IllegalArgumentException("Locality search is too long");
        String term = RentalSearchQuery.normalizeLocation(input);
        if (term.length() < 2) return List.of();
        String canonicalCity = CityRegistry.canonicalCityName(city);
        String aliasName = aliases.resolveLocalityAlias(term, canonicalCity.toLowerCase());
        if (aliasName != null) {
            var aliasMatch = localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(canonicalCity, aliasName);
            if (aliasMatch.isPresent()) return List.of(toOption(aliasMatch.get(), "alias"));
        }
        List<Option> inCity = localities.findOnboardingSuggestions(canonicalCity, term, PageRequest.of(0, 8))
                .stream().map(locality -> toOption(locality, "canonical")).toList();
        if (!inCity.isEmpty()) return inCity;
        // A locality from another supported city is shown as an alternative, never silently selected.
        return localities.findTop8BySectorNameIgnoreCaseOrderByCityAsc(input.trim()).stream()
                .filter(locality -> CityRegistry.isCitySupported(locality.getCity()))
                .map(locality -> toOption(locality, "different_city")).toList();
    }

    @Transactional(readOnly = true)
    public Locality requireMatchingLocality(String city, Long localityId) {
        if (city == null || !CityRegistry.isCitySupported(city) || localityId == null) {
            throw new IllegalArgumentException("Choose a supported city and locality");
        }
        Locality locality = localities.findById(localityId)
                .orElseThrow(() -> new IllegalArgumentException("Choose a canonical locality"));
        if (!locality.getCity().equalsIgnoreCase(city)) {
            throw new IllegalArgumentException("This locality belongs to " + locality.getCity() + ". Choose a matching city.");
        }
        return locality;
    }

    private Option toOption(Locality locality, String match) {
        return new Option(locality.getId(), locality.getCity(), locality.getSectorName(), match);
    }
}
