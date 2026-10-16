package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** ORM schema metadata; receipt arbitration is PostgreSQL ON CONFLICT in the booking transaction. */
@Entity
@Table(name = "visit_scheduling_commands")
@IdClass(VisitSchedulingCommand.Key.class)
public class VisitSchedulingCommand {
    @Id @Column(name = "actor_user_id") private Long actorUserId;
    @Id @Column(name = "command_id") private UUID commandId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(nullable = false, length = 40) private String operation;
    @Column(name = "canonical_payload", nullable = false, columnDefinition = "text") private String canonicalPayload;
    @Column(name = "response_json", columnDefinition = "text") private String responseJson;
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static class Key implements Serializable {
        public Long actorUserId;
        public UUID commandId;
        public Key() {}
        @Override public boolean equals(Object value) {
            return value instanceof Key key && java.util.Objects.equals(actorUserId, key.actorUserId)
                    && java.util.Objects.equals(commandId, key.commandId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(actorUserId, commandId); }
    }
}
