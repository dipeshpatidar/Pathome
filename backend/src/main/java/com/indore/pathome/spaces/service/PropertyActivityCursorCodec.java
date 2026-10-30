package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Year;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Strict, bounded cursor codec. Base64URL is transport encoding only and grants no authority.
 */
@Component
public class PropertyActivityCursorCodec {
    public static final int MAX_ENCODED_LENGTH = 2_048;
    private static final int MAX_DECODED_BYTES = 1_536;
    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record Cursor(
            int version,
            Long listingId,
            PropertyActivityOrdering.Source lastSource,
            Instant timestamp,
            Long lastRowId,
            long publicationCutoff,
            long revisionCutoff,
            int limit,
            int page
    ) {}

    public String encode(Cursor cursor) {
        validate(cursor);
        try {
            byte[] json = mapper.writeValueAsBytes(cursor);
            if (json.length > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException("Activity cursor is too large");
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Activity cursor could not be encoded", ex);
        }
    }

    public Cursor decode(String encoded, Long listingId, int limit) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH
                || !BASE64URL.matcher(encoded).matches()) {
            throw new IllegalArgumentException("Activity cursor is malformed");
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            if (json.length > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException("Activity cursor is too large");
            }
            Cursor cursor = mapper.readValue(json, Cursor.class);
            validate(cursor);
            byte[] canonical = mapper.writeValueAsBytes(cursor);
            if (!Arrays.equals(canonical, json)) {
                throw new IllegalArgumentException("Activity cursor is not canonical");
            }
            if (!cursor.listingId().equals(listingId) || cursor.limit() != limit) {
                throw new IllegalArgumentException("Activity cursor does not belong to this request");
            }
            return cursor;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Activity cursor is malformed", ex);
        }
    }

    private static void validate(Cursor cursor) {
        if (cursor == null || cursor.version() != 1 || cursor.listingId() == null
                || cursor.listingId() <= 0 || cursor.lastSource() == null
                || cursor.timestamp() == null || cursor.lastRowId() == null
                || cursor.lastRowId() <= 0 || cursor.publicationCutoff() < 0
                || cursor.revisionCutoff() < 0 || cursor.limit() < 1 || cursor.limit() > 50
                || cursor.page() < 1) {
            throw new IllegalArgumentException("Activity cursor fields are invalid");
        }
        int year = cursor.timestamp().atZone(java.time.ZoneOffset.UTC).getYear();
        if (year < 1 || year > Year.MAX_VALUE || cursor.timestamp().getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Activity cursor timestamp precision is invalid");
        }
        long cutoff = cursor.lastSource() == PropertyActivityOrdering.Source.PUBLICATION
                ? cursor.publicationCutoff() : cursor.revisionCutoff();
        if (cursor.lastRowId() > cutoff) {
            throw new IllegalArgumentException("Activity cursor anchor exceeds its source cutoff");
        }
    }
}
