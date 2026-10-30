package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadAssistanceSecretServiceTest {
    @Test
    void sixDigitCodesPreserveLeadingZerosAndRejectOutOfRangeValues() {
        assertEquals("000000", LeadAssistanceSecretService.formatSixDigit(0));
        assertEquals("000007", LeadAssistanceSecretService.formatSixDigit(7));
        assertEquals("999999", LeadAssistanceSecretService.formatSixDigit(999999));
        assertThrows(IllegalArgumentException.class,
                () -> LeadAssistanceSecretService.formatSixDigit(-1));
        assertThrows(IllegalArgumentException.class,
                () -> LeadAssistanceSecretService.formatSixDigit(1_000_000));
    }

    @Test
    void opaqueTokensAreCryptographicallyShapedAndPurposeDigestsAreNamespaced() {
        LeadDataProtection protection = mock(LeadDataProtection.class);
        when(protection.keyedDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + ":"
                        + invocation.getArgument(1));
        LeadAssistanceSecretService secrets = new LeadAssistanceSecretService(protection);

        String token = secrets.opaqueToken();
        assertTrue(LeadAssistanceSecretService.looksLikeOpaqueToken(token));
        assertEquals("LEAD_PHONE_CONFIRMATION_LINK:" + token,
                secrets.phoneConfirmationTokenHash(token));
        assertEquals("LEAD_VISIT_CONTINUATION:" + token,
                secrets.visitContinuationTokenHash(token));

        UUID source = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String context = secrets.phoneContextDigest(9L, source,
                LeadPhoneVerificationService.PURPOSE, "fingerprint", 2L, 3);
        assertTrue(context.startsWith("LEAD_PHONE_CONTEXT:"));
        assertTrue(secrets.codeMatches(
                secrets.phoneVerificationDigest(context, "000123"), context, "000123"));
        assertThrows(IllegalArgumentException.class,
                () -> secrets.phoneVerificationDigest(context, "123"));
    }
}
