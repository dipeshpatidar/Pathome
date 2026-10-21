package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TenantVisitAvailabilityTransactionTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final long TENANT_ID = 7L;
    private static final long REQUEST_ID = 51L;
    private static final long SESSION_ID = 500L;

    private final PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
    private final VisitSessionRepository sessions = mock(VisitSessionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TenantVisitAvailabilityTransaction transaction =
            new TenantVisitAvailabilityTransaction(requests, sessions, users);

    private final OffsetDateTime start = LocalDate.now(ZONE).plusDays(2).atTime(13, 0)
            .atZone(ZONE).toOffsetDateTime();

    @BeforeEach
    void freshTenantCapabilityIsPresentUnlessATestSaysOtherwise() {
        when(users.findRoleValueById(TENANT_ID)).thenReturn(Optional.of("ROLE_TENANT"));
    }

    @Test
    void unlinkedReceivedRequestUpdatesWithoutTakingAnySessionLock() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 3L);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        var result = transaction.attempt(TENANT_ID, REQUEST_ID, command(3L));

        assertEquals(start.toInstant(), result.availabilityStartAt());
        assertEquals(ZONE.getId(), result.availabilityZoneId());
        verify(requests).flush();
        verify(sessions, never()).findLockedById(anyLong());
        InOrder order = inOrder(requests, sessions);
        order.verify(requests).findTopologyById(REQUEST_ID);
        order.verify(requests).findLockedById(REQUEST_ID);
    }

    @Test
    void linkedCoordinatingRequestLocksSessionBeforeRequestAndRevalidatesTheLink() {
        VisitSession session = session(VisitSessionStatus.DRAFT);
        PropertyVisitRequest request = request(VisitRequestStatus.COORDINATING, 3L);
        request.setSession(session);
        stubTopology(SESSION_ID);
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(session));
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        var result = transaction.attempt(TENANT_ID, REQUEST_ID, command(3L));

        assertEquals(start.toInstant(), result.availabilityStartAt());
        verify(requests).flush();
        InOrder order = inOrder(requests, sessions);
        order.verify(requests).findTopologyById(REQUEST_ID);
        order.verify(sessions).findLockedById(SESSION_ID);
        order.verify(requests).findLockedById(REQUEST_ID);
    }

    @Test
    void everyUnlinkedPostBookingOrTerminalStatusIsRejectedWithoutMutation() {
        for (VisitRequestStatus status : new VisitRequestStatus[]{
                VisitRequestStatus.COORDINATING, VisitRequestStatus.SCHEDULED,
                VisitRequestStatus.UNAVAILABLE, VisitRequestStatus.CANCELLED}) {
            PropertyVisitRequest request = request(status, 3L);
            stubTopology(null);
            when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThrows(VisitOperationsConflictException.class,
                    () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)), status.name());
            assertNull(request.getAvailabilityStartAt(), status.name());
        }
        verify(requests, never()).flush();
    }

    @Test
    void linkedRequestIsRejectedUnlessBothSessionAndRequestRemainPreBooking() {
        for (VisitSessionStatus status : VisitSessionStatus.values()) {
            if (status == VisitSessionStatus.DRAFT) continue;
            VisitSession session = session(status);
            PropertyVisitRequest request = request(VisitRequestStatus.COORDINATING, 3L);
            request.setSession(session);
            stubTopology(SESSION_ID);
            when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(session));
            when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThrows(VisitOperationsConflictException.class,
                    () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)), status.name());
            assertNull(request.getAvailabilityStartAt(), status.name());
        }

        VisitSession draft = session(VisitSessionStatus.DRAFT);
        for (VisitRequestStatus status : VisitRequestStatus.values()) {
            if (status == VisitRequestStatus.COORDINATING) continue;
            PropertyVisitRequest request = request(status, 3L);
            request.setSession(draft);
            stubTopology(SESSION_ID);
            when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(draft));
            when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThrows(VisitOperationsConflictException.class,
                    () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)), status.name());
        }
        verify(requests, never()).flush();
    }

    @Test
    void discoverySaysUnlinkedButTheLockedRequestIsLinkedSoTheAttemptRollsBackForRetry() {
        VisitSession session = session(VisitSessionStatus.DRAFT);
        PropertyVisitRequest request = request(VisitRequestStatus.COORDINATING, 3L);
        request.setSession(session);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThrows(TenantVisitAvailabilityTransaction.TopologyChangedException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));
        verify(requests, never()).flush();
        verify(sessions, never()).findLockedById(anyLong());
    }

    @Test
    void linkedTopologyThatMovedToAnotherSessionAlsoRollsBackForRetry() {
        VisitSession locked = session(VisitSessionStatus.DRAFT);
        VisitSession current = session(VisitSessionStatus.DRAFT);
        current.setId(501L);
        PropertyVisitRequest request = request(VisitRequestStatus.COORDINATING, 3L);
        request.setSession(current);
        stubTopology(SESSION_ID);
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(locked));
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThrows(TenantVisitAvailabilityTransaction.TopologyChangedException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));
        verify(requests, never()).flush();
    }

    @Test
    void foreignOrMissingRequestIsConcealedAsNotFoundBeforeAnyLock() {
        when(requests.findTopologyById(REQUEST_ID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));

        when(requests.findTopologyById(REQUEST_ID))
                .thenReturn(Optional.of(topology(99L, null)));
        assertThrows(EntityNotFoundException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));

        verify(requests, never()).findLockedById(anyLong());
        verify(sessions, never()).findLockedById(anyLong());
    }

    @Test
    void staleVersionConflictsBeforeAnyAvailabilityMutation() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 4L);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThrows(VisitOperationsConflictException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));
        assertNull(request.getAvailabilityStartAt());
        verify(requests, never()).flush();
    }

    @Test
    void aPersistedCapabilityLostAfterTheControllerCheckIsRejectedUnderTheRequestLock() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 3L);
        // The managed entity still says ROLE_TENANT; the fresh scalar read is the authority.
        when(users.findRoleValueById(TENANT_ID)).thenReturn(Optional.of("ROLE_SUB_ADMIN"));
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));
        InOrder order = inOrder(requests, users);
        order.verify(requests).findLockedById(REQUEST_ID);
        order.verify(users).findRoleValueById(TENANT_ID);
        verify(requests, never()).flush();
    }

    @Test
    void aDeletedUserOrStaleLessorCapabilityUsesFreshPersistedTruth() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 3L);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        when(users.findRoleValueById(TENANT_ID)).thenReturn(Optional.empty());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L)));

        when(users.findRoleValueById(TENANT_ID)).thenReturn(Optional.of("ROLE_LANDLORD"));
        var result = transaction.attempt(TENANT_ID, REQUEST_ID, command(3L));
        assertEquals(start.toInstant(), result.availabilityStartAt());
    }

    @Test
    void allNullClearingIsAppliedInAnEligibleState() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 3L);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        var result = transaction.attempt(TENANT_ID, REQUEST_ID,
                new TenantVisitAvailabilityCommand(3L, null, null, null, null));

        assertNull(result.availabilityStartAt());
        assertNull(result.availabilityEndAt());
        assertNull(result.availabilityZoneId());
        assertNull(result.preferredAt());
        verify(requests).flush();
    }

    @Test
    void invalidOrPastWindowFailsWithoutPartialMutationOrFlush() {
        PropertyVisitRequest request = request(VisitRequestStatus.RECEIVED, 3L);
        stubTopology(null);
        when(requests.findLockedById(REQUEST_ID)).thenReturn(Optional.of(request));

        OffsetDateTime past = start.minusDays(10);
        assertThrows(IllegalArgumentException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L, past, past.plusHours(2),
                        past.plusHours(1))));
        assertNull(request.getAvailabilityStartAt());
        assertNull(request.getAvailabilityEndAt());
        assertNull(request.getAvailabilityZoneId());
        assertNull(request.getPreferredAt());

        assertThrows(IllegalArgumentException.class,
                () -> transaction.attempt(TENANT_ID, REQUEST_ID, command(3L, start, start.plusHours(2),
                        start.plusHours(3))));
        assertNull(request.getAvailabilityStartAt());
        verify(requests, never()).flush();
    }

    private void stubTopology(Long sessionId) {
        when(requests.findTopologyById(REQUEST_ID)).thenReturn(Optional.of(topology(TENANT_ID, sessionId)));
    }

    private PropertyVisitRequest request(VisitRequestStatus status, Long version) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(REQUEST_ID);
        request.setVersion(version);
        request.setStatus(status);
        request.setTenant(user(TENANT_ID));
        return request;
    }

    private VisitSession session(VisitSessionStatus status) {
        VisitSession session = new VisitSession();
        session.setId(SESSION_ID);
        session.setStatus(status);
        session.setTenant(user(TENANT_ID));
        session.setCity("Test City");
        return session;
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole(com.indore.pathome.spaces.entity.Role.ROLE_TENANT);
        return user;
    }

    private static PropertyVisitRequestRepository.RequestTopologyRow topology(Long tenantId, Long sessionId) {
        return new PropertyVisitRequestRepository.RequestTopologyRow() {
            @Override public Long getTenantId() { return tenantId; }
            @Override public Long getSessionId() { return sessionId; }
        };
    }

    private TenantVisitAvailabilityCommand command(Long version) {
        return command(version, start, start.plusHours(4), start.plusHours(1));
    }

    private TenantVisitAvailabilityCommand command(Long version, OffsetDateTime windowStart,
                                                   OffsetDateTime windowEnd, OffsetDateTime preferred) {
        return new TenantVisitAvailabilityCommand(version, windowStart, windowEnd, ZONE.getId(), preferred);
    }
}
