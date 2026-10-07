package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.VisitRequestOwnershipCommand;
import com.indore.pathome.spaces.dto.VisitSessionOwnershipCommand;
import com.indore.pathome.spaces.service.OperationalOwnershipService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes only the ownership actions already governed by Phase 1C scoped capabilities. */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationalOwnershipController {
    private final OperationalOwnershipService ownership;

    public OperationalOwnershipController(OperationalOwnershipService ownership) {
        this.ownership = ownership;
    }

    @PostMapping("/visit-requests/{requestId}/claim")
    public ResponseEntity<Void> claimRequest(Authentication authentication, @PathVariable Long requestId,
            @RequestBody VisitRequestOwnershipCommand command) {
        requireCommand(command);
        ownership.claimRequest(StaffAuthentication.userId(authentication), requestId,
                command.expectedRequestVersion(), command.expectedSessionVersion(),
                command.expectedRequestVersions(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-sessions/{sessionId}/claim")
    public ResponseEntity<Void> claimSession(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody VisitSessionOwnershipCommand command) {
        requireCommand(command);
        ownership.claimSession(StaffAuthentication.userId(authentication), sessionId,
                command.expectedSessionVersion(), command.expectedRequestVersions(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-requests/{requestId}/coordinator")
    public ResponseEntity<Void> assignRequestCoordinator(Authentication authentication,
            @PathVariable Long requestId, @RequestBody VisitRequestOwnershipCommand command) {
        requireCommand(command);
        ownership.assignRequestCoordinator(StaffAuthentication.userId(authentication), requestId,
                command.expectedRequestVersion(), command.expectedSessionVersion(),
                command.expectedRequestVersions(), command.coordinatorUserId(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-sessions/{sessionId}/coordinator")
    public ResponseEntity<Void> assignSessionCoordinator(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody VisitSessionOwnershipCommand command) {
        requireCommand(command);
        ownership.assignCoordinator(StaffAuthentication.userId(authentication), sessionId,
                command.expectedSessionVersion(), command.expectedRequestVersions(),
                command.coordinatorUserId(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-requests/{requestId}/team-transfer")
    public ResponseEntity<Void> transferRequestTeam(Authentication authentication, @PathVariable Long requestId,
            @RequestBody VisitRequestOwnershipCommand command) {
        requireCommand(command);
        ownership.transferRequestTeam(StaffAuthentication.userId(authentication), requestId,
                command.expectedRequestVersion(), command.expectedSessionVersion(), command.expectedRequestVersions(),
                command.destinationTeamId(), command.coordinatorUserId(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-sessions/{sessionId}/team-transfer")
    public ResponseEntity<Void> transferSessionTeam(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody VisitSessionOwnershipCommand command) {
        requireCommand(command);
        ownership.transferTeam(StaffAuthentication.userId(authentication), sessionId,
                command.expectedSessionVersion(), command.expectedRequestVersions(),
                command.destinationTeamId(), command.coordinatorUserId(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-requests/{requestId}/release")
    public ResponseEntity<Void> releaseRequestCoordinator(Authentication authentication,
            @PathVariable Long requestId, @RequestBody VisitRequestOwnershipCommand command) {
        requireCommand(command);
        ownership.releaseRequestCoordinator(StaffAuthentication.userId(authentication), requestId,
                command.expectedRequestVersion(), command.expectedSessionVersion(),
                command.expectedRequestVersions(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/visit-sessions/{sessionId}/release")
    public ResponseEntity<Void> releaseSessionCoordinator(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody VisitSessionOwnershipCommand command) {
        requireCommand(command);
        ownership.releaseCoordinator(StaffAuthentication.userId(authentication), sessionId,
                command.expectedSessionVersion(), command.expectedRequestVersions(), command.reasonCode());
        return ResponseEntity.noContent().build();
    }

    private static void requireCommand(Object command) {
        if (command == null) throw new IllegalArgumentException("Request body is required");
    }
}
