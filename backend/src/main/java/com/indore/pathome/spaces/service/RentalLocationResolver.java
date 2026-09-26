package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    public record Match(String city, String locality, long count, double confidence, String method) {}
    public record Resolution(String city, String locality, double confidence, String method) {}

    public RentalLocationResolver(ListingRepository listings) {
        this(listings, null);
    }

    public RentalLocationResolver(ListingRepository listings, SearchLearningService searchLearningService) {
        this.listings = listings;
        this.searchLearningService = searchLearningService;
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
