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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lead_phone_challenges")
public class LeadPhoneChallenge {
    public static final int MAX_FAILED_ATTEMPTS = 5;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_event_id", nullable = false)
    private LeadSourceEvent sourceEvent;

    @Column(nullable = false, length = 32)
    private String purpose;

    @Column(name = "number_fingerprint", nullable = false, length = 64)
    private String numberFingerprint;

    @Column(name = "masked_destination", nullable = false, length = 32)
    private String maskedDestination;

    @Column(name = "contact_phone_revision", nullable = false)
    private Long contactPhoneRevision;

    @Column(nullable = false)
    private Integer generation;

    @Column(name = "context_digest", nullable = false, length = 64)
    private String contextDigest;

    @Column(name = "verification_digest", nullable = false, length = 64)
    private String verificationDigest;

    @Column(name = "confirmation_token_hash", nullable = false, length = 64)
    private String confirmationTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeadPhoneChallengeState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 16)
    private LeadPhoneDeliveryState deliveryState;

    @Column(name = "delivery_provider", nullable = false, length = 24)
    private String deliveryProvider;

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_method", length = 16)
    private LeadPhoneEntryMethod entryMethod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadPhoneChallenge() {}

    public LeadPhoneChallenge(UUID id, LeadRoutingQueue lead, LeadSourceEvent sourceEvent,
                              String purpose, String numberFingerprint, Long contactPhoneRevision,
                              String maskedDestination, int generation, String contextDigest,
                              String verificationDigest,
                              String confirmationTokenHash, LeadPhoneDeliveryState deliveryState,
                              String deliveryProvider, User createdBy, Instant createdAt,
                              Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.lead = Objects.requireNonNull(lead);
        this.sourceEvent = Objects.requireNonNull(sourceEvent);
        this.purpose = Objects.requireNonNull(purpose);
        this.numberFingerprint = Objects.requireNonNull(numberFingerprint);
        this.maskedDestination = Objects.requireNonNull(maskedDestination);
        this.contactPhoneRevision = Objects.requireNonNull(contactPhoneRevision);
        this.generation = generation;
        this.contextDigest = Objects.requireNonNull(contextDigest);
        this.verificationDigest = Objects.requireNonNull(verificationDigest);
        this.confirmationTokenHash = Objects.requireNonNull(confirmationTokenHash);
        this.state = LeadPhoneChallengeState.PENDING;
        this.deliveryState = Objects.requireNonNull(deliveryState);
        this.deliveryProvider = Objects.requireNonNull(deliveryProvider);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public void cancel(Instant at) {
        if (state != LeadPhoneChallengeState.PENDING) return;
        state = LeadPhoneChallengeState.CANCELLED;
        cancelledAt = at;
    }

    public void expire(Instant at) {
        if (state != LeadPhoneChallengeState.PENDING) return;
        state = LeadPhoneChallengeState.EXPIRED;
        cancelledAt = at;
    }

    public void recordFailedAttempt() {
        if (state != LeadPhoneChallengeState.PENDING) return;
        failedAttempts += 1;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) state = LeadPhoneChallengeState.EXHAUSTED;
    }

    public void verify(LeadPhoneEntryMethod method, Instant at) {
        if (state != LeadPhoneChallengeState.PENDING) {
            throw new IllegalStateException("Only a pending enquiry challenge can be verified");
        }
        state = LeadPhoneChallengeState.VERIFIED;
        entryMethod = Objects.requireNonNull(method);
        verifiedAt = Objects.requireNonNull(at);
    }

    public void updateDelivery(LeadPhoneDeliveryState state) {
        LeadPhoneDeliveryState next = Objects.requireNonNull(state);
        if (this.deliveryState == LeadPhoneDeliveryState.DELIVERED
                && next != LeadPhoneDeliveryState.DELIVERED) return;
        if (this.deliveryState == LeadPhoneDeliveryState.ACCEPTED
                && (next == LeadPhoneDeliveryState.FAILED
                    || next == LeadPhoneDeliveryState.UNKNOWN)) return;
        this.deliveryState = next;
    }

    public UUID getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public LeadSourceEvent getSourceEvent() { return sourceEvent; }
    public String getPurpose() { return purpose; }
    public String getNumberFingerprint() { return numberFingerprint; }
    public String getMaskedDestination() { return maskedDestination; }
    public Long getContactPhoneRevision() { return contactPhoneRevision; }
    public Integer getGeneration() { return generation; }
    public String getContextDigest() { return contextDigest; }
    public String getVerificationDigest() { return verificationDigest; }
    public String getConfirmationTokenHash() { return confirmationTokenHash; }
    public LeadPhoneChallengeState getState() { return state; }
    public LeadPhoneDeliveryState getDeliveryState() { return deliveryState; }
    public String getDeliveryProvider() { return deliveryProvider; }
    public Integer getFailedAttempts() { return failedAttempts; }
    public LeadPhoneEntryMethod getEntryMethod() { return entryMethod; }
    public User getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getVersion() { return version; }
}
