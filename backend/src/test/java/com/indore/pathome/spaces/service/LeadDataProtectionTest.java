package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LeadDataProtectionTest {
    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void destinationsAreEncryptedAndCanOnlyBeMaskedOrRevealedWithTheConfiguredKey() {
        LeadDataProtection protection = new LeadDataProtection(KEY, "primary");
        LeadDataProtection.ProtectedValue value = protection.protect("+919876543210");

        assertNotEquals("+919876543210", value.ciphertext());
        assertEquals("+919876543210",
                protection.reveal(value.ciphertext(), value.iv(), value.keyId()));
        assertEquals("********3210", LeadDataProtection.mask("+919876543210"));
        assertEquals(64, value.fingerprint().length());
    }

    @Test
    void missingKeyFailsClosedInsteadOfStoringPlaintext() {
        LeadDataProtection protection = new LeadDataProtection("", "primary");
        assertThrows(LeadDataProtectionUnavailableException.class,
                () -> protection.protect("+919876543210"));
    }

    @Test
    void malformedOrInconsistentKeyIdentityFailsClosed() {
        assertThrows(LeadDataProtectionUnavailableException.class,
                () -> new LeadDataProtection("not-base64", "primary"));
        assertThrows(LeadDataProtectionUnavailableException.class,
                () -> new LeadDataProtection(KEY, "key-id-that-is-too-long-for-contract"));

        LeadDataProtection producer = new LeadDataProtection(KEY, "primary");
        LeadDataProtection.ProtectedValue stored = producer.protect("+919876543210");
        LeadDataProtection wrongIdentity = new LeadDataProtection(KEY, "replacement");
        assertThrows(LeadDataProtectionUnavailableException.class,
                () -> wrongIdentity.reveal(stored.ciphertext(), stored.iv(), stored.keyId()));
    }

    @Test
    void theSameRunOwnedKeyDecryptsAfterServiceReconstruction() {
        LeadDataProtection beforeRestart = new LeadDataProtection(KEY, "primary");
        LeadDataProtection.ProtectedValue stored = beforeRestart.protect("+919876543210");

        LeadDataProtection afterRestart = new LeadDataProtection(KEY, "primary");
        assertEquals("+919876543210",
                afterRestart.reveal(stored.ciphertext(), stored.iv(), stored.keyId()));
    }

    @Test
    void theConfiguredRunOwnedIdentityDecryptsAcrossReconstruction() {
        String configuredKey = System.getenv("PATHOME_CRM_CONTACT_ENCRYPTION_KEY");
        String configuredKeyId = System.getenv("PATHOME_CRM_CONTACT_ENCRYPTION_KEY_ID");
        assumeTrue(configuredKey != null && configuredKeyId != null,
                "run-owned encryption identity is supplied only by the guarded harness");

        LeadDataProtection beforeRestart =
                new LeadDataProtection(configuredKey, configuredKeyId);
        LeadDataProtection.ProtectedValue stored = beforeRestart.protect("+919876543210");
        assertFalse(stored.ciphertext().contains("9876543210"));

        LeadDataProtection afterRestart =
                new LeadDataProtection(configuredKey, configuredKeyId);
        assertEquals("+919876543210",
                afterRestart.reveal(stored.ciphertext(), stored.iv(), stored.keyId()));
    }
}
