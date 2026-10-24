package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.AutomaticRoutingControlCommand;
import com.indore.pathome.spaces.entity.AutomaticVisitRoutingControl;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.AutomaticVisitRoutingControlRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomaticRoutingControlServiceTest {

    @Test
    void disableTakesTheGlobalControlBeforeActorProfileAndGrantEvidence() {
        AutomaticVisitRoutingControlRepository controls =
                mock(AutomaticVisitRoutingControlRepository.class);
        StaffAccessService access = mock(StaffAccessService.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        AutomaticVisitRoutingControl control = mock(AutomaticVisitRoutingControl.class);
        User actor = mock(User.class);
        Instant now = Instant.parse("2026-10-23T12:00:00Z");

        when(access.requireGlobalAdmin(7L)).thenReturn(actor);
        when(actor.getId()).thenReturn(7L);
        when(clock.now()).thenReturn(now);
        when(control.getVersion()).thenReturn(0L);
        when(control.getProtocolVersion()).thenReturn(1);
        when(controls.findLocked()).thenReturn(Optional.of(control));

        AutomaticRoutingControlService service = new AutomaticRoutingControlService(
                controls, access, audit, bounds, clock);
        service.change(7L, new AutomaticRoutingControlCommand(0L, false, "OWNER_DISABLE"));

        InOrder order = inOrder(access, bounds, controls);
        order.verify(access).requireGlobalAdmin(7L);
        order.verify(bounds).apply();
        order.verify(controls).findLocked();
        order.verify(access).holdGlobalAdminEvidence(7L);
        order.verify(access).requireGlobalAdmin(7L);
    }
}
