package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisitOtpCryptoTest {
    @Test
    void sixDigitOtpDigestIsBoundToSessionTenantGroundExecutiveAndGeneration() {
        VisitExecutionProperties properties = new VisitExecutionProperties();
        properties.getOtp().setHmacSecret("primary-secret-with-at-least-32-bytes");
        VisitOtpCrypto crypto = new VisitOtpCrypto(properties);
        String code = crypto.newCode();
        byte[] digest = crypto.digest(10L, 20L, 30L, 2, code, "primary");

        assertEquals(6, code.length());
        assertTrue(code.matches("[0-9]{6}"));
        assertTrue(crypto.matches(digest, 10L, 20L, 30L, 2, code, "primary"));
        assertFalse(crypto.matches(digest, 11L, 20L, 30L, 2, code, "primary"));
        assertFalse(crypto.matches(digest, 10L, 21L, 30L, 2, code, "primary"));
        assertFalse(crypto.matches(digest, 10L, 20L, 31L, 2, code, "primary"));
        assertFalse(crypto.matches(digest, 10L, 20L, 30L, 3, code, "primary"));
    }

    @Test
    void previousHmacKeyRemainsUsableForUnexpiredChallengesDuringRotation() {
        VisitExecutionProperties properties = new VisitExecutionProperties();
        properties.getOtp().setHmacSecret("replacement-secret-with-at-least-32-bytes");
        properties.getOtp().setKeyId("rotated");
        properties.getOtp().setPreviousKeyId("primary");
        properties.getOtp().setPreviousHmacSecret("previous-secret-with-at-least-32-bytes");
        VisitOtpCrypto crypto = new VisitOtpCrypto(properties);
        byte[] digest = crypto.digest(10L, 20L, 30L, 1, "000123", "primary");

        assertTrue(crypto.matches(digest, 10L, 20L, 30L, 1, "000123", "primary"));
        assertThrows(IllegalStateException.class,
                () -> crypto.digest(10L, 20L, 30L, 1, "000123", "unknown"));
    }
}
