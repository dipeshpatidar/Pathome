package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** The single durable emergency and rollout control for automatic visit-request routing. */
@Entity
@Table(name = "automatic_visit_routing_control")
public class AutomaticVisitRoutingControl {
    public static final short SINGLETON_ID = 1;
    public static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    private Short id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "protocol_version", nullable = false)
    private int protocolVersion;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    protected AutomaticVisitRoutingControl() {}

    public void change(boolean enabled, Long actorUserId, Instant at, String reasonCode) {
        if (actorUserId == null || actorUserId <= 0) {
            throw new IllegalArgumentException("A real staff administrator identity is required");
        }
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
        }
        this.enabled = enabled;
        this.updatedByUserId = actorUserId;
        this.updatedAt = Objects.requireNonNull(at);
        this.reasonCode = reasonCode;
    }

    public Short getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public int getProtocolVersion() { return protocolVersion; }
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public String getReasonCode() { return reasonCode; }
}
