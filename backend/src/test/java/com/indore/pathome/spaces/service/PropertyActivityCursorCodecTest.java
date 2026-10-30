package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyActivityCursorCodecTest {
    private static final PropertyActivityCursorCodec CURSOR = new PropertyActivityCursorCodec();
    private static final PropertyActivityCursorCodec.Cursor VALID =
            new PropertyActivityCursorCodec.Cursor(1, 42L,
                    PropertyActivityOrdering.Source.PUBLICATION,
                    Instant.parse("2026-10-24T08:00:00.123456Z"),
                    101L, 101L, 87L, 20, 1);

    @Test
    void cursorRoundTripsMicrosecondsAndFrozenCutoffsLosslessly() {
        String encoded = CURSOR.encode(VALID);
        assertEquals(VALID, CURSOR.decode(encoded, 42L, 20));
        assertEquals(87L, CURSOR.decode(encoded, 42L, 20).revisionCutoff());
    }

    @Test
    void malformedPaddingNonCanonicalAndOversizedCursorsAreRejectedBeforeBroadAllocation() {
        String valid = CURSOR.encode(VALID);
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(valid + "=", 42L, 20));
        assertThrows(IllegalArgumentException.class,
                () -> CURSOR.decode("A".repeat(PropertyActivityCursorCodec.MAX_ENCODED_LENGTH + 1), 42L, 20));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode("not/base64", 42L, 20));
    }

    @Test
    void duplicateUnknownOverflowWrongListingAndChangedLimitAreRejected() {
        String duplicate = encodeRaw("""
                {"version":1,"version":1,"listingId":42,"lastSource":"PUBLICATION","timestamp":"2026-10-24T08:00:00.123456Z","lastRowId":101,"publicationCutoff":101,"revisionCutoff":87,"limit":20,"page":1}
                """);
        String unknown = encodeRaw("""
                {"version":1,"listingId":42,"lastSource":"PUBLICATION","timestamp":"2026-10-24T08:00:00.123456Z","lastRowId":101,"publicationCutoff":101,"revisionCutoff":87,"limit":20,"page":1,"extra":true}
                """);
        String overflow = encodeRaw("""
                {"version":1,"listingId":42,"lastSource":"PUBLICATION","timestamp":"2026-10-24T08:00:00.123456Z","lastRowId":9223372036854775808,"publicationCutoff":101,"revisionCutoff":87,"limit":20,"page":1}
                """);
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(duplicate, 42L, 20));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(unknown, 42L, 20));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(overflow, 42L, 20));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(CURSOR.encode(VALID), 99L, 20));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.decode(CURSOR.encode(VALID), 42L, 10));
    }

    @Test
    void invalidPrecisionAndAnchorCutoffConsistencyAreRejected() {
        PropertyActivityCursorCodec.Cursor nanosecond = new PropertyActivityCursorCodec.Cursor(1, 42L,
                PropertyActivityOrdering.Source.PUBLICATION,
                Instant.parse("2026-10-24T08:00:00.123456789Z"),
                101L, 101L, 87L, 20, 1);
        PropertyActivityCursorCodec.Cursor beyondCutoff = new PropertyActivityCursorCodec.Cursor(1, 42L,
                PropertyActivityOrdering.Source.REVISION,
                Instant.parse("2026-10-24T08:00:00.123456Z"),
                88L, 101L, 87L, 20, 1);
        assertThrows(IllegalArgumentException.class, () -> CURSOR.encode(nanosecond));
        assertThrows(IllegalArgumentException.class, () -> CURSOR.encode(beyondCutoff));
    }

    @Test
    void cursorOrdinalsContinuePastTheLegacyHundredPageBoundary() {
        for (int page : new int[]{99, 100, 101, 102, Integer.MAX_VALUE}) {
            PropertyActivityCursorCodec.Cursor cursor = new PropertyActivityCursorCodec.Cursor(
                    1, 42L, PropertyActivityOrdering.Source.PUBLICATION,
                    Instant.parse("2026-10-24T08:00:00.123456Z"),
                    101L, 101L, 87L, 1, page);
            assertEquals(cursor, CURSOR.decode(CURSOR.encode(cursor), 42L, 1));
        }
    }

    @Test
    void malformedZeroNegativeAndOverflowedCursorOrdinalsAreRejected() {
        for (String page : new String[]{"0", "-1", "2147483648"}) {
            String raw = """
                    {"version":1,"listingId":42,"lastSource":"PUBLICATION","timestamp":"2026-10-24T08:00:00.123456Z","lastRowId":101,"publicationCutoff":101,"revisionCutoff":87,"limit":20,"page":%s}
                    """.formatted(page);
            assertThrows(IllegalArgumentException.class,
                    () -> CURSOR.decode(encodeRaw(raw), 42L, 20));
        }
    }

    private static String encodeRaw(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
