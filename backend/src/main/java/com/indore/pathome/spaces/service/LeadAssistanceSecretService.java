package com.indore.pathome.spaces.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Cryptographic material for enquiry proof and opaque same-origin Visit handoffs. */
@Service
public class LeadAssistanceSecretService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");

    private final LeadDataProtection protection;

    public LeadAssistanceSecretService(LeadDataProtection protection) {
        this.protection = protection;
    }

    public String sixDigitCode() {
        return formatSixDigit(RANDOM.nextInt(1_000_000));
    }

    public String opaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String phoneContextDigest(Long leadId, UUID sourceEventId, String purpose,
                                     String numberFingerprint, Long contactPhoneRevision,
                                     int generation) {
        return protection.keyedDigest("LEAD_PHONE_CONTEXT", String.join("|",
                String.valueOf(leadId), sourceEventId.toString(), purpose,
                numberFingerprint, String.valueOf(contactPhoneRevision),
                String.valueOf(generation)));
    }

    public String phoneVerificationDigest(String contextDigest, String code) {
        requireCodeShape(code);
        return protection.keyedDigest("LEAD_PHONE_CODE", contextDigest + "|" + code);
    }

    public String phoneConfirmationTokenHash(String token) {
        requireToken(token);
        return protection.keyedDigest("LEAD_PHONE_CONFIRMATION_LINK", token);
    }

    public String visitProposalTokenHash(String token) {
        requireToken(token);
        return protection.keyedDigest("LEAD_VISIT_PROPOSAL_LINK", token);
    }

    public String visitContinuationTokenHash(String token) {
        requireToken(token);
        return protection.keyedDigest("LEAD_VISIT_CONTINUATION", token);
    }

    public boolean codeMatches(String expectedDigest, String contextDigest, String submittedCode) {
        if (submittedCode == null || !SIX_DIGITS.matcher(submittedCode).matches()
                || expectedDigest == null || contextDigest == null) return false;
        String actual = phoneVerificationDigest(contextDigest, submittedCode);
        return MessageDigest.isEqual(expectedDigest.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean looksLikeOpaqueToken(String value) {
        if (value == null || value.length() < 40 || value.length() > 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_';
            if (!valid) return false;
        }
        return true;
    }

    private static void requireToken(String token) {
        if (!looksLikeOpaqueToken(token)) {
            throw new IllegalArgumentException("The secure link is invalid or incomplete");
        }
    }

    private static void requireCodeShape(String code) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            throw new IllegalArgumentException("Enter the six-digit enquiry code");
        }
    }

    static String formatSixDigit(int value) {
        if (value < 0 || value > 999_999) {
            throw new IllegalArgumentException("Enquiry code value is out of range");
        }
        return String.format(Locale.ROOT, "%06d", value);
    }
}
