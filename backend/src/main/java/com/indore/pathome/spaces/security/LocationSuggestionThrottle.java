package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.exception.LocationLookupRateLimitException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Small per-instance limit for the read-only typeahead endpoint. */
@Component
public class LocationSuggestionThrottle {
    private static final int MAX_KEYS = 2048;
    private static final int MAX_REQUESTS_PER_MINUTE = 180;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long startedAt, int count) {}

    public synchronized void check(String key) {
        long now = System.currentTimeMillis();
        Window previous = windows.get(key);
        if (previous != null && now - previous.startedAt() >= 60_000) previous = null;
        if (previous == null) {
            if (windows.size() >= MAX_KEYS) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= 60_000);
            if (windows.size() >= MAX_KEYS) throw new LocationLookupRateLimitException();
            windows.put(key, new Window(now, 1));
        } else {
            if (previous.count() >= MAX_REQUESTS_PER_MINUTE) throw new LocationLookupRateLimitException();
            windows.put(key, new Window(previous.startedAt(), previous.count() + 1));
        }
    }
}
