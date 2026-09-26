package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;

/**
 * Repository-backed public location vocabulary.
 * Priority: EXACT canonical match > PREFIX match > ALIAS resolution > FUZZY trigram fallback.
 */
public final class RentalLocationResolver {
    private static final double MIN_DB_SIMILARITY = 0.34;
    private static final double MIN_RESOLUTION_SIMILARITY = 0.43;
    private static final double MIN_WINNER_GAP = 0.08;
    private final ListingRepository listings;
    private final SearchLearningService searchLearningService;
    private final LocalityRepository localityRepository;

    public enum CitySource {
        EXPLICIT_QUERY,
        LOCALITY_RESOLUTION,
        SELECTED_UI,
        DEFAULT
    }

    public record Match(String city, String locality, long count, double confidence, String method) {}
    public record Resolution(String city, String locality, double confidence, String method) {}
    public record CityResolution(String city, CitySource source, boolean supported) {}
    public record LocalityCityMatch(String city, boolean isUnique, List<String> matchingCities, String canonicalLocality) {
        public LocalityCityMatch(String city, boolean isUnique) {
            this(city, isUnique, city != null ? List.of(city) : List.of(), null);
        }
    }

    private static final Map<String, String> CANONICAL_LOCALITY_CITY_MAP = Map.ofEntries(
            Map.entry("baner", "Pune"),
            Map.entry("wakad", "Pune"),
            Map.entry("hinjewadi", "Pune"),
            Map.entry("kharadi", "Pune"),
            Map.entry("viman nagar", "Pune"),
            Map.entry("mp nagar", "Bhopal"),
            Map.entry("arera colony", "Bhopal"),
            Map.entry("kolar road", "Bhopal"),
            Map.entry("hoshangabad road", "Bhopal"),
            Map.entry("vijay nagar", "Indore"),
            Map.entry("nanda nagar", "Indore"),
            Map.entry("bhawarkua", "Indore"),
            Map.entry("nipania", "Indore"),
            Map.entry("ab road", "Indore"),
            Map.entry("super corridor", "Indore"),
            Map.entry("lig circle", "Indore"),
            Map.entry("old palasia", "Indore"),
            Map.entry("rau", "Indore"),
            Map.entry("mahalaxmi nagar", "Indore"),
            Map.entry("scheme 78", "Indore"),
            Map.entry("scheme 140", "Indore"),
            Map.entry("saket nagar", "Indore"),
            Map.entry("tilak nagar", "Indore"),
            Map.entry("kalani nagar", "Indore")
    );

    public RentalLocationResolver(ListingRepository listings) {
        this(listings, null, null);
    }

    public RentalLocationResolver(ListingRepository listings, SearchLearningService searchLearningService) {
        this(listings, searchLearningService, null);
    }

    public RentalLocationResolver(ListingRepository listings, SearchLearningService searchLearningService, LocalityRepository localityRepository) {
        this.listings = listings;
        this.searchLearningService = searchLearningService;
        this.localityRepository = localityRepository;
    }

    public CityResolution resolveCity(RentalSearchQuery query, String selectedCityFilter) {
        // 1. Explicit high-confidence city from typed query
        if (query.explicitCity() != null && !query.explicitCity().isBlank()) {
            boolean supported = isCitySupported(query.explicitCity());
            return new CityResolution(query.explicitCity(), CitySource.EXPLICIT_QUERY, supported);
        }

        // 2. Authoritative/high-confidence locality -> owning city
        if (query.location() != null && !query.location().isBlank()) {
            LocalityCityMatch owning = resolveAuthoritativeLocalityCity(query.location());
            if (owning != null && owning.isUnique() && owning.city() != null) {
                boolean supported = isCitySupported(owning.city());
                return new CityResolution(owning.city(), CitySource.LOCALITY_RESOLUTION, supported);
            }
        }

        // 3. Selected UI city
        if (selectedCityFilter != null && !selectedCityFilter.isBlank()) {
            String normalized = CityRegistry.canonicalCityName(selectedCityFilter);
            boolean supported = isCitySupported(normalized);
            return new CityResolution(normalized, CitySource.SELECTED_UI, supported);
        }

        // 4. Configured neutral/default behavior
        return new CityResolution("Indore", CitySource.DEFAULT, true);
    }

    public LocalityCityMatch resolveAuthoritativeLocalityCity(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String normalized = RentalSearchQuery.normalizeLocation(candidate);

        java.util.LinkedHashSet<String> distinctCities = new java.util.LinkedHashSet<>();
        String canonicalLocality = null;

        // 1. Check in-memory canonical discovery locality map
        String canonicalCity = CANONICAL_LOCALITY_CITY_MAP.get(normalized);
        if (canonicalCity != null) {
            distinctCities.add(canonicalCity);
            canonicalLocality = toTitleCase(normalized);
        }

        // 2. Check PostgreSQL localityRepository
        if (localityRepository != null) {
            List<Locality> matching = localityRepository.findAllBySectorNameIgnoreCase(normalized);
            if (matching != null && !matching.isEmpty()) {
                for (Locality loc : matching) {
                    if (loc.getCity() != null && !loc.getCity().isBlank()) {
                        distinctCities.add(CityRegistry.canonicalCityName(loc.getCity()));
                        if (canonicalLocality == null) {
                            canonicalLocality = loc.getSectorName();
                        }
                    }
                }
            }
        }

        // 3. Check active listings in DB across all cities
        if (listings != null) {
            List<ListingRepository.LocalitySuggestionRow> active = listings.findPublicRentalLocalitySuggestions(
                    "", "", "", "", null, null, normalized, PageRequest.of(0, 5));
            for (ListingRepository.LocalitySuggestionRow row : active) {
                if (normalized.equals(RentalSearchQuery.normalizeLocation(row.getLocality()))) {
                    distinctCities.add(CityRegistry.canonicalCityName(row.getCity()));
                    if (canonicalLocality == null) {
                        canonicalLocality = row.getLocality();
                    }
                }
            }
        }

        if (distinctCities.isEmpty()) {
            return null;
        }

        List<String> cityList = new ArrayList<>(distinctCities);
        if (cityList.size() == 1) {
            return new LocalityCityMatch(cityList.get(0), true, cityList, canonicalLocality != null ? canonicalLocality : toTitleCase(normalized));
        } else {
            return new LocalityCityMatch(null, false, cityList, canonicalLocality != null ? canonicalLocality : toTitleCase(normalized));
        }
    }

    public String resolveCanonicalLocalityName(String candidate, String cityKey) {
        if (candidate == null || candidate.isBlank()) return null;
        String normalized = RentalSearchQuery.normalizeLocation(candidate);

        // Check canonical map
        for (Map.Entry<String, String> entry : CANONICAL_LOCALITY_CITY_MAP.entrySet()) {
            if (entry.getKey().equals(normalized)) {
                if (cityKey == null || cityKey.isBlank() || entry.getValue().equalsIgnoreCase(cityKey)) {
                    return toTitleCase(entry.getKey());
                }
            }
        }
        // Check locality repository
        if (localityRepository != null) {
            if (cityKey != null && !cityKey.isBlank()) {
                var opt = localityRepository.findByCityIgnoreCaseAndSectorNameIgnoreCase(cityKey, normalized);
                if (opt.isPresent()) return opt.get().getSectorName();
            }
            var list = localityRepository.findAllBySectorNameIgnoreCase(normalized);
            if (!list.isEmpty()) {
                for (Locality loc : list) {
                    if (cityKey == null || cityKey.isBlank() || loc.getCity().equalsIgnoreCase(cityKey)) {
                        return loc.getSectorName();
                    }
                }
                return list.get(0).getSectorName();
            }
        }
        // Check listings repository
        if (listings != null) {
            var rows = listings.findPublicRentalLocalitySuggestions(
                    cityKey != null ? cityKey : "", "", "", "", null, null, normalized, PageRequest.of(0, 5));
            for (var row : rows) {
                if (normalized.equals(RentalSearchQuery.normalizeLocation(row.getLocality()))) {
                    return row.getLocality();
                }
            }
        }
        return null;
    }

    private static final Map<String, String> CANONICAL_LOCALITY_DISPLAY_MAP = Map.ofEntries(
            Map.entry("mp nagar", "MP Nagar"),
            Map.entry("ab road", "AB Road"),
            Map.entry("lig circle", "LIG Circle"),
            Map.entry("scheme 78", "Scheme 78"),
            Map.entry("scheme 140", "Scheme 140")
    );

    public static String toTitleCase(String text) {
        if (text == null || text.isBlank()) return text;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        String known = CANONICAL_LOCALITY_DISPLAY_MAP.get(lower);
        if (known != null) return known;
        StringBuilder sb = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!word.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                if (word.equalsIgnoreCase("mp") || word.equalsIgnoreCase("ab") || word.equalsIgnoreCase("lig")) {
                    sb.append(word.toUpperCase(Locale.ROOT));
                } else {
                    sb.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) sb.append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return sb.toString();
    }

    public static boolean isCitySupported(String city) {
        return CityRegistry.isCitySupported(city);
    }

    public List<Match> suggestions(RentalSearchQuery query, String cityKey, int limit) {
        String candidate = RentalSearchQuery.normalizeLocation(query.location());
        String bhk = query.bhkKey() == null ? "" : query.bhkKey();
        String type = query.propertyType() == null ? "" : query.propertyType().name();
        String furnishing = query.furnishingKey() == null ? "" : query.furnishingKey();
        List<Match> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. EXACT / PREFIX matches on authoritative canonical data
        List<ListingRepository.LocalitySuggestionRow> prefix = listings.findPublicRentalLocalitySuggestions(
                cityKey, bhk, type, furnishing, query.minRent(), query.maxRent(), candidate,
                PageRequest.of(0, limit));
        for (ListingRepository.LocalitySuggestionRow row : prefix) {
            String key = key(row.getCity(), row.getLocality());
            boolean isExact = candidate.equals(RentalSearchQuery.normalizeLocation(row.getLocality()));
            if (seen.add(key)) result.add(new Match(row.getCity(), row.getLocality(), row.getResultCount(),
                    isExact ? 1.0 : 0.92,
                    candidate.isEmpty() ? "structured" : (isExact ? "exact" : "prefix")));
        }

        // 2. ALIAS resolution: if canonical exact/prefix didn't match, resolve via active learned alias
        if (candidate.length() >= 2 && result.isEmpty() && searchLearningService != null) {
            String aliasLocality = searchLearningService.resolveLocalityAlias(candidate, cityKey);
            if (aliasLocality != null && !aliasLocality.isBlank()) {
                String normalizedAlias = RentalSearchQuery.normalizeLocation(aliasLocality);
                List<ListingRepository.LocalitySuggestionRow> aliasMatches = listings.findPublicRentalLocalitySuggestions(
                        cityKey, bhk, type, furnishing, query.minRent(), query.maxRent(),
                        normalizedAlias, PageRequest.of(0, limit));
                for (ListingRepository.LocalitySuggestionRow row : aliasMatches) {
                    if (result.size() >= limit) break;
                    String key = key(row.getCity(), row.getLocality());
                    if (seen.add(key)) {
                        result.add(new Match(row.getCity(), row.getLocality(), row.getResultCount(), 0.95, "alias"));
                    }
                }
            }
        }

        // 3. FUZZY trigram fallback: bounded similarity search when neither exact/prefix nor alias matched
        if (candidate.length() >= 4 && result.isEmpty()) {
            List<ListingRepository.LocalitySuggestionRow> fuzzy = listings.findPublicRentalFuzzyLocalities(
                    cityKey, bhk, type, furnishing, query.minRent(), query.maxRent(), candidate,
                    MIN_DB_SIMILARITY, PageRequest.of(0, limit));
            for (ListingRepository.LocalitySuggestionRow row : fuzzy) {
                if (result.size() >= limit) break;
                String key = key(row.getCity(), row.getLocality());
                double confidence = row.getSimilarity() == null ? 0 : row.getSimilarity();
                if (confidence >= MIN_RESOLUTION_SIMILARITY && seen.add(key)) {
                    result.add(new Match(row.getCity(), row.getLocality(), row.getResultCount(), confidence, "trigram"));
                }
            }
        }
        return result;
    }

    /** Resolve typo text only when the top public locality is strong and unambiguous. */
    public Resolution resolveForDiscovery(RentalSearchQuery query, String cityKey) {
        String candidate = RentalSearchQuery.normalizeLocation(query.location());
        if (candidate.isEmpty()) return new Resolution(null, null, 1.0, "structured");
        List<Match> matches = suggestions(query, cityKey, 3);
        if (matches.isEmpty()) return new Resolution(null, null, 0, "unresolved");
        Match top = matches.get(0);
        if (top.method().equals("exact") || top.method().equals("prefix")) {
            return new Resolution(top.city(), top.locality(), top.confidence(), top.method());
        }
        if (top.method().equals("alias")) {
            return new Resolution(top.city(), top.locality(), top.confidence(), "alias");
        }
        if (top.confidence() < MIN_RESOLUTION_SIMILARITY) {
            return new Resolution(null, null, top.confidence(), "unresolved");
        }
        if (matches.size() > 1 && matches.get(1).confidence() > top.confidence() - MIN_WINNER_GAP) {
            return new Resolution(null, null, top.confidence(), "ambiguous");
        }
        return new Resolution(top.city(), top.locality(), top.confidence(), top.method());
    }

    private static String key(String city, String locality) {
        return RentalSearchQuery.normalizeLocation(city) + "|" + RentalSearchQuery.normalizeLocation(locality);
    }
}
