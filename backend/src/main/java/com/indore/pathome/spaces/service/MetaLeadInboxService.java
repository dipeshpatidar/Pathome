package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable Meta leadgen transport boundary. Authenticated events are recorded once and quarantined
 * until a separately approved source activation/processor exists. This service never calls Graph,
 * creates fabricated customer facts, assigns staff or sends messages.
 */
@Service
public class MetaLeadInboxService {
    private static final String PROVIDER = "META_LEADGEN";
    private static final String ACTIVATION_GATE = "SOURCE_ACTIVATION_NOT_APPROVED";

    private final LeadSourceEventRepository sourceEvents;
    private final DatabaseClock databaseClock;
    private final JdbcTemplate jdbc;

    public MetaLeadInboxService(LeadSourceEventRepository sourceEvents, DatabaseClock databaseClock,
                                JdbcTemplate jdbc) {
        this.sourceEvents = Objects.requireNonNull(sourceEvents);
        this.databaseClock = Objects.requireNonNull(databaseClock);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional
    public void acceptMetaLead(String pageId, String leadgenId, String formId, String payloadHash) {
        acceptMetaLead(pageId, leadgenId, formId, payloadHash, "LEGACY_ENVELOPE_V1");
    }

    @Transactional
    public void acceptMetaLead(String pageId, String leadgenId, String formId, String payloadHash,
                               String fingerprintVersion) {
        String accountId = bounded(pageId, 128, "Meta page identity");
        String externalId = bounded(leadgenId, 160, "Meta lead identity");
        String providerFormId = bounded(formId, 100, "Meta form identity");
        String hash = bounded(payloadHash, 64, "Payload hash").toLowerCase(Locale.ROOT);
        String version = bounded(fingerprintVersion, 24, "Fingerprint version");
        String lockKey = PROVIDER + ":" + accountId + ":" + externalId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, lockKey), resultSet -> null);

        Optional<LeadSourceEvent> existing = sourceEvents
                .findByProviderAndProviderAccountIdAndExternalEventId(PROVIDER, accountId, externalId);
        if (existing.isPresent()) {
            LeadSourceEvent event = existing.get();
            if ("LEGACY_ENVELOPE_V1".equals(event.getFingerprintVersion())) {
                // V52 compared whole-envelope hashes. Its rows cannot be safely reclassified as proof
                // of tampering, so retain them and surface an explicit reconciliation requirement.
                event.markForLegacyFingerprintReconciliation(databaseClock.now());
                sourceEvents.saveAndFlush(event);
                return;
            }
            if (!event.getPayloadHash().equals(hash)
                    || !version.equals(event.getFingerprintVersion())) {
                throw new VisitOperationsConflictException(
                        "This source event was already recorded with different content");
            }
            return;
        }

        Instant now = databaseClock.now();
        LeadSourceEvent event = new LeadSourceEvent(UUID.randomUUID(), PROVIDER, accountId, externalId,
                LeadSourceEventKind.META_LEADGEN, hash, version, now,
                LeadSourceEventState.QUARANTINED, ACTIVATION_GATE);
        event.setProviderFormId(providerFormId);
        sourceEvents.saveAndFlush(event);
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is missing or invalid");
        }
        return value.trim();
    }
}
