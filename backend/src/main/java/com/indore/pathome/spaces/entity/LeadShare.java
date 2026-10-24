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
@Table(name = "lead_shares")
public class LeadShare {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shortlist_id", nullable = false)
    private LeadShortlist shortlist;

    @Column(name = "shortlist_version", nullable = false)
    private Long shortlistVersion;

    @Column(name = "contact_preference_revision", nullable = false)
    private Long contactPreferenceRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LeadShareMode mode;

    @Column(name = "public_links_json", nullable = false, columnDefinition = "text")
    private String publicLinksJson;

    @Column(name = "preview_text", nullable = false, columnDefinition = "text")
    private String previewText;

    @Column(name = "masked_destination", length = 40)
    private String maskedDestination;

    @Column(name = "recorded_at", nullable = false, columnDefinition = "timestamptz")
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    protected LeadShare() {}

    public LeadShare(UUID id, LeadRoutingQueue lead, LeadShortlist shortlist, Long shortlistVersion,
                     Long contactPreferenceRevision, LeadShareMode mode, String publicLinksJson,
                     String previewText, String maskedDestination, Instant recordedAt, User createdBy) {
        this.id = id;
        this.lead = lead;
        this.shortlist = shortlist;
        this.shortlistVersion = shortlistVersion;
        this.contactPreferenceRevision = contactPreferenceRevision;
        this.mode = mode;
        this.publicLinksJson = publicLinksJson;
        this.previewText = previewText;
        this.maskedDestination = maskedDestination;
        this.recordedAt = recordedAt;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public LeadShortlist getShortlist() { return shortlist; }
    public Long getShortlistVersion() { return shortlistVersion; }
    public Long getContactPreferenceRevision() { return contactPreferenceRevision; }
    public LeadShareMode getMode() { return mode; }
    public String getPublicLinksJson() { return publicLinksJson; }
    public String getPreviewText() { return previewText; }
    public String getMaskedDestination() { return maskedDestination; }
    public Instant getRecordedAt() { return recordedAt; }
}
