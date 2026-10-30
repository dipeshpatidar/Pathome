package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "property_publication_outbox")
public class PropertyPublicationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "publication_version", nullable = false, updatable = false)
    private Long publicationVersion;

    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "dispatched_at", columnDefinition = "timestamptz")
    private Instant dispatchedAt;

    protected PropertyPublicationOutbox() {}

    public PropertyPublicationOutbox(Long listingId, Long publicationVersion, String eventType,
                                     Map<String, Object> payload, Instant createdAt) {
        this.listingId = Objects.requireNonNull(listingId);
        this.publicationVersion = Objects.requireNonNull(publicationVersion);
        this.eventType = Objects.requireNonNull(eventType);
        this.payload = Map.copyOf(payload);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public Long getListingId() { return listingId; }
    public Long getPublicationVersion() { return publicationVersion; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
}

