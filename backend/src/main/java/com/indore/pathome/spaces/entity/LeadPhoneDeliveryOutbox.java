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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lead_phone_delivery_outbox")
public class LeadPhoneDeliveryOutbox {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "challenge_id", nullable = false)
    private LeadPhoneChallenge challenge;

    @Column(name = "destination_fingerprint", nullable = false, length = 64)
    private String destinationFingerprint;

    @Column(name = "payload_ciphertext", nullable = false, columnDefinition = "text")
    private String payloadCiphertext;

    @Column(name = "payload_iv", nullable = false, length = 32)
    private String payloadIv;

    @Column(name = "payload_key_version", nullable = false, length = 24)
    private String payloadKeyVersion;

    @Column(name = "provider_name", nullable = false, length = 24)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeadPhoneDeliveryState state;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "provider_message_id", length = 160)
    private String providerMessageId;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeadPhoneDeliveryOutbox() {}

    public LeadPhoneDeliveryOutbox(UUID id, LeadPhoneChallenge challenge,
                                   String destinationFingerprint, String payloadCiphertext,
                                   String payloadIv, String payloadKeyVersion, String providerName,
                                   Instant now) {
        this.id = Objects.requireNonNull(id);
        this.challenge = Objects.requireNonNull(challenge);
        this.destinationFingerprint = Objects.requireNonNull(destinationFingerprint);
        this.payloadCiphertext = Objects.requireNonNull(payloadCiphertext);
        this.payloadIv = Objects.requireNonNull(payloadIv);
        this.payloadKeyVersion = Objects.requireNonNull(payloadKeyVersion);
        this.providerName = Objects.requireNonNull(providerName);
        this.state = LeadPhoneDeliveryState.QUEUED;
        this.availableAt = Objects.requireNonNull(now);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void claim(UUID token, Instant leaseUntil, Instant now) {
        if (state != LeadPhoneDeliveryState.QUEUED || this.leaseUntil != null) {
            throw new IllegalStateException("Delivery attempt is not claimable");
        }
        this.leaseToken = Objects.requireNonNull(token);
        this.leaseUntil = Objects.requireNonNull(leaseUntil);
        this.state = LeadPhoneDeliveryState.DISPATCHING;
        this.attempts += 1;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void dispatchAccepted(LeadPhoneDeliveryState resultState, String providerMessageId,
                                 Instant now) {
        Objects.requireNonNull(resultState);
        if (state == LeadPhoneDeliveryState.DELIVERED
                && resultState != LeadPhoneDeliveryState.DELIVERED) {
            return;
        }
        if (state == LeadPhoneDeliveryState.ACCEPTED
                && (resultState == LeadPhoneDeliveryState.FAILED
                    || resultState == LeadPhoneDeliveryState.UNKNOWN)) {
            return;
        }
        // Provider state can arrive out of order. Preserve the strongest positive fact.
        if (state != LeadPhoneDeliveryState.DELIVERED
                && resultState == LeadPhoneDeliveryState.DELIVERED) {
            this.state = LeadPhoneDeliveryState.DELIVERED;
        } else if (state != LeadPhoneDeliveryState.ACCEPTED
                && state != LeadPhoneDeliveryState.DELIVERED) {
            this.state = resultState;
        }
        if (providerMessageId != null) this.providerMessageId = providerMessageId;
        this.leaseToken = null;
        this.leaseUntil = null;
        purgePayload(now);
    }

    public void markSuppressed(String errorCode, Instant now) {
        state = LeadPhoneDeliveryState.SUPPRESSED;
        lastErrorCode = errorCode;
        leaseToken = null;
        leaseUntil = null;
        purgePayload(now);
    }

    public void markUnknown(String errorCode, Instant now) {
        state = LeadPhoneDeliveryState.UNKNOWN;
        lastErrorCode = errorCode;
        leaseToken = null;
        leaseUntil = null;
        purgePayload(now);
    }

    /**
     * A delivery envelope is single-use. Terminal transport state keeps only metadata and retry
     * evidence; encrypted code/link material must not remain available for a later dispatch.
     */
    public void purgePayload(Instant now) {
        payloadCiphertext = "";
        payloadIv = "";
        payloadKeyVersion = "PURGED";
        updatedAt = Objects.requireNonNull(now);
    }

    public UUID getId() { return id; }
    public LeadPhoneChallenge getChallenge() { return challenge; }
    public String getDestinationFingerprint() { return destinationFingerprint; }
    public String getPayloadCiphertext() { return payloadCiphertext; }
    public String getPayloadIv() { return payloadIv; }
    public String getPayloadKeyVersion() { return payloadKeyVersion; }
    public boolean isPayloadPurged() { return payloadCiphertext.isEmpty() && payloadIv.isEmpty(); }
    public String getProviderName() { return providerName; }
    public LeadPhoneDeliveryState getState() { return state; }
    public Integer getAttempts() { return attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public UUID getLeaseToken() { return leaseToken; }
    public String getProviderMessageId() { return providerMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
