package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "visit_session_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_visit_session_item_listing", columnNames = {"session_id", "listing_id"}),
        @UniqueConstraint(name = "uk_visit_session_item_position", columnNames = {"session_id", "position"})
}, indexes = {
        @Index(name = "idx_visit_session_item_listing", columnList = "listing_id, session_id")
})
public class VisitSessionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private VisitSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "position", nullable = false)
    private Integer position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_request_id")
    private PropertyVisitRequest sourceRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "derived_from_request_id")
    private PropertyVisitRequest derivedFromRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 32)
    private VisitSessionItemOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", nullable = false, length = 24)
    private VisitSessionItemConfirmationStatus confirmationStatus = VisitSessionItemConfirmationStatus.PENDING;

    @Column(name = "availability_confirmed_at")
    private Instant availabilityConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_user_id")
    private User confirmedBy;

    @Column(name = "lessor_confirmation_reference", length = 255)
    private String lessorConfirmationReference;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removal_reason", length = 500)
    private String removalReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        validate(true);
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        validate(false);
        updatedAt = Instant.now();
    }

    private void validate(boolean newItem) {
        if (session == null) throw new IllegalStateException("Visit session is required");
        if (listing == null) throw new IllegalStateException("Listing is required");
        if (position == null || position < 1) throw new IllegalStateException("Position must be positive");
        if (confirmationStatus == null) throw new IllegalStateException("Confirmation status is required");
        validateProvenance(newItem);
        boolean hasConfirmationMetadata = availabilityConfirmedAt != null && confirmedBy != null;
        if (confirmationStatus == VisitSessionItemConfirmationStatus.CONFIRMED && !hasConfirmationMetadata)
            throw new IllegalStateException("Confirmed items require confirmation actor and timestamp");
        if ((availabilityConfirmedAt == null) != (confirmedBy == null))
            throw new IllegalStateException("Confirmation actor and timestamp must be provided together");
    }

    private void validateProvenance(boolean newItem) {
        if (sourceRequest == null && derivedFromRequest == null && origin == null) {
            if (newItem) throw new IllegalStateException("New visit session items require provenance");
            return;
        }

        boolean directRequest = sourceRequest != null
                && derivedFromRequest == null
                && origin == VisitSessionItemOrigin.TENANT_REQUESTED;
        boolean derivedRequest = sourceRequest == null
                && derivedFromRequest != null
                && (origin == VisitSessionItemOrigin.OE_ADDED
                    || origin == VisitSessionItemOrigin.LESSOR_SUGGESTED);
        if (!directRequest && !derivedRequest)
            throw new IllegalStateException("Visit session item provenance is inconsistent");
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public VisitSession getSession() { return session; }
    public void setSession(VisitSession session) { this.session = session; }
    public Listing getListing() { return listing; }
    public void setListing(Listing listing) { this.listing = listing; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public PropertyVisitRequest getSourceRequest() { return sourceRequest; }
    public void setSourceRequest(PropertyVisitRequest sourceRequest) { this.sourceRequest = sourceRequest; }
    public PropertyVisitRequest getDerivedFromRequest() { return derivedFromRequest; }
    public void setDerivedFromRequest(PropertyVisitRequest derivedFromRequest) { this.derivedFromRequest = derivedFromRequest; }
    public VisitSessionItemOrigin getOrigin() { return origin; }
    public void setOrigin(VisitSessionItemOrigin origin) { this.origin = origin; }
    public VisitSessionItemConfirmationStatus getConfirmationStatus() { return confirmationStatus; }
    public void setConfirmationStatus(VisitSessionItemConfirmationStatus confirmationStatus) { this.confirmationStatus = confirmationStatus; }
    public Instant getAvailabilityConfirmedAt() { return availabilityConfirmedAt; }
    public void setAvailabilityConfirmedAt(Instant availabilityConfirmedAt) { this.availabilityConfirmedAt = availabilityConfirmedAt; }
    public User getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(User confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getLessorConfirmationReference() { return lessorConfirmationReference; }
    public void setLessorConfirmationReference(String lessorConfirmationReference) { this.lessorConfirmationReference = lessorConfirmationReference; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
    public String getRemovalReason() { return removalReason; }
    public void setRemovalReason(String removalReason) { this.removalReason = removalReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
