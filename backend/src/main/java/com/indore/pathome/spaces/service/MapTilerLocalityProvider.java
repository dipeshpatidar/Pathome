package com.indore.pathome.spaces.service;

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
            URI uri = UriComponentsBuilder.fromUriString("https://api.maptiler.com/geocoding/{query}.json")
                    .queryParam("key", apiKey).queryParam("country", "in")
                    .queryParam("types", "locality,neighbourhood,place,road")
                    .queryParam("limit", 6).queryParam("autocomplete", true)
                    .buildAndExpand(query + ", " + city).encode().toUri();
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
            List<Result> result = new ArrayList<>();
            for (JsonNode feature : features) {
                if (result.size() == 6) break;
                String name = feature.path("text").asText("").trim();
                String id = feature.path("id").asText("").trim();
                if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)
                        || id.isBlank() || id.length() > 160 || id.chars().anyMatch(Character::isISOControl)
                        || !insideCity(feature, city)) continue;
                result.add(new Result(name, city, "MAPTILER", id));
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

    static boolean insideCity(JsonNode feature, String city) {
        if (!"in".equalsIgnoreCase(feature.path("properties").path("country_code").asText(""))) return false;
        for (JsonNode context : feature.path("context")) {
            String id = context.path("id").asText("");
            if (id.startsWith("municipality.")
                    && city.equalsIgnoreCase(context.path("text").asText(""))) return true;
        }
        return false;
    }
}
