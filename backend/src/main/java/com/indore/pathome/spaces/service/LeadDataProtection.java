package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * AES-256-GCM protection for CRM destinations and source payloads. Missing or malformed key material
 * fails closed: plaintext storage is never used as a fallback.
 */
@Service
public class LeadDataProtection {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    private final byte[] key;
    private final String keyId;

    public LeadDataProtection(
            @Value("${pathome.crm.contact-encryption-key:}") String encodedKey,
            @Value("${pathome.crm.contact-encryption-key-id:}") String configuredKeyId) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            this.keyId = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            if (decoded.length != 32) {
                throw new LeadDataProtectionUnavailableException(
                        "CRM contact protection is unavailable because its key is invalid");
            }
            this.key = decoded.clone();
            if (configuredKeyId == null || configuredKeyId.isBlank()
                    || configuredKeyId.length() > 24) {
                throw new LeadDataProtectionUnavailableException(
                        "CRM contact protection is unavailable because its key ID is invalid");
            }
            this.keyId = configuredKeyId.trim();
        } catch (IllegalArgumentException exception) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection is unavailable because its key is invalid", exception);
        }
    }

    public ProtectedValue protect(String plaintext) {
        requireConfigured();
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Protected CRM value is required");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new ProtectedValue(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv), keyId, fingerprint(plaintext));
        } catch (GeneralSecurityException exception) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection could not encrypt the value", exception);
        }
    }

    public String reveal(String ciphertext, String encodedIv, String storedKeyId) {
        requireConfigured();
        if (!keyId.equals(storedKeyId)) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection cannot read this key version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(encodedIv)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection could not read the protected value", exception);
        }
    }

    public String fingerprint(String normalizedValue) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    ("CRM_DESTINATION:" + normalizedValue).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection could not fingerprint the value", exception);
        }
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = NON_DIGIT.matcher(value).replaceAll("");
        if (digits.length() <= 4) return "****";
        return "*".repeat(Math.max(4, digits.length() - 4))
                + digits.substring(digits.length() - 4);
    }

    private void requireConfigured() {
        if (key == null) {
            throw new LeadDataProtectionUnavailableException(
                    "CRM contact protection is not configured for this environment");
        }
    }

    public record ProtectedValue(String ciphertext, String iv, String keyId, String fingerprint) {}
}
