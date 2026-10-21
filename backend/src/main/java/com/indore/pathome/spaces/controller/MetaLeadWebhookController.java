package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.service.MetaLeadInboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Meta leadgen webhook transport. POST authenticity is decided from the raw bytes before JSON is
 * parsed. Accepted events enter a durable replay-safe inbox but remain quarantined because live source
 * activation is a separate owner gate.
 */
@RestController
@RequestMapping("/api/v1/webhooks/meta-leads")
public class MetaLeadWebhookController {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_CHANGES = 100;

    private final MetaLeadInboxService inbox;
    private final ObjectMapper objectMapper;
    private final String appSecret;
    private final String verifyToken;
    private final String pageId;
    private final String formId;

    public MetaLeadWebhookController(
            MetaLeadInboxService inbox,
            ObjectMapper objectMapper,
            @Value("${meta.webhook.app-secret:}") String appSecret,
            @Value("${meta.webhook.verify-token:}") String verifyToken,
            @Value("${meta.webhook.page-id:}") String pageId,
            @Value("${meta.webhook.form-id:}") String formId) {
        this.inbox = Objects.requireNonNull(inbox);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
        this.pageId = pageId;
        this.formId = formId;
    }

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        if (blank(appSecret) || blank(verifyToken) || blank(pageId) || blank(formId)) {
            return unavailable();
        }
        if ("subscribe".equals(mode) && constantTimeEquals(verifyToken, token)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body("Verification failed");
    }

    @PostMapping
    public ResponseEntity<String> handleIncomingMetaLead(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) {
        if (blank(appSecret) || blank(verifyToken) || blank(pageId) || blank(formId)) {
            return unavailable();
        }
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES
                || !validSignature(signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                    .body("Invalid webhook signature");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook body is malformed");
        }
        JsonNode entries = payload.path("entry");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Webhook entries are missing or exceed the bound");
        }

        int accepted = 0;
        for (JsonNode entry : entries) {
            if (!pageId.equals(entry.path("id").asText())) {
                // Signed payloads can contain unrelated Page activity. Ignore it rather than treating
                // it as customer demand or failing a valid batch that also contains our leadgen event.
                continue;
            }
            JsonNode changes = entry.path("changes");
            if (!changes.isArray() || changes.size() > MAX_CHANGES) {
                throw new IllegalArgumentException("Webhook changes are missing or exceed the bound");
            }
            for (JsonNode change : changes) {
                JsonNode value = change.path("value");
                String leadgenId = value.path("leadgen_id").asText("");
                if (leadgenId.isBlank()) continue;
                if (!"leadgen".equals(change.path("field").asText("leadgen"))
                        || !formId.equals(value.path("form_id").asText())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                            .body("Webhook form identity does not match this application");
                }
                inbox.acceptMetaLead(pageId, leadgenId, formId, sha256(rawBody));
                accepted++;
            }
        }
        if (accepted == 0) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("EVENT_IGNORED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("EVENT_RECEIVED");
    }

    private boolean validSignature(String signature, byte[] rawBody) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return constantTimeEquals(expected, signature);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String sha256(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body("Meta leadgen intake is not configured");
    }
}
