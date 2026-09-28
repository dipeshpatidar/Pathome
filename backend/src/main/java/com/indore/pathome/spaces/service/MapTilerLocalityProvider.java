package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.service.ExternalLocalityProvider.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
public class MapTilerLocalityProvider implements ExternalLocalityProvider {
    private static final Logger log = LoggerFactory.getLogger(MapTilerLocalityProvider.class);
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Semaphore inFlight = new Semaphore(6);
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private long windowStart = System.currentTimeMillis();
    private int requestsInWindow;
    private record CacheEntry(long expiresAt, List<Result> results) {}

    public MapTilerLocalityProvider(@Value("${MAPTILER_API_KEY:}") String apiKey, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mapper = mapper;
    }

    @Override
    public List<Result> search(String city, String query) {
        if (apiKey.isBlank()) return List.of();
        String cacheKey = city.toLowerCase(java.util.Locale.ROOT) + ':' + query.toLowerCase(java.util.Locale.ROOT);
        synchronized (cache) {
            CacheEntry cached = cache.get(cacheKey);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.results();
            if (System.currentTimeMillis() - windowStart >= 60_000) {
                windowStart = System.currentTimeMillis();
                requestsInWindow = 0;
            }
            if (requestsInWindow >= 60) return List.of();
            requestsInWindow++;
        }
        if (!inFlight.tryAcquire()) return List.of();
        try {
            List<String> attempts = new ArrayList<>(2);
            attempts.add(query + ", " + city);
            String collapsed = query.replaceAll("\\s+", "");
            if (!collapsed.equalsIgnoreCase(query) && collapsed.length() >= 3) {
                attempts.add(collapsed + ", " + city);
            }
            List<Result> result = new ArrayList<>();
            for (String attempt : attempts) {
                result = fetchFeatures(attempt, city, query);
                if (!result.isEmpty()) break;
            }
            synchronized (cache) {
                if (cache.size() >= 256) cache.clear();
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis() + 30_000, List.copyOf(result)));
            }
            return result;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Optional locality provider unavailable: {}", ex.getClass().getSimpleName());
            return List.of();
        } finally {
            inFlight.release();
        }
    }

    private List<Result> fetchFeatures(String targetQuery, String city, String userQuery) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString("https://api.maptiler.com/geocoding/{query}.json")
                .queryParam("key", apiKey).queryParam("country", "in")
                .queryParam("types", "locality,neighbourhood,place,road,address,subregion,municipal_district")
                .queryParam("limit", 6).queryParam("autocomplete", true)
                .buildAndExpand(targetQuery).encode().toUri();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200) return List.of();
            body = stream.readNBytes(250_001);
        }
        if (body.length > 250_000) return List.of();
        JsonNode features = mapper.readTree(new String(body, StandardCharsets.UTF_8)).path("features");
        if (!features.isArray()) return List.of();
        List<Result> list = new ArrayList<>();
        for (JsonNode feature : features) {
            if (list.size() == 6) break;
            String rawName = feature.path("text").asText("").trim();
            String id = feature.path("id").asText("").trim();
            if (rawName.isBlank() || rawName.length() > 120 || rawName.chars().anyMatch(Character::isISOControl)
                    || id.isBlank() || id.length() > 160 || id.chars().anyMatch(Character::isISOControl)
                    || !insideCity(feature, city)
                    || !isRelevant(rawName, userQuery, city)) continue;
            list.add(new Result(formatName(rawName), city, "MAPTILER", id));
        }
        return list;
    }

    static boolean isRelevant(String name, String query, String city) {
        if (name.equalsIgnoreCase(city) || name.equalsIgnoreCase(city + " City")) return false;
        String nName = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String nQuery = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (nQuery.length() < 2) return false;
        return nName.contains(nQuery) || nQuery.contains(nName);
    }

    static String formatName(String name) {
        if (name == null || name.isBlank()) return "";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    static boolean insideCity(JsonNode feature, String city) {
        if (!"in".equalsIgnoreCase(feature.path("properties").path("country_code").asText(""))) return false;
        String canonicalCity = city.trim().toLowerCase(Locale.ROOT);
        String cityWithCity = canonicalCity + " city";
        for (JsonNode context : feature.path("context")) {
            String id = context.path("id").asText("").toLowerCase(Locale.ROOT);
            String text = context.path("text").asText("").trim().toLowerCase(Locale.ROOT);
            if (id.startsWith("municipality.") || id.startsWith("subregion.") || id.startsWith("county.") || id.startsWith("place.")) {
                if (text.equals(canonicalCity) || text.equals(cityWithCity) || text.startsWith(canonicalCity + " ")) {
                    return true;
                }
            }
        }
        return false;
    }
}
