package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PropertyVisitRequestAttemptServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final PropertyVisitRequestRepository requests =
            mock(PropertyVisitRequestRepository.class);
    private final VisitSessionRepository sessions = mock(VisitSessionRepository.class);
    private final LeadAssistanceCommandReceiptRepository receipts =
            mock(LeadAssistanceCommandReceiptRepository.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final PropertyVisitRequestAttemptService service =
            new PropertyVisitRequestAttemptService(users, listings, requests, sessions,
                    mock(TrustedOperationalIntakeService.class), receipts,
                    mock(OperationalAuditService.class), clock, entityManager);

    @Test
    void linkedAcknowledgementLocksSessionBeforeRequestAndRecordsTypedReceipt() {
        User tenant = tenant();
        VisitSession session = session(tenant);
        PropertyVisitRequest request = request(tenant, session, 7L);
        UUID operationId = UUID.randomUUID();
        when(users.findLockedById(7L)).thenReturn(Optional.of(tenant));
        when(requests.findTopologyById(55L)).thenReturn(Optional.of(
                new Topology(7L, 44L)));
        when(sessions.findLockedById(44L)).thenReturn(Optional.of(session));
        when(requests.findLockedByIdAndSessionId(55L, 44L)).thenReturn(Optional.of(request));
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyInt(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(null);
        when(clock.now()).thenReturn(Instant.parse("2026-10-27T10:00:00Z"));
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyVisitRequest result = service.acknowledge(tenant, 55L, 31L,
                operationId, "hash", 7L);

        assertEquals(55L, result.getId());
        InOrder order = inOrder(sessions, requests);
        order.verify(sessions).findLockedById(44L);
        order.verify(requests).findLockedByIdAndSessionId(55L, 44L);
        verify(receipts).saveAndFlush(org.mockito.ArgumentMatchers.argThat(value ->
                PropertyVisitRequestAttemptService.ACKNOWLEDGE_ACTION.equals(value.getAction())));
    }

    @Test
    void staleAcknowledgementVersionProducesNoReceipt() {
        User tenant = tenant();
        PropertyVisitRequest request = request(tenant, null, 7L);
        UUID operationId = UUID.randomUUID();
        when(users.findLockedById(7L)).thenReturn(Optional.of(tenant));
        when(requests.findTopologyById(55L)).thenReturn(Optional.of(
                new Topology(7L, null)));
        when(requests.findLockedByIdAndTenantId(55L, 7L)).thenReturn(Optional.of(request));
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyInt(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(null);

        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.acknowledge(tenant, 55L, 31L, operationId, "hash", 6L));
        verify(receipts, never()).saveAndFlush(any());
    }

    private static User tenant() {
        User tenant = new User();
        tenant.setId(7L);
        tenant.setRole(Role.ROLE_TENANT);
        return tenant;
    }

    private static VisitSession session(User tenant) {
        VisitSession session = new VisitSession();
        session.setId(44L);
        session.setTenant(tenant);
        session.setStatus(VisitSessionStatus.DRAFT);
        return session;
    }

    private static PropertyVisitRequest request(User tenant, VisitSession session, Long version) {
        RentalDetails listing = new RentalDetails();
        listing.setId(31L);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(55L);
        request.setTenant(tenant);
        request.setListing(listing);
        request.setSession(session);
        request.setStatus(VisitRequestStatus.COORDINATING);
        request.setVersion(version);
        return request;
    }

    private record Topology(Long tenantId, Long sessionId)
            implements PropertyVisitRequestRepository.RequestTopologyRow {
        @Override public Long getTenantId() { return tenantId; }
        @Override public Long getSessionId() { return sessionId; }
    }
}
