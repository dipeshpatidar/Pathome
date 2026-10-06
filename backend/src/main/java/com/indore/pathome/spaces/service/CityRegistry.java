package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authoritative, data-driven registry for supported and known cities.
 * Prioritizes sub-millisecond execution with O(1) concurrent sets.
 * No city names need to be hardcoded in parser regular expressions.
 */
public final class CityRegistry {
    private static final Logger log = LoggerFactory.getLogger(CityRegistry.class);

    private static final Set<String> INITIAL_SUPPORTED_CITIES = Set.of("indore", "bhopal", "pune");
    private static final AtomicReference<Set<String>> SUPPORTED_CITIES =
            new AtomicReference<>(INITIAL_SUPPORTED_CITIES);
    private static final Set<String> SUPPORTED_CITIES_VIEW = Collections.unmodifiableSet(new AbstractSet<>() {
        @Override
        public Iterator<String> iterator() {
            return SUPPORTED_CITIES.get().iterator();
        }

        @Override
        public int size() {
            return SUPPORTED_CITIES.get().size();
        }

        @Override
        public boolean contains(Object value) {
            return SUPPORTED_CITIES.get().contains(value);
        }
    });
    private static final Set<String> KNOWN_METRO_CITIES = ConcurrentHashMap.newKeySet();

    static {
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
            SUPPORTED_CITIES.updateAndGet(current -> {
                Set<String> updated = new LinkedHashSet<>(current);
                updated.add(normalized);
                return Collections.unmodifiableSet(updated);
            });
            KNOWN_METRO_CITIES.add(normalized);
            log.info("Registered supported city in dynamic registry: {}", normalized);
        }
    }

    /** Replaces the startup compatibility snapshot from active canonical display names. */
    static void replaceSupportedCities(Collection<String> activeCityNames) {
        Set<String> activeNames = new LinkedHashSet<>();
        if (activeCityNames != null) {
            for (String cityName : activeCityNames) {
                if (cityName != null && !cityName.isBlank()) {
                    String normalized = cityName.strip().toLowerCase(Locale.ROOT);
                    activeNames.add(normalized);
                    KNOWN_METRO_CITIES.add(normalized);
                }
            }
        }
        SUPPORTED_CITIES.set(Collections.unmodifiableSet(activeNames));
    }

    /**
     * Supported cities are strictly authoritative and defined by Pathome's supported city registry.
     * They must never be dynamically derived from locality records, property addresses, or unvalidated database strings.
     */
    public static void syncFromDatabase(LocalityRepository localityRepository, ListingRepository listingRepository) {
        // Supported cities are strictly defined by Pathome's canonical registry.
        // We never derive supported cities from locality records, property listings, or search terms.
    }

    public static boolean isCitySupported(String cityName) {
        if (cityName == null || cityName.isBlank()) return false;
        return SUPPORTED_CITIES.get().contains(cityName.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isKnownCity(String cityName) {
        if (cityName == null || cityName.isBlank()) return false;
        String lower = cityName.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_CITIES.get().contains(lower) || KNOWN_METRO_CITIES.contains(lower);
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
        return SUPPORTED_CITIES_VIEW;
    }

    public static Set<String> getKnownCities() {
        return Collections.unmodifiableSet(KNOWN_METRO_CITIES);
    }
}
