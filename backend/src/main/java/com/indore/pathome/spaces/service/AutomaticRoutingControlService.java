package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.AutomaticRoutingControlCommand;
import com.indore.pathome.spaces.dto.AutomaticRoutingControlView;
import com.indore.pathome.spaces.entity.AutomaticVisitRoutingControl;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.OperationalCommandUnavailableException;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.AutomaticVisitRoutingControlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class AutomaticRoutingControlService {
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private final AutomaticVisitRoutingControlRepository controls;
    private final StaffAccessService access;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;

    public AutomaticRoutingControlService(AutomaticVisitRoutingControlRepository controls,
                                          StaffAccessService access,
                                          OperationalAuditService audit,
                                          TransactionalCommandBounds commandBounds,
                                          DatabaseClock databaseClock) {
        this.controls = controls;
        this.access = access;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
    }

    @Transactional(readOnly = true)
    public AutomaticRoutingControlView read(Long actorId) {
        access.requireGlobalAdmin(actorId);
        return toView(controls.findById(AutomaticVisitRoutingControl.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Automatic routing control is missing")));
    }

    @Transactional(readOnly = true)
    public boolean isProcessingEnabled() {
        return controls.findById(AutomaticVisitRoutingControl.SINGLETON_ID)
                .filter(AutomaticVisitRoutingControl::isEnabled)
                .filter(control -> control.getProtocolVersion()
                        == AutomaticVisitRoutingControl.SUPPORTED_PROTOCOL_VERSION)
                .isPresent();
    }

    @Transactional
    public AutomaticRoutingControlView change(Long actorId, AutomaticRoutingControlCommand command) {
        if (command == null || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.reasonCode() == null || !REASON.matcher(command.reasonCode()).matches()) {
            throw new IllegalArgumentException(
                    "Expected routing control version and a bounded uppercase reason code are required");
        }
        try {
            return changeBounded(actorId, command);
        } catch (RuntimeException failure) {
            throw OperationalCommandUnavailableException.translate(failure);
        }
    }

    private AutomaticRoutingControlView changeBounded(Long actorId,
            AutomaticRoutingControlCommand command) {
        // An initial non-locking check rejects an unauthenticated caller before it can wait on the
        // shared global control. The control is then the first exclusive lock in this transaction, so
        // it cannot form the former worker(control -> City) / coverage(City -> grants) /
        // disable(grants -> control) cycle.
        access.requireGlobalAdmin(actorId);
        commandBounds.apply();
        AutomaticVisitRoutingControl control = controls.findLocked()
                .orElseThrow(() -> new IllegalStateException("Automatic routing control is missing"));
        if (!Objects.equals(control.getVersion(), command.expectedVersion())) {
            throw new StaffAccessConflictException("Routing control version is stale; reload and retry");
        }
        access.holdGlobalAdminEvidence(actorId);
        User actor = access.requireGlobalAdmin(actorId);
        control.change(command.enabled(), actor.getId(), databaseClock.now(), command.reasonCode());
        controls.saveAndFlush(control);
        audit.recordUserEvent(actor, "AUTOMATIC_ROUTING_CONTROL_CHANGED",
                "AUTOMATIC_VISIT_ROUTING_CONTROL", 1L, command.reasonCode(), null, null,
                Map.of("controlEnabled", command.enabled(),
                        "controlVersion", command.expectedVersion() + 1,
                        "routingProtocolVersion", control.getProtocolVersion()));
        return toView(control);
    }

    private static AutomaticRoutingControlView toView(AutomaticVisitRoutingControl control) {
        return new AutomaticRoutingControlView(control.isEnabled(), control.getProtocolVersion(),
                control.getVersion(), control.getUpdatedAt(), control.getUpdatedByUserId(),
                control.getReasonCode());
    }
}
