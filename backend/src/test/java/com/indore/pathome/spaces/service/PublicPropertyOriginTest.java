package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicPropertyOriginTest {
    @Test
    void canonicalOriginUsesOnlyConfiguredHttpsOrigin() {
        assertEquals("https://pathome.test",
                PublicPropertyOrigin.canonical("https://PATHOME.test/"));
        assertEquals("https://pathome.test:8443",
                PublicPropertyOrigin.canonical("https://pathome.test:8443"));
    }

    @Test
    void unsafeOrAmbiguousOriginsFailClosed() {
        for (String invalid : new String[]{
                "", "http://pathome.test", "https://user:secret@pathome.test",
                "https://pathome.test/path", "https://pathome.test?x=1",
                "https://pathome.test#fragment", "https://pathome.test:0"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PublicPropertyOrigin.canonical(invalid), invalid);
        }
    }
}
