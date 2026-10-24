package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lead_source_events")
public class LeadSourceEvent {
    @Id
    private UUID id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_account_id", nullable = false, length = 128)
    private String providerAccountId;

    @Column(name = "external_event_id", nullable = false, length = 160)
    private String externalEventId;

    @Column(name = "provider_form_id", length = 100)
    private String providerFormId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, length = 32)
    private LeadSourceEventKind eventKind;

    @Column(name = "source_channel", length = 64)
    private String sourceChannel;

    @Column(name = "campaign_ref", length = 200)
    private String campaignRef;

    @Column(name = "referral_ref", length = 200)
    private String referralRef;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "fingerprint_version", nullable = false, length = 24)
    private String fingerprintVersion;

    @Column(name = "extracted_payload_ciphertext", columnDefinition = "text")
    private String extractedPayloadCiphertext;

    @Column(name = "extracted_payload_iv", length = 32)
    private String extractedPayloadIv;

    @Column(name = "extraction_key_version", length = 24)
    private String extractionKeyVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolution_lead_id")
    private LeadRoutingQueue resolutionLead;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 24)
    private LeadSourceEventState processingState;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false, columnDefinition = "timestamptz")
    private Instant availableAt;

    @Column(name = "lease_until", columnDefinition = "timestamptz")
    private Instant leaseUntil;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    protected LeadSourceEvent() {}

    public LeadSourceEvent(UUID id, String provider, String providerAccountId, String externalEventId,
                           LeadSourceEventKind eventKind, String payloadHash, Instant receivedAt,
                           LeadSourceEventState state, String errorCode) {
        this(id, provider, providerAccountId, externalEventId, eventKind, payloadHash,
                "LEGACY_ENVELOPE_V1", receivedAt, state, errorCode);
    }

    public LeadSourceEvent(UUID id, String provider, String providerAccountId, String externalEventId,
                           LeadSourceEventKind eventKind, String payloadHash, String fingerprintVersion,
                           Instant receivedAt, LeadSourceEventState state, String errorCode) {
        this.id = id;
        this.provider = provider;
        this.providerAccountId = providerAccountId;
        this.externalEventId = externalEventId;
        this.eventKind = eventKind;
        this.payloadHash = payloadHash;
        this.fingerprintVersion = fingerprintVersion;
        this.processingState = state;
        this.availableAt = receivedAt;
        this.receivedAt = receivedAt;
        this.updatedAt = receivedAt;
        this.errorCode = errorCode;
    }

    public void setSourceAttribution(String sourceChannel, String campaignRef, String referralRef) {
        this.sourceChannel = sourceChannel;
        this.campaignRef = campaignRef;
        this.referralRef = referralRef;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getProviderAccountId() { return providerAccountId; }
    public String getExternalEventId() { return externalEventId; }
    public String getProviderFormId() { return providerFormId; }
    public void setProviderFormId(String providerFormId) { this.providerFormId = providerFormId; }
    public String getPayloadHash() { return payloadHash; }
    public String getFingerprintVersion() { return fingerprintVersion; }
    public LeadSourceEventKind getEventKind() { return eventKind; }
    public LeadSourceEventState getProcessingState() { return processingState; }
    public String getErrorCode() { return errorCode; }
    public LeadRoutingQueue getResolutionLead() { return resolutionLead; }
    public boolean hasProtectedExtractedPayload() { return extractedPayloadCiphertext != null; }
    public String getSourceChannel() { return sourceChannel; }
    public String getCampaignRef() { return campaignRef; }
    public String getReferralRef() { return referralRef; }
    public Instant getReceivedAt() { return receivedAt; }
    public LeadRoutingQueue getLead() { return lead; }
    public void attachTo(LeadRoutingQueue lead) { this.lead = lead; }
    public void resolveAgainst(LeadRoutingQueue lead) { this.resolutionLead = lead; }
    public void setProcessingState(LeadSourceEventState processingState) { this.processingState = processingState; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public void incrementAttempts() { this.attempts++; }
    public int getAttempts() { return attempts; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public UUID getLeaseToken() { return leaseToken; }
    public void lease(UUID token, Instant until) {
        this.leaseToken = token;
        this.leaseUntil = until;
    }
    public void clearLease() {
        this.leaseToken = null;
        this.leaseUntil = null;
    }
    public void setExtractedPayload(String ciphertext, String iv, String keyVersion) {
        this.extractedPayloadCiphertext = ciphertext;
        this.extractedPayloadIv = iv;
        this.extractionKeyVersion = keyVersion;
    }
    public void touch(Instant now) { this.updatedAt = now; }
    public void markForLegacyFingerprintReconciliation(Instant now) {
        this.errorCode = "LEGACY_FINGERPRINT_RECONCILIATION_REQUIRED";
        this.updatedAt = now;
    }
}
