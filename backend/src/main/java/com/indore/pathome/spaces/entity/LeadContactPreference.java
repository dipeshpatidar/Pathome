package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "lead_contact_preferences", uniqueConstraints = {
        @UniqueConstraint(name = "uk_lead_contact_preference", columnNames = {"lead_id", "revision"})
})
public class LeadContactPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @Column(nullable = false)
    private Long revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LeadContactChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_choice", nullable = false, length = 16)
    private LeadDestinationChoice destinationChoice;

    @Column(name = "country_calling_code", length = 5)
    private String countryCallingCode;

    @Column(name = "destination_ciphertext", columnDefinition = "text")
    private String destinationCiphertext;

    @Column(name = "destination_iv", length = 32)
    private String destinationIv;

    @Column(name = "destination_key_version", length = 24)
    private String destinationKeyVersion;

    @Column(name = "destination_fingerprint", length = 64)
    private String destinationFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_status", nullable = false, length = 16)
    private LeadConsentStatus consentStatus;

    @Column(name = "consent_purpose", nullable = false, length = 32)
    private String consentPurpose;

    @Column(name = "consent_wording_version", length = 64)
    private String consentWordingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_method", length = 24)
    private LeadConsentEvidenceMethod evidenceMethod;

    @Column(name = "recorded_at", nullable = false, columnDefinition = "timestamptz")
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "captured_by_user_id", nullable = false)
    private User capturedBy;

    protected LeadContactPreference() {}

    public LeadContactPreference(LeadRoutingQueue lead, Long revision, LeadContactChannel channel,
                                 LeadDestinationChoice destinationChoice, String countryCallingCode,
                                 String ciphertext, String iv, String keyVersion, String fingerprint,
                                 LeadConsentStatus consentStatus, String wordingVersion,
                                 LeadConsentEvidenceMethod evidenceMethod, Instant recordedAt, User capturedBy) {
        this.lead = lead;
        this.revision = revision;
        this.channel = channel;
        this.destinationChoice = destinationChoice;
        this.countryCallingCode = countryCallingCode;
        this.destinationCiphertext = ciphertext;
        this.destinationIv = iv;
        this.destinationKeyVersion = keyVersion;
        this.destinationFingerprint = fingerprint;
        this.consentStatus = consentStatus;
        this.consentPurpose = "PROPERTY_SHARING";
        this.consentWordingVersion = wordingVersion;
        this.evidenceMethod = evidenceMethod;
        this.recordedAt = recordedAt;
        this.capturedBy = capturedBy;
    }

    public Long getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public Long getRevision() { return revision; }
    public LeadContactChannel getChannel() { return channel; }
    public LeadDestinationChoice getDestinationChoice() { return destinationChoice; }
    public String getCountryCallingCode() { return countryCallingCode; }
    public String getDestinationCiphertext() { return destinationCiphertext; }
    public String getDestinationIv() { return destinationIv; }
    public String getDestinationKeyVersion() { return destinationKeyVersion; }
    public String getDestinationFingerprint() { return destinationFingerprint; }
    public LeadConsentStatus getConsentStatus() { return consentStatus; }
    public String getConsentPurpose() { return consentPurpose; }
    public String getConsentWordingVersion() { return consentWordingVersion; }
    public LeadConsentEvidenceMethod getEvidenceMethod() { return evidenceMethod; }
    public Instant getRecordedAt() { return recordedAt; }
}
