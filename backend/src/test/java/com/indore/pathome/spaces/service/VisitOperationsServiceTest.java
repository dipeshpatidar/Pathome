package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;

class VisitOperationsServiceTest {
    private PropertyVisitRequestRepository requests;
    private VisitSessionRepository sessions;
    private UserRepository users;
    private VisitPolicyRepository visitPolicies;
    private VisitSessionItemRepository items;
    private ListingRepository listings;
    private LocalityRepository localities;
    private VisitOperationsAuthorizationService authorization;
    private ApplicationEventPublisher events;
    private EntityManager entityManager;
    private VisitSchedulingRecommendationService recommendations;
    private VisitSchedulingDecisionRepository decisions;
    private OperationalSecurityGuards operationalGuards;
    private VisitOperationsService service;

    @BeforeEach
    void setUp() {
        requests = mock(PropertyVisitRequestRepository.class);
        sessions = mock(VisitSessionRepository.class);
        users = mock(UserRepository.class);
        visitPolicies = mock(VisitPolicyRepository.class);
        items = mock(VisitSessionItemRepository.class);
        listings = mock(ListingRepository.class);
        localities = mock(LocalityRepository.class);
        authorization = mock(VisitOperationsAuthorizationService.class);
        events = mock(ApplicationEventPublisher.class);
        entityManager = mock(EntityManager.class);
        recommendations = mock(VisitSchedulingRecommendationService.class);
        decisions = mock(VisitSchedulingDecisionRepository.class);
        operationalGuards = mock(OperationalSecurityGuards.class);
        OperationalAuditService operationalAudit = mock(OperationalAuditService.class);
        service = new VisitOperationsService(requests, sessions, users, visitPolicies, items, listings, localities,
                authorization, events, entityManager, recommendations, decisions, operationalGuards, operationalAudit);
        when(authorization.requireOperations(9L)).thenReturn(user(9L, Role.ROLE_ADMIN));
        when(users.findLockedById(anyLong())).thenAnswer(invocation -> Optional.of(user(invocation.getArgument(0), Role.ROLE_GROUND_BOY)));
        when(users.findById(anyLong())).thenAnswer(invocation -> Optional.of(user(invocation.getArgument(0), Role.ROLE_GROUND_BOY)));
        when(authorization.requireGroundExecutiveTarget(anyLong())).thenAnswer(invocation -> user(invocation.getArgument(0), Role.ROLE_GROUND_BOY));
        VisitPolicy policy = new VisitPolicy();
        when(visitPolicies.findById(VisitPolicy.SINGLETON_ID)).thenReturn(Optional.of(policy));
    }

    @Test
    void coordinateCreatesOneSessionAndExactDirectRequestItem() {
        User tenant = user(1L, Role.ROLE_TENANT);
        Listing listing = listing(101L, "Example City");
        PropertyVisitRequest request = request(11L, tenant, listing, VisitRequestStatus.RECEIVED, 0L, null);
        when(requests.findLockedById(11L)).thenReturn(Optional.of(request));
        when(sessions.save(any(VisitSession.class))).thenAnswer(invocation -> {
            VisitSession saved = invocation.getArgument(0);
            saved.setId(500L);
            return saved;
        });
        when(items.findBySessionIdAndListingId(500L, 101L)).thenReturn(Optional.empty());
        when(items.findMaximumPosition(500L)).thenReturn(0);
        AtomicReference<VisitSessionItem> created = new AtomicReference<>();
        when(items.saveAndFlush(any(VisitSessionItem.class))).thenAnswer(invocation -> {
            VisitSessionItem item = invocation.getArgument(0);
            item.setId(700L);
            created.set(item);
            return item;
        });
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenAnswer(invocation ->
                created.get() == null ? List.of() : List.of(created.get()));

        OperationsVisitSessionView result = service.coordinateRequest(9L, 11L,
                new CoordinateVisitRequestCommand(0L, null, null));

        assertEquals(VisitRequestStatus.COORDINATING, request.getStatusValue());
        assertSame(tenant, request.getSession().getTenant());
        assertEquals("Example City", request.getSession().getCity());
        assertEquals(500L, result.sessionId());
        assertSame(request, created.get().getSourceRequest());
        assertNull(created.get().getDerivedFromRequest());
        assertEquals(VisitSessionItemOrigin.TENANT_REQUESTED, created.get().getOrigin());
        verify(sessions, times(1)).save(any(VisitSession.class));
        verify(operationalGuards).acquire(List.of(), List.of(), List.of(9L));
        verify(authorization, times(2)).requireOperations(9L);
        InOrder linkAuthorization = inOrder(requests, operationalGuards, authorization);
        linkAuthorization.verify(requests).findLockedById(11L);
        linkAuthorization.verify(operationalGuards).acquire(List.of(), List.of(), List.of(9L));
        linkAuthorization.verify(authorization).requireOperations(9L);
        InOrder writeOrder = inOrder(entityManager, items);
        writeOrder.verify(entityManager).flush();
        writeOrder.verify(items).saveAndFlush(any(VisitSessionItem.class));
    }

    @Test
    void retryOfExistingCoordinationReturnsLinkedSessionWithoutCreatingDuplicates() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 2L);
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.COORDINATING, 8L, session);
        when(requests.findLockedById(11L)).thenReturn(Optional.of(request));
        when(sessions.findById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of());

        OperationsVisitSessionView result = service.coordinateRequest(9L, 11L,
                new CoordinateVisitRequestCommand(0L, null, null));

        assertEquals(500L, result.sessionId());
        verify(sessions, never()).save(any());
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void attachingRequestFromAnotherTenantIsRejected() {
        VisitSession session = session(500L, 2L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest request = request(11L, user(1L, Role.ROLE_TENANT), listing(101L, "Example City"),
                VisitRequestStatus.RECEIVED, 0L, null);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(requests.findLockedById(11L)).thenReturn(Optional.of(request));

        assertThrows(AccessDeniedException.class, () -> service.coordinateRequest(9L, 11L,
                new CoordinateVisitRequestCommand(0L, 500L, 0L)));
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void deniedOperationsActorCannotUseRawIdDetailOrMutation() {
        doThrow(new AccessDeniedException("Admin required")).when(authorization).requireOperations(4L);

        assertThrows(AccessDeniedException.class, () -> service.getOperationsSession(4L, 500L));
        assertThrows(AccessDeniedException.class, () -> service.markRequestUnavailable(4L, 11L,
                new ExpectedVisitRequestVersion(0L)));

        verify(sessions, never()).findById(500L);
        verify(requests, never()).findLockedById(11L);
    }

    @Test
    void nearbyItemsCarryEitherApprovedDerivedOriginAndRejectMixedCity() {
        for (VisitSessionItemOrigin origin : List.of(VisitSessionItemOrigin.OE_ADDED,
                VisitSessionItemOrigin.LESSOR_SUGGESTED)) {
            VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
            PropertyVisitRequest source = request(11L, session.getTenant(), listing(101L, "Example City"),
                    VisitRequestStatus.COORDINATING, 0L, session);
            Listing nearby = listing(102L, "Example City");
            when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
            when(requests.findLockedById(11L)).thenReturn(Optional.of(source));
            when(listings.findByIdAndStatus(102L, ListingStatus.ACTIVE)).thenReturn(Optional.of(nearby));
            when(items.existsBySessionIdAndListingId(500L, 102L)).thenReturn(false);
            when(items.findMaximumPosition(500L)).thenReturn(1);
            AtomicReference<VisitSessionItem> created = new AtomicReference<>();
            when(items.saveAndFlush(any(VisitSessionItem.class))).thenAnswer(invocation -> {
                VisitSessionItem item = invocation.getArgument(0);
                created.set(item);
                return item;
            });
            when(items.findBySessionIdOrderByPositionAsc(500L)).thenAnswer(invocation ->
                    created.get() == null ? List.of() : List.of(created.get()));

            service.addNearbyProperty(9L, 500L, new AddVisitSessionItemCommand(0L, 102L, 11L, origin));

            assertNull(created.get().getSourceRequest());
            assertSame(source, created.get().getDerivedFromRequest());
            assertEquals(origin, created.get().getOrigin());
            reset(sessions, requests, listings, items);
            when(authorization.requireOperations(9L)).thenReturn(user(9L, Role.ROLE_ADMIN));
        }

        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest source = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.COORDINATING, 0L, session);
        Listing mixedCityListing = listing(103L, "Other City");
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(requests.findLockedById(11L)).thenReturn(Optional.of(source));
        when(listings.findByIdAndStatus(103L, ListingStatus.ACTIVE)).thenReturn(Optional.of(mixedCityListing));
        assertThrows(VisitOperationsConflictException.class, () -> service.addNearbyProperty(9L, 500L,
                new AddVisitSessionItemCommand(0L, 103L, 11L, VisitSessionItemOrigin.OE_ADDED)));
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void scheduledSessionRejectsNearbyPropertyAddition() {
        VisitSession session = scheduledSession();
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));

        assertThrows(VisitOperationsConflictException.class, () -> service.addNearbyProperty(9L, 500L,
                new AddVisitSessionItemCommand(0L, 102L, 11L, VisitSessionItemOrigin.OE_ADDED)));

        verify(requests, never()).findLockedById(anyLong());
        verify(items, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void duplicateNearbyItemAndStaleChildMutationAreRejected() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 4L);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        assertThrows(VisitOperationsConflictException.class, () -> service.addNearbyProperty(9L, 500L,
                new AddVisitSessionItemCommand(3L, 102L, 11L, VisitSessionItemOrigin.OE_ADDED)));
        verify(requests, never()).findLockedById(anyLong());
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void duplicateListingIsRejectedEvenWhenTheExistingItemIsInSessionHistory() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest source = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.COORDINATING, 0L, session);
        Listing nearby = listing(102L, "Example City");
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(requests.findLockedById(11L)).thenReturn(Optional.of(source));
        when(listings.findByIdAndStatus(102L, ListingStatus.ACTIVE)).thenReturn(Optional.of(nearby));
        when(items.existsBySessionIdAndListingId(500L, 102L)).thenReturn(true);

        assertThrows(VisitOperationsConflictException.class, () -> service.addNearbyProperty(9L, 500L,
                new AddVisitSessionItemCommand(0L, 102L, 11L, VisitSessionItemOrigin.OE_ADDED)));
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void unconfirmedItineraryCannotBeScheduledAndPublishesNoEvent() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        VisitSessionItem pending = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(List.of(pending));

        assertThrows(VisitOperationsConflictException.class, () -> service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", 41L, 30)));
        assertEquals(VisitSessionStatus.DRAFT, session.getStatus());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void scheduleRequiresGroundExecutiveAndPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", null, 30)));
        assertThrows(IllegalArgumentException.class, () -> service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", 41L, 0)));
        verify(sessions, never()).findLockedById(anyLong());
    }

    @Test
    void overlappingGroundExecutiveReservationRejectsScheduleBeforeMutation() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(sessions.existsOverlappingReservation(eq(41L), anyCollection(), eq(500L),
                any(), any())).thenReturn(true);

        assertThrows(VisitOperationsConflictException.class, () -> service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", 41L, 30)));
        assertEquals(VisitSessionStatus.DRAFT, session.getStatus());
        assertNull(session.getRepresentative());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void confirmedScheduleUpdatesRelatedRequestAndEmitsOneAuthoritativeEvent() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.COORDINATING, 0L, session);
        VisitSessionItem confirmed = item(700L, session, request.getListing(),
                VisitSessionItemConfirmationStatus.CONFIRMED, request);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(List.of(confirmed));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(confirmed));
        when(requests.findLockedBySessionIdOrderByIdAsc(500L)).thenReturn(List.of(request));

        service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", 41L, 30));

        assertEquals(VisitSessionStatus.SCHEDULED, session.getStatus());
        assertEquals(41L, session.getRepresentative().getId());
        assertEquals(30, session.getDurationSnapshotMinutes());
        assertEquals(Instant.parse("2099-10-02T11:30:00Z"), session.getReservedEndAt());
        assertEquals(VisitRequestStatus.SCHEDULED, request.getStatusValue());
        verify(entityManager).flush();
        verify(events, times(1)).publishEvent(any(VisitSessionNotificationEvent.class));
        service.schedule(9L, 500L,
                new ScheduleVisitSessionCommand(0L, Instant.parse("2099-10-02T11:00:00Z"), "Asia/Kolkata", 41L, 30));
        verify(events, times(1)).publishEvent(any(VisitSessionNotificationEvent.class));
    }

    @Test
    void availabilityConfirmationRecordsAuthenticatedActorAndTimestamp() {
        User actor = user(9L, Role.ROLE_ADMIN);
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.COORDINATING, 0L, session);
        VisitSessionItem pending = item(700L, session, request.getListing(),
                VisitSessionItemConfirmationStatus.PENDING, request);
        when(authorization.requireOperations(9L)).thenReturn(actor);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(pending));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(pending));

        service.setAvailability(9L, 500L, 700L,
                new VisitSessionAvailabilityCommand(0L, VisitSessionItemConfirmationStatus.CONFIRMED));

        assertEquals(VisitSessionItemConfirmationStatus.CONFIRMED, pending.getConfirmationStatus());
        assertSame(actor, pending.getConfirmedBy());
        assertNotNull(pending.getAvailabilityConfirmedAt());
        assertEquals(VisitRequestStatus.COORDINATING, request.getStatusValue());
    }

    @Test
    void operationsRecordsPropertyWindowAndAuthenticatedChannelWithoutSchedulingSession() {
        User actor = user(9L, Role.ROLE_ADMIN);
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        VisitSessionItem pending = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        when(authorization.requireOperations(9L)).thenReturn(actor);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(pending));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(pending));

        OperationsVisitSessionView response = service.setAvailability(9L, 500L, 700L,
                new VisitSessionAvailabilityCommand(0L, VisitSessionItemConfirmationStatus.CONFIRMED,
                        java.time.OffsetDateTime.parse("2099-10-02T13:00:00+05:30"),
                        java.time.OffsetDateTime.parse("2099-10-02T17:00:00+05:30"),
                        "Asia/Kolkata", PropertyAvailabilitySource.WHATSAPP));

        assertEquals(VisitSessionItemConfirmationStatus.CONFIRMED, pending.getConfirmationStatus());
        assertEquals(Instant.parse("2099-10-02T07:30:00Z"), pending.getAvailabilityStartAt());
        assertEquals(Instant.parse("2099-10-02T11:30:00Z"), pending.getAvailabilityEndAt());
        assertEquals("Asia/Kolkata", pending.getAvailabilityZoneId());
        assertEquals(PropertyAvailabilitySource.WHATSAPP, pending.getAvailabilitySource());
        assertSame(actor, pending.getConfirmedBy());
        assertNull(pending.getLessorConfirmationReference());
        assertNull(session.getScheduledAt());
        assertEquals(Instant.parse("2099-10-02T07:30:00Z"), response.items().get(0).propertyAvailabilityStartAt());
        assertEquals(9L, response.items().get(0).availabilityConfirmedByUserId());
    }

    @Test
    void invalidPropertyAvailabilityDoesNotPartiallyConfirmItem() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        VisitSessionItem pending = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.setAvailability(9L, 500L, 700L,
                new VisitSessionAvailabilityCommand(0L, VisitSessionItemConfirmationStatus.CONFIRMED,
                        java.time.OffsetDateTime.parse("2099-10-02T17:00:00+05:30"),
                        java.time.OffsetDateTime.parse("2099-10-02T13:00:00+05:30"),
                        "Asia/Kolkata", PropertyAvailabilitySource.PHONE)));

        assertEquals(VisitSessionItemConfirmationStatus.PENDING, pending.getConfirmationStatus());
        assertNull(pending.getAvailabilityConfirmedAt());
        verify(entityManager, never()).flush();
    }

    @Test
    void removalSoftDeletesAndRetainsItemProvenanceAndActor() {
        User actor = user(9L, Role.ROLE_ADMIN);
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.UNAVAILABLE, 0L, session);
        VisitSessionItem unavailable = item(700L, session, request.getListing(),
                VisitSessionItemConfirmationStatus.UNAVAILABLE, request);
        when(authorization.requireOperations(9L)).thenReturn(actor);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(unavailable));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(List.of(unavailable));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(unavailable));

        OperationsVisitSessionView result = service.removeItem(9L, 500L, 700L,
                new RemoveVisitSessionItemCommand(0L, "Lessor reported unavailable"));

        assertNotNull(unavailable.getRemovedAt());
        assertSame(actor, unavailable.getRemovedBy());
        assertSame(request, unavailable.getSourceRequest());
        assertEquals("Lessor reported unavailable", unavailable.getRemovalReason());
        assertEquals(1, result.items().size());
        assertEquals(700L, result.items().get(0).itemId());
    }

    @Test
    void reorderNormalizesAllActivePositionsDeterministically() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.DRAFT, 0L);
        VisitSessionItem first = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        VisitSessionItem second = item(701L, session, listing(102L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(first, second));
        when(items.findMaximumPosition(500L)).thenReturn(2);

        service.reorderItems(9L, 500L, new ReorderVisitSessionItemsCommand(0L, List.of(701L, 700L)));

        assertEquals(1, second.getPosition());
        assertEquals(2, first.getPosition());
        verify(items).saveAllAndFlush(List.of(first, second));
    }

    @Test
    void scheduledItineraryReorderAndRemovalNotifyAssignedGroundExecutive() {
        VisitSession session = scheduledSession();
        VisitSessionItem first = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.CONFIRMED, null);
        VisitSessionItem second = item(701L, session, listing(102L, "Example City"),
                VisitSessionItemConfirmationStatus.CONFIRMED, null);
        List<VisitSessionItem> itinerary = List.of(first, second);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(itinerary);
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(itinerary);
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(first));
        when(items.findMaximumPosition(500L)).thenReturn(2);

        service.reorderItems(9L, 500L, new ReorderVisitSessionItemsCommand(0L, List.of(701L, 700L)));
        service.removeItem(9L, 500L, 700L, new RemoveVisitSessionItemCommand(0L, "Property change"));

        assertEquals(1, second.getPosition());
        assertNotNull(first.getRemovedAt());
        verify(items).saveAllAndFlush(itinerary);
        verify(events, times(2)).publishEvent((Object) argThat((Object event) ->
                event instanceof VisitSessionNotificationEvent visitEvent
                        && visitEvent.type() == VisitSessionNotificationEvent.Type.ITINERARY_CHANGED
                        && visitEvent.groundExecutiveUserId().equals(41L)));
        assertGroundSeesStops(session, itinerary, 1);
    }

    @Test
    void scheduledRemovalCannotCountPendingStopAsRemainingConfirmedStop() {
        VisitSession session = scheduledSession();
        VisitSessionItem confirmed = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.CONFIRMED, null);
        VisitSessionItem pending = item(701L, session, listing(102L, "Example City"),
                VisitSessionItemConfirmationStatus.PENDING, null);
        List<VisitSessionItem> itinerary = List.of(confirmed, pending);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(confirmed));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(itinerary);

        assertThrows(VisitOperationsConflictException.class, () -> service.removeItem(9L, 500L, 700L,
                new RemoveVisitSessionItemCommand(0L, "Remove confirmed stop")));

        assertNull(confirmed.getRemovedAt());
        verify(entityManager, never()).flush();
        verify(events, never()).publishEvent(any());
        assertGroundSeesStops(session, itinerary, 1);
    }

    @Test
    void scheduledRemovalCannotRemoveOnlyConfirmedStop() {
        VisitSession session = scheduledSession();
        VisitSessionItem confirmed = item(700L, session, listing(101L, "Example City"),
                VisitSessionItemConfirmationStatus.CONFIRMED, null);
        List<VisitSessionItem> itinerary = List.of(confirmed);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndId(500L, 700L)).thenReturn(Optional.of(confirmed));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(itinerary);

        assertThrows(VisitOperationsConflictException.class, () -> service.removeItem(9L, 500L, 700L,
                new RemoveVisitSessionItemCommand(0L, "Remove final stop")));

        assertNull(confirmed.getRemovedAt());
        verify(entityManager, never()).flush();
        verify(events, never()).publishEvent(any());
        assertGroundSeesStops(session, itinerary, 1);
    }

    @Test
    void reschedulePreservesProvenanceAndRejectsPastTime() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.SCHEDULED, 0L);
        session.setScheduledAt(Instant.parse("2099-10-02T11:00:00Z"));
        session.setZoneId("Asia/Kolkata");
        session.setRepresentative(user(41L, Role.ROLE_GROUND_BOY));
        session.setAssignedAt(Instant.parse("2026-10-01T00:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2099-10-02T11:30:00Z"));
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.SCHEDULED, 0L, session);
        VisitSessionItem confirmed = item(700L, session, request.getListing(),
                VisitSessionItemConfirmationStatus.CONFIRMED, request);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(List.of(confirmed));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(confirmed));

        service.reschedule(9L, 500L, new RescheduleVisitSessionCommand(0L,
                Instant.parse("2099-10-03T11:00:00Z"), "Asia/Kolkata"));

        assertEquals(request, confirmed.getSourceRequest());
        assertEquals(VisitSessionItemOrigin.TENANT_REQUESTED, confirmed.getOrigin());
        assertEquals(VisitSessionItemConfirmationStatus.CONFIRMED, confirmed.getConfirmationStatus());
        assertEquals(Instant.parse("2099-10-03T11:00:00Z"), session.getScheduledAt());
        assertEquals(Instant.parse("2099-10-03T11:30:00Z"), session.getReservedEndAt());
        verify(sessions).existsOverlappingReservation(eq(41L), anyCollection(), eq(500L),
                eq(Instant.parse("2099-10-03T11:00:00Z")), eq(Instant.parse("2099-10-03T11:30:00Z")));
        assertThrows(IllegalArgumentException.class, () -> service.reschedule(9L, 500L,
                new RescheduleVisitSessionCommand(0L, Instant.parse("2000-01-01T00:00:00Z"), "Asia/Kolkata")));
        assertThrows(IllegalArgumentException.class, () -> service.reschedule(9L, 500L,
                new RescheduleVisitSessionCommand(0L, Instant.parse("2099-10-04T11:00:00Z"), "+05:30")));
    }

    @Test
    void rescheduleAndReassignmentRejectOverlappingReservations() {
        User ge = user(41L, Role.ROLE_GROUND_BOY);
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.SCHEDULED, 0L);
        session.setScheduledAt(Instant.parse("2099-10-02T11:00:00Z"));
        session.setZoneId("Asia/Kolkata");
        session.setRepresentative(ge);
        session.setAssignedAt(Instant.parse("2026-10-01T00:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2099-10-02T11:30:00Z"));
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(sessions.existsOverlappingReservation(eq(41L), anyCollection(), eq(500L), any(), any()))
                .thenReturn(true);

        assertThrows(VisitOperationsConflictException.class, () -> service.reschedule(9L, 500L,
                new RescheduleVisitSessionCommand(0L, Instant.parse("2099-10-03T11:00:00Z"), "Asia/Kolkata")));
        assertEquals(Instant.parse("2099-10-02T11:00:00Z"), session.getScheduledAt());

        reset(sessions);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(sessions.existsOverlappingReservation(eq(42L), anyCollection(), eq(500L), any(), any()))
                .thenReturn(true);
        assertThrows(VisitOperationsConflictException.class, () -> service.assignGroundExecutive(9L, 500L,
                new AssignGroundExecutiveCommand(0L, 42L)));
        assertSame(ge, session.getRepresentative());
    }

    @Test
    void reassignmentChangesTheCurrentGroundExecutiveReadBoundaryImmediately() {
        User oldGround = user(40L, Role.ROLE_GROUND_BOY);
        User newGround = user(41L, Role.ROLE_GROUND_BOY);
        User unassignedGround = user(42L, Role.ROLE_GROUND_BOY);
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.SCHEDULED, 0L);
        session.setScheduledAt(Instant.parse("2099-10-02T11:00:00Z"));
        session.setZoneId("Asia/Kolkata");
        session.setRepresentative(oldGround);
        session.setAssignedAt(Instant.parse("2026-10-01T00:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2099-10-02T11:30:00Z"));
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(authorization.requireGroundExecutiveTarget(41L)).thenReturn(newGround);
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of());
        when(authorization.requireGroundExecutive(40L)).thenReturn(oldGround);
        when(authorization.requireGroundExecutive(41L)).thenReturn(newGround);
        when(authorization.requireGroundExecutive(42L)).thenReturn(unassignedGround);
        when(sessions.findById(500L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(500L)).thenReturn(List.of());

        service.assignGroundExecutive(9L, 500L, new AssignGroundExecutiveCommand(0L, 41L));
        assertEquals(41L, session.getRepresentative().getId());
        assertThrows(EntityNotFoundException.class, () -> service.getAssignedSession(40L, 500L));
        assertThrows(EntityNotFoundException.class, () -> service.getAssignedSession(42L, 500L));
        assertEquals(500L, service.getAssignedSession(41L, 500L).sessionId());
        verify(events).publishEvent((Object) argThat((Object event) -> event instanceof VisitSessionNotificationEvent visitEvent
                && visitEvent.groundExecutiveUserId().equals(41L)));
    }

    @Test
    void cancellationPreservesSessionItemsAndCancelsLinkedRequests() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.SCHEDULED, 0L);
        session.setScheduledAt(Instant.parse("2099-10-02T11:00:00Z"));
        session.setZoneId("Asia/Kolkata");
        session.setRepresentative(user(41L, Role.ROLE_GROUND_BOY));
        session.setAssignedAt(Instant.parse("2026-10-01T00:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2099-10-02T11:30:00Z"));
        PropertyVisitRequest request = request(11L, session.getTenant(), listing(101L, "Example City"),
                VisitRequestStatus.SCHEDULED, 0L, session);
        VisitSessionItem existing = item(700L, session, request.getListing(),
                VisitSessionItemConfirmationStatus.PENDING, request);
        when(sessions.findLockedById(500L)).thenReturn(Optional.of(session));
        when(requests.findLockedBySessionIdOrderByIdAsc(500L)).thenReturn(List.of(request));
        when(items.findBySessionIdOrderByPositionAsc(500L)).thenReturn(List.of(existing));

        OperationsVisitSessionView result = service.cancel(9L, 500L, 0L);

        assertEquals(VisitSessionStatus.CANCELLED, session.getStatus());
        assertEquals(Instant.parse("2099-10-02T11:30:00Z"), session.getReservedEndAt());
        assertEquals(VisitRequestStatus.CANCELLED, request.getStatusValue());
        assertEquals(1, result.items().size());
        verify(events).publishEvent((Object) argThat((Object event) -> event instanceof VisitSessionNotificationEvent visitEvent
                && visitEvent.type() == VisitSessionNotificationEvent.Type.CANCELLED));
    }

    private static VisitSessionItem item(Long id, VisitSession session, Listing listing,
                                          VisitSessionItemConfirmationStatus status, PropertyVisitRequest source) {
        VisitSessionItem item = new VisitSessionItem();
        item.setId(id);
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(1);
        item.setSourceRequest(source);
        item.setOrigin(source == null ? VisitSessionItemOrigin.OE_ADDED : VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(status);
        if (status == VisitSessionItemConfirmationStatus.CONFIRMED) {
            item.setAvailabilityConfirmedAt(Instant.parse("2026-10-01T00:00:00Z"));
            item.setConfirmedBy(user(9L, Role.ROLE_ADMIN));
        }
        return item;
    }

    private static PropertyVisitRequest request(Long id, User tenant, Listing listing, VisitRequestStatus status,
                                                Long version, VisitSession session) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(id);
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setVersion(version);
        request.setSession(session);
        return request;
    }

    private static VisitSession session(Long id, Long tenantId, String city, VisitSessionStatus status, Long version) {
        VisitSession session = new VisitSession();
        session.setId(id);
        session.setTenant(user(tenantId, Role.ROLE_TENANT));
        session.setCity(city);
        session.setStatus(status);
        session.setVersion(version);
        return session;
    }

    private void assertGroundSeesStops(VisitSession session, List<VisitSessionItem> itinerary, int expectedCount) {
        when(authorization.requireGroundExecutive(41L)).thenReturn(session.getRepresentative());
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId())).thenReturn(itinerary);

        assertEquals(expectedCount, service.getAssignedSession(41L, session.getId()).items().size());
    }

    private static VisitSession scheduledSession() {
        VisitSession session = session(500L, 1L, "Example City", VisitSessionStatus.SCHEDULED, 0L);
        session.setScheduledAt(Instant.parse("2099-10-02T11:00:00Z"));
        session.setZoneId("Asia/Kolkata");
        session.setRepresentative(user(41L, Role.ROLE_GROUND_BOY));
        session.setAssignedAt(Instant.parse("2026-10-01T00:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2099-10-02T11:30:00Z"));
        return session;
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private static Listing listing(Long id, String city) {
        Listing listing = mock(Listing.class);
        when(listing.getCanonicalLocalityId()).thenReturn(null);
        when(listing.getId()).thenReturn(id);
        when(listing.getTitle()).thenReturn("Listing " + id);
        when(listing.getAddress()).thenReturn("Address " + id);
        when(listing.getCity()).thenReturn(city);
        when(listing.getSector()).thenReturn("Sector");
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        return listing;
    }
}
