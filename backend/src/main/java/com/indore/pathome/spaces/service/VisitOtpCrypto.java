package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

@Component
public class VisitOtpCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final VisitExecutionProperties properties;

    public VisitOtpCrypto(VisitExecutionProperties properties) { this.properties = properties; }

    public String newCode() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }

    public byte[] digest(Long sessionId, Long tenantId, Long groundExecutiveId, int generation,
                         String code, String keyId) {
        String secret = properties.getOtp().secretForKey(keyId);
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("Visit start-code service is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = sessionId + ":" + tenantId + ":" + groundExecutiveId + ":" + generation + ":" + code;
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Visit start-code service is not configured", ex);
        }
    }

    public boolean matches(byte[] storedDigest, Long sessionId, Long tenantId, Long groundExecutiveId,
                           int generation, String code, String keyId) {
        return MessageDigest.isEqual(storedDigest,
                digest(sessionId, tenantId, groundExecutiveId, generation, code, keyId));
    }
}
