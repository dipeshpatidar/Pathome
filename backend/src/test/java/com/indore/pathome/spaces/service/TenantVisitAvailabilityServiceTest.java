package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantVisitAvailabilityServiceTest {
    private final TenantVisitAvailabilityTransaction transaction = mock(TenantVisitAvailabilityTransaction.class);
    private final TenantVisitAvailabilityService service = new TenantVisitAvailabilityService(transaction);
    private final TenantVisitAvailabilityCommand command =
            new TenantVisitAvailabilityCommand(3L, null, null, null, null);

    @Test
    void delegatesOneAttemptWithTheAuthenticatedTenantIdentity() {
        TenantVisitAvailabilityView view = new TenantVisitAvailabilityView(51L, 4L, null, null, null, null);
        when(transaction.attempt(7L, 51L, command)).thenReturn(view);

        assertSame(view, service.update(7L, 51L, command));
        verify(transaction).attempt(7L, 51L, command);
    }

    @Test
    void retriesOnlyAfterATopologyChangeRolledTheAttemptBack() {
        TenantVisitAvailabilityView view = new TenantVisitAvailabilityView(51L, 4L, null, null, null, null);
        when(transaction.attempt(7L, 51L, command))
                .thenThrow(new TenantVisitAvailabilityTransaction.TopologyChangedException())
                .thenReturn(view);

        assertSame(view, service.update(7L, 51L, command));
        verify(transaction, times(2)).attempt(7L, 51L, command);
    }

    @Test
    void boundedRetryStopsAsAConflictInsteadOfLooping() {
        when(transaction.attempt(7L, 51L, command))
                .thenThrow(new TenantVisitAvailabilityTransaction.TopologyChangedException());

        VisitOperationsConflictException failure = assertThrows(VisitOperationsConflictException.class,
                () -> service.update(7L, 51L, command));
        assertTrue(failure.getMessage().contains("reload and retry"));
        verify(transaction, times(3)).attempt(7L, 51L, command);
        verifyNoMoreInteractions(transaction);
    }

    @Test
    void rejectsMissingActorRequestOrCommandBeforeTouchingTheTransaction() {
        assertThrows(IllegalArgumentException.class, () -> service.update(null, 51L, command));
        assertThrows(IllegalArgumentException.class, () -> service.update(7L, null, command));
        assertThrows(IllegalArgumentException.class, () -> service.update(7L, 51L, null));
        verifyNoInteractions(transaction);
    }

    @Test
    void thePublicRetryOrchestratorRefusesToJoinAnAmbientTransaction() throws Exception {
        Transactional boundary = TenantVisitAvailabilityService.class
                .getMethod("update", Long.class, Long.class, TenantVisitAvailabilityCommand.class)
                .getAnnotation(Transactional.class);
        assertNotNull(boundary, "the retry orchestrator must declare an explicit propagation boundary");
        assertEquals(Propagation.NEVER, boundary.propagation());
    }
}
