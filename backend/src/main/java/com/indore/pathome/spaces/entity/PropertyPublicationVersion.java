package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "property_publication_versions")
public class PropertyPublicationVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "publication_version", nullable = false, updatable = false)
    private Long publicationVersion;

    @Column(name = "revision_id", nullable = false, updatable = false, length = 64)
    private String revisionId;

    @Column(name = "event_kind", nullable = false, updatable = false, length = 32)
    private String eventKind;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "authority_evidence", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> authorityEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> changedFields;

    @Column(name = "published_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant publishedAt;

    protected PropertyPublicationVersion() {}

    public PropertyPublicationVersion(Long listingId, Long publicationVersion, String revisionId,
                                      String eventKind, Long actorUserId,
                                      Map<String, Object> authorityEvidence,
                                      Map<String, Object> beforeSnapshot,
                                      Map<String, Object> afterSnapshot,
                                      Map<String, Object> changedFields, Instant publishedAt) {
        this.listingId = Objects.requireNonNull(listingId);
        this.publicationVersion = Objects.requireNonNull(publicationVersion);
        this.revisionId = Objects.requireNonNull(revisionId);
        this.eventKind = Objects.requireNonNull(eventKind);
        this.actorUserId = Objects.requireNonNull(actorUserId);
        this.authorityEvidence = immutable(authorityEvidence);
        this.beforeSnapshot = beforeSnapshot == null ? null : immutable(beforeSnapshot);
        this.afterSnapshot = immutable(afterSnapshot);
        this.changedFields = immutable(changedFields);
        this.publishedAt = Objects.requireNonNull(publishedAt);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public Long getId() { return id; }
    public Long getListingId() { return listingId; }
    public Long getPublicationVersion() { return publicationVersion; }
    public String getRevisionId() { return revisionId; }
    public String getEventKind() { return eventKind; }
    public Long getActorUserId() { return actorUserId; }
    public Map<String, Object> getAuthorityEvidence() { return authorityEvidence; }
    public Map<String, Object> getBeforeSnapshot() { return beforeSnapshot; }
    public Map<String, Object> getAfterSnapshot() { return afterSnapshot; }
    public Map<String, Object> getChangedFields() { return changedFields; }
    public Instant getPublishedAt() { return publishedAt; }
}

