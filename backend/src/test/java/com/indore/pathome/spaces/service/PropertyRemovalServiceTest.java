package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.removal.PropertyRemovalDtos;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PropertyRemovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private final PropertyRemovalRequestRepository requests = mock(PropertyRemovalRequestRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final LocalityRepository localities = mock(LocalityRepository.class);
    private final LessorProfileRepository lessorProfiles = mock(LessorProfileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository employeeProfiles = mock(EmployeeProfileRepository.class);
    private final StaffAccessGrantRepository grants = mock(StaffAccessGrantRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final VisitSessionRepository sessions = mock(VisitSessionRepository.class);
    private final PropertyRevisionEventRepository events = mock(PropertyRevisionEventRepository.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PropertyRemovalService service = new PropertyRemovalService(requests, listings, localities,
            lessorProfiles, users, employeeProfiles, grants, drafts, sessions, events, audit,
            notifications, clock, entityManager);
    private RentalDetails listing;
    private User owner;
    private User admin;
    private LessorProfile profile;

    @BeforeEach
    void setUp() {
        owner = new User(5L, "owner@pathome.in", "hash", "Owner", "9000000000",
                Role.ROLE_LANDLORD, null, 0);
        admin = new User(9L, "admin@pathome.in", "hash", "Admin", "9000000001",
                Role.ROLE_ADMIN, null, 0);
        listing = new RentalDetails();
        listing.setId(42L);
        listing.setOwnerUserId(5L);
        listing.setLessorProfileId(15L);
        listing.setVersion(10L);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setTitle("Home");
        listing.setAddress("Private address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        profile = new LessorProfile(15L, 5L, "Owner", "9000000000",
                "owner@pathome.in", LessorSourceType.SELF_SERVICE);
        when(clock.now()).thenReturn(NOW);
        when(grants.hasEffectiveGlobalAdmin(anyLong())).thenReturn(true);
        when(users.findById(anyLong())).thenAnswer(call -> Optional.of(
                call.<Long>getArgument(0) == 9L ? admin : owner));
    }

    @Test
    void ownerRequestIsPendingAndDoesNotChangePropertyVisibility() {
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(lessorProfiles.findById(15L)).thenReturn(Optional.of(profile));
        when(requests.findByListingIdAndRequesterUserIdAndCreationOperationId(
                eq(42L), eq(5L), any())).thenReturn(Optional.empty());
        when(requests.findPendingForUpdate(42L)).thenReturn(List.of());
        when(requests.saveAndFlush(any())).thenAnswer(call -> {
            PropertyRemovalRequest saved = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });
        UUID operationId = UUID.randomUUID();

        var result = service.create(5L, 42L, 10L,
                new PropertyRemovalDtos.CreateRequest(operationId, "Moving back to the city"));

        assertEquals(PropertyRemovalRequestStatus.PENDING, result.request().requestStatus());
        assertTrue(result.created());
        assertEquals(ListingWorkflowStatus.PUBLISHED, listing.getWorkflowStatus());
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
        assertEquals(10L, listing.getVersion());
        assertEquals("Private address", listing.getAddress());
        assertEquals(new BigDecimal("25000.00"), listing.getMonthlyRent());
        verify(entityManager).refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        verify(listings, never()).delete(any());
        verify(audit).recordUserEvent(eq(owner), eq("PROPERTY_REMOVAL_REQUESTED"),
                eq("PROPERTY_REMOVAL_REQUEST"), any(), eq("OWNER_REMOVAL_REQUEST"),
                isNull(), isNull(), anyMap());
    }

    @Test
    void preloadedActiveListingThatArrivesArchivedAtSameVersionIsRejectedByFreshEligibility() {
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(lessorProfiles.findById(15L)).thenReturn(Optional.of(profile));
        doAnswer(invocation -> {
            listing.setWorkflowStatus(ListingWorkflowStatus.ARCHIVED);
            listing.setStatus(ListingStatus.CLOSED);
            return null;
        }).when(entityManager).refresh(listing, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(com.indore.pathome.spaces.exception.DraftConflictException.class,
                () -> service.create(5L, 42L, 10L,
                        new PropertyRemovalDtos.CreateRequest(UUID.randomUUID(), "Relocating")));

        verify(entityManager).refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        verify(requests, never()).saveAndFlush(any());
        verify(events, never()).save(any());
        assertEquals(10L, listing.getVersion());
        assertEquals(ListingWorkflowStatus.ARCHIVED, listing.getWorkflowStatus());
    }

    @Test
    void reverseOrderingStartsArchivedButContinuesOnlyWhenTheLockedReadIsActiveAndCurrent() {
        listing.setWorkflowStatus(ListingWorkflowStatus.ARCHIVED);
        listing.setStatus(ListingStatus.CLOSED);
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(lessorProfiles.findById(15L)).thenReturn(Optional.of(profile));
        when(requests.findByListingIdAndRequesterUserIdAndCreationOperationId(
                eq(42L), eq(5L), any())).thenReturn(Optional.empty());
        when(requests.findPendingForUpdate(42L)).thenReturn(List.of());
        when(requests.saveAndFlush(any())).thenAnswer(call -> {
            PropertyRemovalRequest saved = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });
        doAnswer(invocation -> {
            listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
            listing.setStatus(ListingStatus.ACTIVE);
            return null;
        }).when(entityManager).refresh(listing, LockModeType.PESSIMISTIC_WRITE);

        var result = service.create(5L, 42L, 10L,
                new PropertyRemovalDtos.CreateRequest(UUID.randomUUID(), "Relocating"));
        assertTrue(result.created());
        assertEquals(ListingWorkflowStatus.PUBLISHED, listing.getWorkflowStatus());
    }

    @Test
    void approvalArchivesOnceAndPreservesBusinessFacts() {
        PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                "creation-hash", NOW, 10L, "Relocating");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 77L);
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getId()).thenReturn(101L);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.findEffectiveGlobalAdminSharedByUserId(9L)).thenReturn(List.of(grant));
        when(users.findSharedById(9L)).thenReturn(Optional.of(admin));
        when(requests.findById(77L)).thenReturn(Optional.of(request));
        when(listings.lockForSchedulingById(42L)).thenReturn(Optional.of(42L));
        when(requests.findLockedById(77L)).thenReturn(Optional.of(request));
        when(listings.findById(42L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(listing)).thenAnswer(call -> {
            listing.setVersion(11L);
            return listing;
        });
        UUID operationId = UUID.randomUUID();

        var result = service.decide(9L, 77L, 0L,
                new PropertyRemovalDtos.DecisionRequest(operationId,
                        PropertyRemovalDtos.DecisionType.APPROVED, 10L, "Approved after review"));

        assertEquals(PropertyRemovalRequestStatus.APPROVED, result.requestStatus());
        assertEquals(ListingWorkflowStatus.ARCHIVED, listing.getWorkflowStatus());
        assertEquals(ListingStatus.CLOSED, listing.getStatus());
        assertEquals(11L, listing.getVersion());
        assertEquals(5L, listing.getOwnerUserId());
        assertEquals(15L, listing.getLessorProfileId());
        assertEquals("Private address", listing.getAddress());
        assertEquals(new BigDecimal("25000.00"), listing.getMonthlyRent());
        assertEquals(11L, request.getResultingListingVersion());
        assertEquals(11L, request.getAuthorityEvidence().get("resultingVersion"));
        assertEquals(10L, request.getAuthorityEvidence().get("previousVersion"));
        verify(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);
        verify(entityManager).refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        verify(listings, times(1)).saveAndFlush(listing);
        verify(requests, never()).saveAndFlush(request);
        verify(entityManager).detach(request);
        verify(events).save(any(PropertyRevisionEvent.class));
        verify(notifications).createNotificationWithEventKeyInCurrentTransaction(
                eq(TargetRole.LANDLORD), eq("5"), anyString(), anyString(), isNull(),
                eq("PROPERTY"), eq("info"), eq(NotificationAuthorizationClass.RECIPIENT),
                isNull(), contains("property-removal-77"));
    }

    @Test
    void rejectionPersistsExactlyOnceAndDetachesBeforeLaterFacts() {
        PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                "creation-hash", NOW, 10L, "Relocating");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 77L);
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getId()).thenReturn(101L);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.findEffectiveGlobalAdminSharedByUserId(9L)).thenReturn(List.of(grant));
        when(users.findSharedById(9L)).thenReturn(Optional.of(admin));
        when(requests.findById(77L)).thenReturn(Optional.of(request));
        when(listings.lockForSchedulingById(42L)).thenReturn(Optional.of(42L));
        when(requests.findLockedById(77L)).thenReturn(Optional.of(request));
        when(listings.findById(42L)).thenReturn(Optional.of(listing));
        when(requests.saveAndFlush(request)).thenReturn(request);
        UUID operationId = UUID.randomUUID();

        var result = service.decide(9L, 77L, 0L,
                new PropertyRemovalDtos.DecisionRequest(operationId,
                        PropertyRemovalDtos.DecisionType.REJECTED, 10L,
                        "Rejected after review"));

        assertEquals(PropertyRemovalRequestStatus.REJECTED, result.requestStatus());
        assertEquals(ListingWorkflowStatus.PUBLISHED, listing.getWorkflowStatus());
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
        assertEquals(10L, request.getResultingListingVersion());
        assertEquals(10L, request.getAuthorityEvidence().get("resultingVersion"));
        verify(requests, times(1)).saveAndFlush(request);
        verify(listings, never()).saveAndFlush(any());
        verify(entityManager).detach(request);
        verify(events).save(any(PropertyRevisionEvent.class));
        verify(notifications).createNotificationWithEventKeyInCurrentTransaction(
                eq(TargetRole.LANDLORD), eq("5"), anyString(), anyString(), isNull(),
                eq("PROPERTY"), eq("info"), eq(NotificationAuthorizationClass.RECIPIENT),
                isNull(), contains("property-removal-77"));
    }

    @Test
    void competingDecisionCommittedBeforeTheLockIsReplayedAfterTheFreshLockedRead() {
        UUID operationId = UUID.randomUUID();
        String reason = "Reviewed after correction";
        String payloadHash = sha256("DECISION|77|0|REJECTED|10|" + reason);
        PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                "creation-hash", NOW, 10L, "Relocating");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 77L);
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getId()).thenReturn(101L);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.findEffectiveGlobalAdminSharedByUserId(9L)).thenReturn(List.of(grant));
        when(users.findSharedById(9L)).thenReturn(Optional.of(admin));
        when(requests.findById(77L)).thenReturn(Optional.of(request));
        when(listings.lockForSchedulingById(42L)).thenReturn(Optional.of(42L));
        when(requests.findLockedById(77L)).thenReturn(Optional.of(request));
        when(listings.findById(42L)).thenReturn(Optional.of(listing));
        doAnswer(invocation -> {
            request.reject(9L, operationId, payloadHash, reason, 10L, NOW,
                    Map.of("capability", StaffCapability.STAFF_ADMIN.name(),
                            "scopeType", "GLOBAL", "grantId", 101L,
                            "previousVersion", 10L, "resultingVersion", 10L));
            return null;
        }).when(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);

        var result = service.decide(9L, 77L, 0L,
                new PropertyRemovalDtos.DecisionRequest(operationId,
                        PropertyRemovalDtos.DecisionType.REJECTED, 10L, reason));

        assertEquals(PropertyRemovalRequestStatus.REJECTED, result.requestStatus());
        verify(requests, never()).saveAndFlush(any());
        verify(listings, never()).saveAndFlush(any());
        verify(events, never()).save(any());
    }

    @Test
    void ownerHistoryPageTwoUsesARealTwentyRowOffsetAndOneExtraLookahead() {
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(lessorProfiles.findById(15L)).thenReturn(Optional.of(profile));
        when(requests.findOwnerHistory(eq(42L), eq(5L), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(1, 20), false));

        var result = service.ownerHistory(5L, 42L, 1);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests).findOwnerHistory(eq(42L), eq(5L), pageable.capture());
        assertEquals(20L, pageable.getValue().getOffset());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(1, result.page());
        assertFalse(result.hasMore());
    }

    @Test
    void everyAdminStatusFilterUsesCorrectPageOffsetAndServerScope() {
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.hasEffectiveGlobalAdmin(9L)).thenReturn(true);

        for (PropertyRemovalRequestStatus status : java.util.Arrays.asList(
                PropertyRemovalRequestStatus.PENDING, PropertyRemovalRequestStatus.APPROVED,
                PropertyRemovalRequestStatus.REJECTED, PropertyRemovalRequestStatus.WITHDRAWN, null)) {
            when(requests.findAdminQueue(eq(status), eq(List.of(7L)), eq(false), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(1, 20), false));
            service.adminQueue(9L, status, 7L, 1, 20);
        }

        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(requests, times(5)).findAdminQueue(any(), eq(List.of(7L)), eq(false),
                pageables.capture());
        assertTrue(pageables.getAllValues().stream().allMatch(pageable ->
                pageable.getPageNumber() == 1 && pageable.getPageSize() == 20
                        && pageable.getOffset() == 20L));
        verify(grants, never()).findEffectiveGlobalAdminSharedByUserId(anyLong());
        verify(users, never()).findSharedById(anyLong());
    }

    @Test
    void adminAndOwnerPagesTraverseEveryBoundaryOnceForAllFiveFilters() {
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.hasEffectiveGlobalAdmin(9L)).thenReturn(true);
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(listing));
        when(lessorProfiles.findById(15L)).thenReturn(Optional.of(profile));
        when(sessions.countAppointmentsForListings(anyList(), anyList())).thenReturn(List.of());

        for (PropertyRemovalRequestStatus status : java.util.Arrays.asList(
                PropertyRemovalRequestStatus.PENDING, PropertyRemovalRequestStatus.APPROVED,
                PropertyRemovalRequestStatus.REJECTED, PropertyRemovalRequestStatus.WITHDRAWN, null)) {
            for (int count : List.of(0, 1, 20, 21, 23, 42)) {
                List<PropertyRemovalRequest> allRows = mixedRequests(count);
                List<PropertyRemovalRequest> rows = allRows.stream()
                        .filter(row -> status == null || row.getRequestStatus() == status)
                        .sorted(java.util.Comparator
                                .comparing(PropertyRemovalRequest::getRequestedAt).reversed()
                                .thenComparing(PropertyRemovalRequest::getId,
                                        java.util.Comparator.reverseOrder()))
                        .toList();
                when(listings.findAllById(anyList())).thenReturn(rows.stream()
                        .map(row -> listing(row.getId())).toList());
                when(requests.findAdminQueue(eq(status), eq(List.of()), eq(true), any(Pageable.class)))
                        .thenAnswer(call -> page(rows, call.getArgument(3)));

                List<Long> traversed = new ArrayList<>();
                int pageNumber = 0;
                boolean hasMore;
                do {
                    var page = service.adminQueue(9L, status, null, pageNumber, 20);
                    traversed.addAll(page.items().stream()
                            .map(PropertyRemovalDtos.AdminQueueItem::requestId).toList());
                    hasMore = page.hasMore();
                    pageNumber++;
                } while (hasMore);
                assertEquals(rows.size(), traversed.size());
                assertEquals(rows.size(), traversed.stream().distinct().count());
                assertEquals(rows.stream().map(PropertyRemovalRequest::getId).toList(), traversed);
            }
        }

        for (int count : List.of(0, 1, 20, 21, 23, 42)) {
            List<PropertyRemovalRequest> rows = mixedRequests(count).stream()
                    .sorted(java.util.Comparator
                            .comparing(PropertyRemovalRequest::getRequestedAt).reversed()
                            .thenComparing(PropertyRemovalRequest::getId,
                                    java.util.Comparator.reverseOrder()))
                    .toList();
            when(requests.findOwnerHistory(eq(42L), eq(5L), any(Pageable.class)))
                    .thenAnswer(call -> page(rows, call.getArgument(2)));
            List<Long> traversed = new ArrayList<>();
            int pageNumber = 0;
            boolean hasMore;
            do {
                var page = service.ownerHistory(5L, 42L, pageNumber);
                traversed.addAll(page.items().stream()
                        .map(PropertyRemovalDtos.RequestView::requestId).toList());
                hasMore = page.hasMore();
                pageNumber++;
            } while (hasMore);
            assertEquals(count, traversed.size());
            assertEquals(count, traversed.stream().distinct().count());
            assertEquals(rows.stream().map(PropertyRemovalRequest::getId).toList(), traversed);
        }
        verify(grants, never()).findEffectiveGlobalAdminSharedByUserId(anyLong());
        verify(users, never()).findSharedById(anyLong());
    }

    @Test
    void adminQueueAndDetailUseFreshNonLockingAuthorityOnly() {
        EmployeeProfile activeProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        activeProfile.setStaffActive(true);
        PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                "creation-hash", NOW, 10L, "Relocating");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 77L);
        when(users.findRoleValueById(9L)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(employeeProfiles.findByUserId(9L)).thenReturn(Optional.of(activeProfile));
        when(grants.hasEffectiveGlobalAdmin(9L)).thenReturn(true);
        when(requests.findAdminQueue(eq(PropertyRemovalRequestStatus.PENDING), eq(List.of()),
                eq(true), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(request), PageRequest.of(0, 20), false));
        when(requests.findById(77L)).thenReturn(Optional.of(request));
        when(listings.findById(42L)).thenReturn(Optional.of(listing));
        when(drafts.findOpenRevisions(eq(42L), anyList())).thenReturn(List.of());
        when(sessions.countAppointmentsForListing(eq(42L), anyList())).thenReturn(0L);

        var queue = service.adminQueue(9L, PropertyRemovalRequestStatus.PENDING,
                null, 0, 20);
        var detail = service.adminDetail(9L, 77L);

        assertEquals(77L, queue.items().get(0).requestId());
        assertEquals(77L, detail.request().requestId());
        verify(grants, times(2)).hasEffectiveGlobalAdmin(9L);
        verify(users, times(2)).findById(9L);
        verify(grants, never()).findEffectiveGlobalAdminSharedByUserId(anyLong());
        verify(users, never()).findSharedById(anyLong());
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
        verify(listings, never()).lockForSchedulingById(anyLong());
    }

    private static List<PropertyRemovalRequest> mixedRequests(int count) {
        List<PropertyRemovalRequestStatus> statuses = List.of(
                PropertyRemovalRequestStatus.PENDING,
                PropertyRemovalRequestStatus.APPROVED,
                PropertyRemovalRequestStatus.REJECTED,
                PropertyRemovalRequestStatus.WITHDRAWN);
        List<PropertyRemovalRequest> rows = new ArrayList<>();
        for (long id = 1L; id <= count; id++) {
            PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                    "mixed-hash-" + id, NOW.minusSeconds(id % 5), 10L, "Owner reason " + id);
            org.springframework.test.util.ReflectionTestUtils.setField(request, "id", id);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    request, "requestStatus", statuses.get(Math.toIntExact((id - 1) % statuses.size())));
            rows.add(request);
        }
        return rows;
    }

    private static List<PropertyRemovalRequest> requests(int count,
                                                          PropertyRemovalRequestStatus status) {
        List<PropertyRemovalRequest> rows = new ArrayList<>();
        for (long id = 1L; id <= count; id++) {
            PropertyRemovalRequest request = new PropertyRemovalRequest(42L, 5L, UUID.randomUUID(),
                    "hash-" + id, NOW, 10L, "Owner reason");
            org.springframework.test.util.ReflectionTestUtils.setField(request, "id", id);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    request, "requestStatus", status == null
                            ? PropertyRemovalRequestStatus.PENDING : status);
            rows.add(request);
        }
        return rows;
    }

    private static Listing listing(Long id) {
        RentalDetails row = new RentalDetails();
        row.setId(id);
        row.setTitle("Property " + id);
        row.setVersion(10L);
        row.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        row.setStatus(ListingStatus.ACTIVE);
        return row;
    }

    private static <T> org.springframework.data.domain.Slice<T> page(
            List<T> rows, Pageable pageable) {
        int start = Math.toIntExact(pageable.getOffset());
        int end = Math.min(rows.size(), start + pageable.getPageSize());
        return new SliceImpl<>(rows.subList(start, end), pageable, end < rows.size());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

