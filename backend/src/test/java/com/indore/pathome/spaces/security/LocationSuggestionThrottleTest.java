package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.exception.LocationLookupRateLimitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocationSuggestionThrottleTest {
    @Test
    void limitsRepeatedQueriesWithoutBlockingAnotherClient() {
        var throttle = new LocationSuggestionThrottle();
        for (int i = 0; i < 180; i++) throttle.check("guest:one");
        assertThrows(LocationLookupRateLimitException.class, () -> throttle.check("guest:one"));
        assertDoesNotThrow(() -> throttle.check("guest:two"));
    }
}
