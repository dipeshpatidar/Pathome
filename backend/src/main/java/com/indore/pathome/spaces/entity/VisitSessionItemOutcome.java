package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "visit_session_item_outcomes")
public class VisitSessionItemOutcome {
    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false)
    private VisitSessionOutcomeReport report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", referencedColumnName = "id", insertable = false, updatable = false)
    private VisitSessionItem sessionItem;

    @Column(name = "position_snapshot", nullable = false)
    private Integer positionSnapshot;

    @Column(name = "listing_id_snapshot", nullable = false)
    private Long listingIdSnapshot;

    @Column(name = "title_snapshot", nullable = false, columnDefinition = "TEXT")
    private String titleSnapshot;

    @Column(name = "address_snapshot", nullable = false, columnDefinition = "TEXT")
    private String addressSnapshot;

    @Column(name = "city_snapshot", nullable = false, columnDefinition = "TEXT")
    private String citySnapshot;

    @Column(name = "sector_snapshot", nullable = false, columnDefinition = "TEXT")
    private String sectorSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_state", nullable = false, length = 16)
    private VisitSessionItemOutcomeState outcomeState = VisitSessionItemOutcomeState.UNRECORDED;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private VisitSessionItemSkipReason skipReason;

    @Column(name = "private_note", length = 500)
    private String privateNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id")
    private User recordedBy;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VisitSessionItemOutcome() {}

    public VisitSessionItemOutcome(VisitSessionOutcomeReport report, VisitSessionItem sessionItem) {
        setReport(report);
        setSessionItem(sessionItem);
        this.positionSnapshot = sessionItem.getPosition();
        this.listingIdSnapshot = sessionItem.getListing().getId();
        this.titleSnapshot = sessionItem.getListing().getTitle();
        this.addressSnapshot = sessionItem.getListing().getAddress();
        this.citySnapshot = sessionItem.getListing().getCity();
        this.sectorSnapshot = sessionItem.getListing().getSector();
        this.outcomeState = VisitSessionItemOutcomeState.UNRECORDED;
    }

    @PrePersist
    protected void onCreate() {
        validate();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        validate();
        updatedAt = Instant.now();
    }

    private void validate() {
        if (itemId == null || sessionId == null || positionSnapshot == null || positionSnapshot < 1
                || listingIdSnapshot == null || titleSnapshot == null || addressSnapshot == null
                || citySnapshot == null || sectorSnapshot == null || outcomeState == null)
            throw new IllegalStateException("Outcome scope and property snapshot are required");
        boolean recorded = outcomeState != VisitSessionItemOutcomeState.UNRECORDED;
        if (recorded != (recordedBy != null) || recorded != (recordedAt != null))
            throw new IllegalStateException("Recorded outcomes require actor and timestamp");
        if (outcomeState == VisitSessionItemOutcomeState.SKIPPED && skipReason == null)
            throw new IllegalStateException("Skipped outcomes require a reason");
        if (outcomeState != VisitSessionItemOutcomeState.SKIPPED && skipReason != null)
            throw new IllegalStateException("Only skipped outcomes may have a skip reason");
        if (outcomeState == VisitSessionItemOutcomeState.UNRECORDED && privateNote != null)
            throw new IllegalStateException("Unrecorded outcomes cannot contain a note");
        if (skipReason == VisitSessionItemSkipReason.OTHER && (privateNote == null || privateNote.isBlank()))
            throw new IllegalStateException("OTHER skip outcomes require a private note");
        if (privateNote != null && privateNote.length() > 500)
            throw new IllegalStateException("Private outcome note is too long");
    }

    public void record(VisitSessionItemOutcomeState state, VisitSessionItemSkipReason reason,
            String note, User actor, Instant at) {
        this.outcomeState = state;
        this.skipReason = reason;
        this.privateNote = note;
        this.recordedBy = actor;
        this.recordedAt = at;
    }

    public void updatePrivateNote(String note) {
        this.privateNote = note;
    }

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public VisitSessionOutcomeReport getReport() { return report; }
    public void setReport(VisitSessionOutcomeReport report) { this.report = report; this.sessionId = report == null ? null : report.getSessionId(); }
    public VisitSessionItem getSessionItem() { return sessionItem; }
    public void setSessionItem(VisitSessionItem sessionItem) { this.sessionItem = sessionItem; this.itemId = sessionItem == null ? null : sessionItem.getId(); }
    public Integer getPositionSnapshot() { return positionSnapshot; }
    public Long getListingIdSnapshot() { return listingIdSnapshot; }
    public String getTitleSnapshot() { return titleSnapshot; }
    public String getAddressSnapshot() { return addressSnapshot; }
    public String getCitySnapshot() { return citySnapshot; }
    public String getSectorSnapshot() { return sectorSnapshot; }
    public VisitSessionItemOutcomeState getOutcomeState() { return outcomeState; }
    public void setOutcomeState(VisitSessionItemOutcomeState outcomeState) { this.outcomeState = outcomeState; }
    public VisitSessionItemSkipReason getSkipReason() { return skipReason; }
    public void setSkipReason(VisitSessionItemSkipReason skipReason) { this.skipReason = skipReason; }
    public String getPrivateNote() { return privateNote; }
    public void setPrivateNote(String privateNote) { this.privateNote = privateNote; }
    public User getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
