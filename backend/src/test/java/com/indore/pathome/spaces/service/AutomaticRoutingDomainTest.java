package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.AuditActorKind;
import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AutomaticRoutingDomainTest {

    @Test
    void routingOnlySourceRequiresEnrollmentReadinessAndCanonicalCityAgreement() {
        IntakeRoutingAuthority.Scope routing = new IntakeRoutingAuthority.Scope(false,
                List.of(), List.of(5L), List.of(5L));

        assertTrue(routing.coversRoutingOnlySource(5L, 5L, true, true));
        assertThrows(EntityNotFoundException.class, () -> requireRoutingSource(routing, null, 5L, true, true));
        assertThrows(EntityNotFoundException.class, () -> requireRoutingSource(routing, 5L, 9L, true, true));
        assertThrows(EntityNotFoundException.class, () -> requireRoutingSource(routing, 5L, 5L, false, true));
        assertThrows(EntityNotFoundException.class, () -> requireRoutingSource(routing, 5L, 5L, true, false));
    }

    @Test
    void cityTaskReadsRequireReadyCanonicalEvidenceWhileGlobalDiagnosisRemainsAvailable() {
        IntakeRoutingAuthority.Scope cityAdmin = new IntakeRoutingAuthority.Scope(false,
                List.of(5L), List.of(), List.of(5L));
        IntakeRoutingAuthority.Scope global = new IntakeRoutingAuthority.Scope(true,
                List.of(), List.of(), List.of());

        assertTrue(cityAdmin.coversRoutingTaskSource(5L, 5L, true));
        assertFalse(cityAdmin.coversRoutingTaskSource(5L, 9L, true));
        assertFalse(cityAdmin.coversRoutingTaskSource(5L, 5L, false));
        assertTrue(global.coversRoutingTaskSource(null, null, false));
    }

    private static void requireRoutingSource(IntakeRoutingAuthority.Scope scope, Long sourceCityId,
            Long localityCityId, boolean ready, boolean enrolled) {
        if (!scope.coversRoutingOnlySource(sourceCityId, localityCityId, ready, enrolled)) {
            throw new EntityNotFoundException("Visit Request not found");
        }
    }

    @Test
    void opsRoutingIsCityScopedAndCannotUseTeamScope() {
        User actor = new User();
        SupportedCity city = new SupportedCity("indore", "Indore", true);
        Instant now = Instant.now();

        StaffAccessGrant valid = new StaffAccessGrant(actor, StaffCapability.OPS_ROUTING,
                StaffScopeType.CITY, city, null, now, now.plusSeconds(3600), actor, now,
                "ROUTING_EXCEPTION_DUTY", StaffGrantProvisioningSource.ADMIN_API);
        assertEquals(StaffCapability.OPS_ROUTING, valid.getCapability());
        assertThrows(IllegalArgumentException.class, () -> new StaffAccessGrant(actor,
                StaffCapability.OPS_ROUTING, StaffScopeType.TEAM, null, null, now,
                now.plusSeconds(3600), actor, now, "ROUTING_EXCEPTION_DUTY",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    @Test
    void businessRoutingExceptionsArePersistentWhileTechnicalRetryIsBounded() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setVersion(0L);
        Instant now = Instant.now();
        VisitRequestRoutingTask task = new VisitRequestRoutingTask(request, now);

        task.needsReview("NO_ACTIVE_TEAM_COVERAGE", now);
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, task.getState());
        assertEquals(0, task.getAttempts());
        assertEquals(now, task.getAvailableAt());

        task.requeue(now.plusSeconds(1));
        assertEquals(VisitRequestRoutingTaskState.PENDING, task.getState());
        task.retry("ROUTING_TECHNICAL_FAILURE", now.plusSeconds(2));
        assertEquals(VisitRequestRoutingTaskState.RETRY, task.getState());
        assertEquals(1, task.getAttempts());
        assertTrue(task.getAvailableAt().isAfter(now.plusSeconds(2)));
    }

    @Test
    void technicalRetryUsesTheApprovedBoundedBackoffAndAttemptCap() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setVersion(0L);
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        VisitRequestRoutingTask task = new VisitRequestRoutingTask(request, now);
        long[] delays = {5, 10, 20, 40, 80, 160, 300};

        for (int attempt = 1; attempt <= delays.length; attempt++) {
            task.retry("ROUTING_TECHNICAL_FAILURE", now);
            assertEquals(attempt, task.getAttempts());
            assertEquals(VisitRequestRoutingTaskState.RETRY, task.getState());
            assertEquals(now.plusSeconds(delays[attempt - 1]), task.getAvailableAt());
        }
        task.retry("ROUTING_TECHNICAL_FAILURE", now.plusMillis(125));
        assertEquals(VisitRequestRoutingTask.MAX_ATTEMPTS, task.getAttempts());
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, task.getState());
        assertEquals(now.plusMillis(125).plusSeconds(300), task.getAvailableAt());
    }

    @Test
    void systemAuditHasNoHumanOrOperatorIdentity() {
        Instant now = Instant.now();
        OperationalAuditEvent system = new OperationalAuditEvent(now, AuditActorKind.SYSTEM,
                null, null, "AUTOMATIC_TEAM_ROUTED", "PROPERTY_VISIT_REQUEST", 41L,
                null, null, null, null, "UNIQUE_CANONICAL_COVERAGE_V1", Map.of("coverageId", 9L));

        assertEquals(AuditActorKind.SYSTEM, system.getActorKind());
        assertNotNull(system.getOccurredAt());
        assertThrows(IllegalArgumentException.class, () -> new OperationalAuditEvent(now,
                AuditActorKind.SYSTEM, mock(User.class), null, "AUTOMATIC_TEAM_ROUTED",
                "PROPERTY_VISIT_REQUEST", 41L, null, null, null, null,
                "UNIQUE_CANONICAL_COVERAGE_V1", Map.of()));
    }
}
