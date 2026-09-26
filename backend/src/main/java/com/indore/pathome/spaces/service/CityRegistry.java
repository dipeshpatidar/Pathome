package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authoritative, data-driven registry for supported and known cities.
 * Prioritizes sub-millisecond execution with O(1) concurrent sets.
 * No city names need to be hardcoded in parser regular expressions.
 */
public final class CityRegistry {
    private static final Logger log = LoggerFactory.getLogger(CityRegistry.class);

    private static final Set<String> SUPPORTED_CITIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> KNOWN_METRO_CITIES = ConcurrentHashMap.newKeySet();

    static {
        // Default core operational cities
        SUPPORTED_CITIES.add("indore");
        SUPPORTED_CITIES.add("bhopal");
        SUPPORTED_CITIES.add("pune");

        // Standard metro cities for truthful unsupported-city recognition
        Collections.addAll(KNOWN_METRO_CITIES,
                "indore", "bhopal", "pune", "mumbai", "delhi", "bangalore", "bengaluru",
                "hyderabad", "chennai", "kolkata", "ahmedabad", "jaipur", "surat",
                "lucknow", "chandigarh", "goa", "dewas", "ujjain", "gwalior",
                "jabalpur", "noida", "gurgaon"
        );
    }

    private CityRegistry() {}

    /**
     * Registers a new supported city dynamically from database or administrative sources.
     */
    public static void registerSupportedCity(String cityName) {
        if (cityName != null && !cityName.isBlank()) {
            String normalized = cityName.trim().toLowerCase(Locale.ROOT);
            SUPPORTED_CITIES.add(normalized);
            KNOWN_METRO_CITIES.add(normalized);
            log.info("Registered supported city in dynamic registry: {}", normalized);
        }
    }

    /**
     * Synchronizes supported cities from PostgreSQL repositories at startup or runtime.
     */
    public static void syncFromDatabase(LocalityRepository localityRepository, ListingRepository listingRepository) {
        if (localityRepository != null) {
            try {
                for (String city : localityRepository.findDistinctCities()) {
                    if (city != null && !city.isBlank()) {
                        registerSupportedCity(city);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to sync cities from localityRepository: {}", ex.getMessage());
            }
        }
    }

    public static boolean isCitySupported(String cityName) {
        if (cityName == null || cityName.isBlank()) return false;
        return SUPPORTED_CITIES.contains(cityName.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isKnownCity(String cityName) {
        if (cityName == null || cityName.isBlank()) return false;
        String lower = cityName.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_CITIES.contains(lower) || KNOWN_METRO_CITIES.contains(lower);
    }

    public static String matchCityName(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String lower = candidate.trim().toLowerCase(Locale.ROOT);
        if (isKnownCity(lower)) {
            return canonicalCityName(lower);
        }
        return null;
    }

    public static String canonicalCityName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("bengaluru")) return "Bangalore";
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1).toLowerCase(Locale.ROOT);
    }

    public static Set<String> getSupportedCities() {
        return Collections.unmodifiableSet(SUPPORTED_CITIES);
    }

    public static Set<String> getKnownCities() {
        return Collections.unmodifiableSet(KNOWN_METRO_CITIES);
    }
}
