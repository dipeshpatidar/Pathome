package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ApproveVisitRecommendationCommand;
import com.indore.pathome.spaces.dto.VisitSchedulingApprovalView;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** No independent transaction or success cache: rollback removes both command and business effects. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class VisitSchedulingCommandStore {
    private final JdbcTemplate jdbc;
    private final TransactionalCommandBounds bounds;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public VisitSchedulingCommandStore(JdbcTemplate jdbc, TransactionalCommandBounds bounds) {
        this.jdbc = jdbc; this.bounds = bounds;
    }
    public void applyBounds() { bounds.apply(); }

    public VisitSchedulingApprovalView begin(Long actorId, Long sessionId, ApproveVisitRecommendationCommand command) {
        String canonical = json(new ApproveVisitRecommendationCommand(command.expectedSessionVersion(),
                command.groundExecutiveUserId(), command.scheduledAt(), command.zoneId(),
                command.overrideReason() == null || command.overrideReason().isBlank() ? null : command.overrideReason().trim(), command.commandId()));
        jdbc.update("insert into visit_scheduling_commands(actor_user_id,command_id,session_id,operation,canonical_payload) "
                + "values (?,?,?,'OE_SELECTION',?) on conflict(actor_user_id,command_id) do nothing",
                actorId, command.commandId(), sessionId, canonical);
        return jdbc.query("select session_id,operation,canonical_payload,response_json from visit_scheduling_commands "
                + "where actor_user_id=? and command_id=?", (rs, index) -> {
            if (rs.getLong("session_id") != sessionId || !"OE_SELECTION".equals(rs.getString("operation"))
                    || !canonical.equals(rs.getString("canonical_payload")))
                throw new VisitOperationsConflictException("Scheduling command ID was already used with different contents");
            return decode(rs.getString("response_json"));
        }, actorId, command.commandId()).get(0);
    }

    public VisitSchedulingApprovalView find(Long actorId, Long sessionId, UUID commandId) {
        var found = jdbc.query("select response_json from visit_scheduling_commands where actor_user_id=? and command_id=? and session_id=?",
                (rs, index) -> decode(rs.getString(1)), actorId, commandId, sessionId);
        return found.isEmpty() ? null : found.get(0);
    }

    public void complete(Long actorId, UUID commandId, VisitSchedulingApprovalView response) {
        if (jdbc.update("update visit_scheduling_commands set response_json=? where actor_user_id=? and command_id=? and response_json is null",
                json(response), actorId, commandId) != 1) throw new IllegalStateException("Scheduling receipt could not be completed");
    }

    private String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Scheduling receipt serialization failed", failure); }
    }
    private VisitSchedulingApprovalView decode(String json) {
        if (json == null) return null;
        try { return MAPPER.readValue(json, VisitSchedulingApprovalView.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Scheduling receipt cannot be read", failure); }
    }
}
