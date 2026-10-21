package com.indore.pathome.spaces.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.controller.MetaLeadWebhookController;
import com.indore.pathome.spaces.service.MetaLeadInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MetaWebhookSignatureTest {
    private static final String SECRET = "test-only-meta-app-secret";
    private static final String PAGE_ID = "page-101";
    private static final String FORM_ID = "form-202";
    private static final String BODY = """
            {"object":"page","entry":[{"id":"page-101","changes":[{"field":"leadgen","value":{
              "leadgen_id":"lead-303","form_id":"form-202"}}]}]}
            """;

    @Test
    void validRawBodyIsAcceptedOnceByTheDurableInboxBoundary() throws Exception {
        MetaLeadInboxService inbox = mock(MetaLeadInboxService.class);
        MetaLeadWebhookController controller = controller(inbox);

        ResponseEntity<String> response = controller.handleIncomingMetaLead(
                signature(BODY), BODY.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatusCode().value());
        verify(inbox).acceptMetaLead("page-101", "lead-303", "form-202", sha256(BODY));
    }

    @Test
    void missingOrChangedSignatureFailsBeforeTheInboxIsCalled() throws Exception {
        MetaLeadInboxService inbox = mock(MetaLeadInboxService.class);
        MetaLeadWebhookController controller = controller(inbox);

        assertEquals(403, controller.handleIncomingMetaLead(
                null, BODY.getBytes(StandardCharsets.UTF_8)).getStatusCode().value());
        assertEquals(403, controller.handleIncomingMetaLead(
                signature(BODY), (BODY + " ").getBytes(StandardCharsets.UTF_8)).getStatusCode().value());
        verify(inbox, never()).acceptMetaLead(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void signaturesAreBoundToTheRawBytesRatherThanParsedJson() throws Exception {
        MetaLeadWebhookController controller = controller(mock(MetaLeadInboxService.class));
        String reformatted = BODY.replace("\n", "\n ");

        assertEquals(403, controller.handleIncomingMetaLead(
                signature(BODY), reformatted.getBytes(StandardCharsets.UTF_8)).getStatusCode().value());
    }

    @Test
    void missingConfigurationFailsClosedRatherThanUsingPlaceholderSecrets() throws Exception {
        MetaLeadWebhookController controller = new MetaLeadWebhookController(
                mock(MetaLeadInboxService.class), new ObjectMapper(), "", "", "", "");

        assertEquals(503, controller.handleIncomingMetaLead(
                "sha256=deadbeef", BODY.getBytes(StandardCharsets.UTF_8)).getStatusCode().value());
    }

    @Test
    void aSignedUnrelatedPageEventIsIgnoredWithoutCreatingDemand() throws Exception {
        MetaLeadInboxService inbox = mock(MetaLeadInboxService.class);
        MetaLeadWebhookController controller = controller(inbox);
        String unrelated = BODY.replace("page-101", "another-page");

        ResponseEntity<String> response = controller.handleIncomingMetaLead(
                signature(unrelated), unrelated.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("EVENT_IGNORED", response.getBody());
        verify(inbox, never()).acceptMetaLead(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aLeadgenEventFromTheWrongFormIsRejectedBeforePersistence() throws Exception {
        MetaLeadInboxService inbox = mock(MetaLeadInboxService.class);
        MetaLeadWebhookController controller = controller(inbox);
        String wrongForm = BODY.replace("form-202", "foreign-form");

        assertEquals(403, controller.handleIncomingMetaLead(
                signature(wrongForm), wrongForm.getBytes(StandardCharsets.UTF_8)).getStatusCode().value());
        verify(inbox, never()).acceptMetaLead(anyString(), anyString(), anyString(), anyString());
    }

    private static MetaLeadWebhookController controller(MetaLeadInboxService inbox) {
        return new MetaLeadWebhookController(inbox, new ObjectMapper(), SECRET, "verify-token", PAGE_ID, FORM_ID);
    }

    private static String signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String body) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
    }
}
