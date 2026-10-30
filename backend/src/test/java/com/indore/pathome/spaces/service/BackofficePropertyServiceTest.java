package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyCommandReceipt;
import com.indore.pathome.spaces.entity.PropertyPublicationVersion;
import com.indore.pathome.spaces.entity.PropertyRevisionEvent;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import com.indore.pathome.spaces.dto.backoffice.StaffPropertySnapshot;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyCommandReceiptRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyPublicationOutboxRepository;
import com.indore.pathome.spaces.repository.PropertyPublicationVersionRepository;
import com.indore.pathome.spaces.repository.PropertyRevisionEventRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackofficePropertyServiceTest {
    private static final Long USER_ID = 9L;
    private static final Long LISTING_ID = 42L;
    private static final Long CITY_ID = 7L;

    private final ListingRepository listings = mock(ListingRepository.class);
    private final LocalityRepository localities = mock(LocalityRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final PropertyDraftMediaRepository draftMedia = mock(PropertyDraftMediaRepository.class);
    private final PropertyMediaAssetRepository assets = mock(PropertyMediaAssetRepository.class);
    private final PropertyPublicationVersionRepository publications =
            mock(PropertyPublicationVersionRepository.class);
    private final PropertyRevisionEventRepository revisionEvents =
            mock(PropertyRevisionEventRepository.class);
    private final PropertyCommandReceiptRepository receipts = mock(PropertyCommandReceiptRepository.class);
    private final PropertyPublicationOutboxRepository outbox =
            mock(PropertyPublicationOutboxRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BackofficePropertyAccessService access = mock(BackofficePropertyAccessService.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PropertyPublicationSnapshotAdapter publicationSnapshots =
            mock(PropertyPublicationSnapshotAdapter.class);
    private final PropertyActivityCursorCodec activityCursors = new PropertyActivityCursorCodec();
    private final BackofficePropertyService service = new BackofficePropertyService(listings, localities,
            drafts, draftMedia, assets, publications, revisionEvents, receipts, outbox, users, access,
            audit, clock, new ObjectMapper().findAndRegisterModules(), entityManager, publicationSnapshots,
            activityCursors);

    private RentalDetails listing;

    @BeforeEach
    void setUp() {
        listing = new RentalDetails();
        listing.setId(LISTING_ID);
        listing.setVersion(10L);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setCanonicalLocalityId(11L);
        listing.setTitle("Authorized property");

        SupportedCity city = new SupportedCity("indore", "Indore", true);
        ReflectionTestUtils.setField(city, "id", CITY_ID);
        Locality locality = new Locality("Indore", "Vijay Nagar", null, 0, 0.0);
        locality.setId(11L);
        locality.setSupportedCity(city);

        when(listings.findById(LISTING_ID)).thenReturn(Optional.of(listing));
        when(localities.findById(11L)).thenReturn(Optional.of(locality));
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_EDIT_PUBLISH))
                .thenReturn(List.of(CITY_ID));
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_AUDIT_READ)).thenReturn(List.of());
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_LIFECYCLE)).thenReturn(List.of());
        when(clock.now()).thenReturn(Instant.parse("2026-10-24T08:00:00Z"));
    }

    @Test
    void lifecycleReplayChecksCurrentCommandDutyBeforeReturningACommittedOutcome() {
        UUID operationId = UUID.randomUUID();
        when(receipts.findByOperationId(operationId)).thenReturn(Optional.of(
                new PropertyCommandReceipt(operationId, "PROPERTY_LIFECYCLE", LISTING_ID,
                        "irrelevant-prior-payload", USER_ID, 11L,
                        Instant.parse("2026-10-24T07:00:00Z"))));
        when(access.requireForMutation(USER_ID, StaffCapability.PROPERTY_LIFECYCLE, CITY_ID))
                .thenThrow(new AccessDeniedException("revoked"));

        assertThrows(AccessDeniedException.class, () -> service.lifecycle(USER_ID, LISTING_ID,
                operationId, 10L, "PAUSE", "Operational correction"));
    }

    @Test
    void competingLifecycleReceiptAppearingBeforeTheLockIsReplayedAfterFreshAuthority() {
        UUID operationId = UUID.randomUUID();
        String reason = "Operational correction";
        String payloadHash = sha256("PAUSE|42|10|" + reason);
        PropertyCommandReceipt receipt = new PropertyCommandReceipt(operationId,
                "PROPERTY_LIFECYCLE", LISTING_ID, payloadHash, USER_ID, 11L,
                Instant.parse("2026-10-24T07:30:00Z"));
        BackofficePropertyAccessService.GrantEvidence evidence =
                new BackofficePropertyAccessService.GrantEvidence(USER_ID, CITY_ID,
                        StaffCapability.PROPERTY_LIFECYCLE.name(), 99L, List.of(99L));
        when(access.requireForMutation(USER_ID, StaffCapability.PROPERTY_LIFECYCLE, CITY_ID))
                .thenReturn(evidence);
        when(receipts.findByOperationId(operationId))
                .thenReturn(Optional.empty(), Optional.of(receipt));
        when(listings.lockForSchedulingById(LISTING_ID)).thenReturn(Optional.of(LISTING_ID));
        org.mockito.Mockito.doAnswer(invocation -> {
            listing.setVersion(11L);
            listing.setWorkflowStatus(ListingWorkflowStatus.PAUSED);
            return null;
        }).when(entityManager).refresh(listing, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        when(publications.currentVersion(LISTING_ID)).thenReturn(3L);

        var result = service.lifecycle(USER_ID, LISTING_ID, operationId, 10L, "PAUSE", reason);

        assertEquals(11L, result.version());
        assertEquals(3L, result.publicationVersion());
        verify(access).revalidate(USER_ID, StaffCapability.PROPERTY_LIFECYCLE, CITY_ID);
        verify(listings, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void fullHistoryRequiresAuditDutyAndNeverFallsBackToEditDuty() {
        doThrow(new AccessDeniedException("audit duty revoked"))
                .when(access).requireRead(USER_ID, StaffCapability.PROPERTY_AUDIT_READ, CITY_ID);

        assertThrows(AccessDeniedException.class, () -> service.activity(USER_ID, LISTING_ID, 0));
    }

    @Test
    void activityOutsideEveryCurrentCityScopeIsAGenericHiddenProperty() {
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_EDIT_PUBLISH))
                .thenReturn(List.of());
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_AUDIT_READ))
                .thenReturn(List.of());
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_LIFECYCLE))
                .thenReturn(List.of());
        doThrow(new AccessDeniedException("foreign City"))
                .when(access).requireRead(USER_ID, StaffCapability.PROPERTY_AUDIT_READ, CITY_ID);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.activity(USER_ID, LISTING_ID, 0));
    }

    @Test
    void discardPreservesUnicodeReasonAndInvestigableDraftPayload() {
        BackofficePropertyAccessService.GrantEvidence evidence =
                new BackofficePropertyAccessService.GrantEvidence(USER_ID, CITY_ID,
                        StaffCapability.PROPERTY_EDIT_PUBLISH.name(), 99L, List.of(99L));
        when(access.requireForMutation(USER_ID, StaffCapability.PROPERTY_EDIT_PUBLISH, CITY_ID))
                .thenReturn(evidence);
        when(listings.lockForSchedulingById(LISTING_ID)).thenReturn(Optional.of(LISTING_ID));
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("staff-unicode");
        draft.setPublishedPropertyId(LISTING_ID);
        draft.setStaffMakerUserId(USER_ID);
        draft.setRevisionMode("STAFF_DIRECT");
        draft.setStatus("DRAFT");
        draft.setVersion(4);
        draft.setPayload("{\"title\":\"मूल विवरण\",\"description\":\"पुराना पाठ\"}");
        when(drafts.findStaffDraftForUpdate("staff-unicode", USER_ID)).thenReturn(Optional.of(draft));
        when(drafts.saveAndFlush(draft)).thenReturn(draft);

        service.discard(USER_ID, LISTING_ID, "staff-unicode", "  मरम्मत — आवश्यक, कृपया!  ");

        assertEquals("{\"title\":\"मूल विवरण\",\"description\":\"पुराना पाठ\"}", draft.getPayload());
        ArgumentCaptor<PropertyRevisionEvent> event = ArgumentCaptor.forClass(PropertyRevisionEvent.class);
        verify(revisionEvents).save(event.capture());
        assertEquals("मरम्मत — आवश्यक, कृपया!", event.getValue().getDetails().get("reason"));
        assertEquals("DRAFT_DISCARD", event.getValue().getDetails().get("reasonCode"));
        assertTrue(!event.getValue().getDetails().containsKey("reasonCode")
                || !String.valueOf(event.getValue().getDetails().get("reasonCode")).contains("मरम्मत"));
    }

    @Test
    void activityUsesOneLockedCohortAndCursorTraversalWithoutSkippingEqualTimestampRows() {
        Instant timestamp = Instant.parse("2026-10-24T08:00:00.123456Z");
        List<PropertyPublicationVersion> publicationsPage = new ArrayList<>();
        for (long id = 21; id >= 1; id--) {
            publicationsPage.add(publication(id, timestamp));
        }
        List<PropertyRevisionEvent> revisionsPage = List.of(revision(1L, timestamp));

        when(listings.lockForSchedulingById(LISTING_ID)).thenReturn(Optional.of(LISTING_ID));
        when(publications.latestRowId(LISTING_ID)).thenReturn(21L);
        when(revisionEvents.latestRowId(LISTING_ID)).thenReturn(1L);
        when(publications.findActivityCohort(eq(LISTING_ID), eq(21L), any()))
                .thenReturn(publicationsPage);
        when(revisionEvents.findActivityCohort(eq(LISTING_ID), eq(1L), any()))
                .thenReturn(revisionsPage);
        when(users.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of());
        when(publications.findByIdAndListingId(21L, LISTING_ID))
                .thenReturn(Optional.of(publicationsPage.get(0)));
        when(publications.findByIdAndListingId(2L, LISTING_ID))
                .thenReturn(Optional.of(publicationsPage.get(19)));
        when(revisionEvents.findByIdAndListingId(1L, LISTING_ID))
                .thenReturn(Optional.of(revisionsPage.get(0)));

        var first = service.activity(USER_ID, LISTING_ID, 0, null, 20);
        verify(listings).lockForSchedulingById(LISTING_ID);
        assertEquals(20, first.items().size());
        assertTrue(first.hasMore());
        assertNotNull(first.nextCursor());
        assertEquals(20, first.items().stream().map(item -> item.eventId()).distinct().count());

        when(publications.findActivityAfterAnchor(eq(LISTING_ID), eq(21L), eq(timestamp),
                eq(0), eq(2L), any())).thenReturn(List.of(publicationsPage.get(20)));
        when(revisionEvents.findActivityAfterAnchor(eq(LISTING_ID), eq(1L), eq(timestamp),
                eq(0), eq(2L), any())).thenReturn(revisionsPage);

        var second = service.activity(USER_ID, LISTING_ID, 0, first.nextCursor(), 20);
        assertEquals(List.of("PUBLICATION:1", "REVISION:1"),
                second.items().stream().map(item -> item.eventId()).toList());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
    }

    @Test
    void oneHundredAndTwoActivityRowsTraverseExactlyOnceAtLimitsOneTwentyAndFifty() {
        Instant timestamp = Instant.parse("2026-10-24T08:00:00.123456Z");
        List<PropertyPublicationVersion> publicationRows = new ArrayList<>();
        List<PropertyRevisionEvent> revisionRows = new ArrayList<>();
        List<PropertyActivityOrdering.Entry> sourceRows = new ArrayList<>();
        for (long id = 1L; id <= 51L; id++) {
            PropertyPublicationVersion publication = publication(id, timestamp);
            PropertyRevisionEvent revision = revision(id, timestamp);
            publicationRows.add(publication);
            revisionRows.add(revision);
            sourceRows.add(new PropertyActivityOrdering.Entry(null,
                    PropertyActivityOrdering.Source.PUBLICATION, id, timestamp));
            sourceRows.add(new PropertyActivityOrdering.Entry(null,
                    PropertyActivityOrdering.Source.REVISION, id, timestamp));
        }
        sourceRows.sort(PropertyActivityOrdering.ORDER);

        when(listings.lockForSchedulingById(LISTING_ID)).thenReturn(Optional.of(LISTING_ID));
        when(publications.latestRowId(LISTING_ID)).thenReturn(51L);
        when(revisionEvents.latestRowId(LISTING_ID)).thenReturn(51L);
        when(users.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of());
        publicationRows.forEach(row -> when(publications.findByIdAndListingId(row.getId(), LISTING_ID))
                .thenReturn(Optional.of(row)));
        revisionRows.forEach(row -> when(revisionEvents.findByIdAndListingId(row.getId(), LISTING_ID))
                .thenReturn(Optional.of(row)));
        when(publications.findActivityCohort(eq(LISTING_ID), eq(51L), any(Pageable.class)))
                .thenAnswer(call -> publicationRows.stream()
                        .filter(row -> row.getId() <= 51L)
                        .sorted((left, right) -> PropertyActivityOrdering.ORDER.compare(
                                new PropertyActivityOrdering.Entry(null,
                                        PropertyActivityOrdering.Source.PUBLICATION,
                                        left.getId(), left.getPublishedAt()),
                                new PropertyActivityOrdering.Entry(null,
                                        PropertyActivityOrdering.Source.PUBLICATION,
                                        right.getId(), right.getPublishedAt())))
                        .limit(call.<Pageable>getArgument(2).getPageSize()).toList());
        when(revisionEvents.findActivityCohort(eq(LISTING_ID), eq(51L), any(Pageable.class)))
                .thenAnswer(call -> revisionRows.stream()
                        .sorted((left, right) -> PropertyActivityOrdering.ORDER.compare(
                                new PropertyActivityOrdering.Entry(null,
                                        PropertyActivityOrdering.Source.REVISION,
                                        left.getId(), left.getOccurredAt()),
                                new PropertyActivityOrdering.Entry(null,
                                        PropertyActivityOrdering.Source.REVISION,
                                        right.getId(), right.getOccurredAt())))
                        .limit(call.<Pageable>getArgument(2).getPageSize()).toList());
        when(publications.findActivityAfterAnchor(eq(LISTING_ID), eq(51L), eq(timestamp),
                anyInt(), anyLong(), any(Pageable.class))).thenAnswer(call -> after(
                        sourceRows, PropertyActivityOrdering.Source.PUBLICATION,
                        call.getArgument(3), call.getArgument(4),
                        call.<Pageable>getArgument(5)).stream()
                .map(row -> publicationRows.stream()
                        .filter(candidate -> candidate.getId().equals(row.rowId())).findFirst().orElseThrow())
                .toList());
        when(revisionEvents.findActivityAfterAnchor(eq(LISTING_ID), eq(51L), eq(timestamp),
                anyInt(), anyLong(), any(Pageable.class))).thenAnswer(call -> after(
                        sourceRows, PropertyActivityOrdering.Source.REVISION,
                        call.getArgument(3), call.getArgument(4),
                        call.<Pageable>getArgument(5)).stream()
                .map(row -> revisionRows.stream()
                        .filter(candidate -> candidate.getId().equals(row.rowId())).findFirst().orElseThrow())
                .toList());

        for (int limit : List.of(1, 20, 50)) {
            List<String> traversed = new ArrayList<>();
            var page = service.activity(USER_ID, LISTING_ID, 0, null, limit);
            traversed.addAll(page.items().stream().map(BackofficeDtos.Activity::eventId).toList());
            int cursorPages = 0;
            while (page.hasMore() && page.nextCursor() != null) {
                page = service.activity(USER_ID, LISTING_ID, 0, page.nextCursor(), limit);
                traversed.addAll(page.items().stream().map(BackofficeDtos.Activity::eventId).toList());
                cursorPages++;
                assertTrue(cursorPages < 110, "cursor traversal must terminate");
            }

            assertEquals(102, traversed.size());
            assertEquals(102, traversed.stream().distinct().count());
            assertEquals("PUBLICATION:51", traversed.get(0));
            if (limit == 1) assertTrue(cursorPages > 100);
            assertFalse(page.hasMore());
        }
    }

    @Test
    void activityRejectsInvalidLegacyBoundsAndCursorPageConflictsBeforeQueries() {
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, -1, null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, 101, null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, 0, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, 0, null, 51));
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, 1, "not-a-valid-cursor", 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.activity(USER_ID, LISTING_ID, 1, " ", 20));
        assertEquals(101, BackofficePropertyService.nextCursorPage(100));
        assertEquals(Integer.MAX_VALUE, BackofficePropertyService.nextCursorPage(Integer.MAX_VALUE - 1));
        assertNull(BackofficePropertyService.nextCursorPage(Integer.MAX_VALUE));
    }

    @Test
    void commandReadbackRequiresTheDutyThatOwnsThatCommand() {
        UUID operationId = UUID.randomUUID();
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_EDIT_PUBLISH))
                .thenReturn(List.of());
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_AUDIT_READ))
                .thenReturn(List.of());
        when(access.inventoryCityIds(USER_ID, StaffCapability.PROPERTY_LIFECYCLE))
                .thenReturn(List.of(CITY_ID));
        doThrow(new AccessDeniedException("edit duty absent"))
                .when(access).requireRead(USER_ID, StaffCapability.PROPERTY_EDIT_PUBLISH, CITY_ID);
        doThrow(new AccessDeniedException("audit duty absent"))
                .when(access).requireRead(USER_ID, StaffCapability.PROPERTY_AUDIT_READ, CITY_ID);
        when(receipts.findByOperationId(operationId)).thenReturn(Optional.of(
                new PropertyCommandReceipt(operationId, "PROPERTY_PUBLISH", LISTING_ID,
                        "hash", USER_ID, 2L, Instant.parse("2026-10-24T07:00:00Z"))));

        assertThrows(AccessDeniedException.class,
                () -> service.commandOutcome(USER_ID, LISTING_ID, operationId));
    }

    @Test
    void mediaBusinessDiffIgnoresTemporaryDraftIdsButDetectsCaptionsAndCover() {
        List<StaffPropertySnapshot.Media> first = List.of(
                new StaffPropertySnapshot.Media(10L, "draft-a", "a.jpg", MediaType.IMAGE,
                        RoomTag.LIVING_ROOM, "Living", 0, true, "UPLOADED"));
        List<StaffPropertySnapshot.Media> sameBusiness = List.of(
                new StaffPropertySnapshot.Media(10L, "draft-b", "a.jpg", MediaType.IMAGE,
                        RoomTag.LIVING_ROOM, "Living", 0, true, "UPLOADED"));
        List<StaffPropertySnapshot.Media> changed = List.of(
                new StaffPropertySnapshot.Media(10L, "draft-c", "a.jpg", MediaType.IMAGE,
                        RoomTag.LIVING_ROOM, "Sunlit living room", 0, true, "UPLOADED"));

        assertTrue(BackofficePropertyService.sameMediaBusinessContent(first, sameBusiness));
        assertFalse(BackofficePropertyService.sameMediaBusinessContent(first, changed));
    }

    private static PropertyPublicationVersion publication(long id, Instant timestamp) {
        PropertyPublicationVersion row = new PropertyPublicationVersion(LISTING_ID, id, "revision-" + id,
                "ADMIN_LISTING_PUBLICATION", 3L, Map.of(), null,
                Map.<String, Object>of("title", "Home " + id), Map.of(), timestamp);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private static PropertyRevisionEvent revision(long id, Instant timestamp) {
        PropertyRevisionEvent row = new PropertyRevisionEvent(LISTING_ID, "draft-" + id, "PUBLISHED",
                3L, timestamp, 1, Map.<String, Object>of());
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private static List<PropertyActivityOrdering.Entry> after(
            List<PropertyActivityOrdering.Entry> rows,
            PropertyActivityOrdering.Source source,
            int anchorRank,
            long anchorRowId,
            Pageable pageable) {
        PropertyActivityOrdering.Source anchorSource = anchorRank == 0
                ? PropertyActivityOrdering.Source.PUBLICATION
                : PropertyActivityOrdering.Source.REVISION;
        PropertyActivityOrdering.Entry anchor = rows.stream()
                .filter(row -> row.source() == anchorSource && row.rowId() == anchorRowId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("anchor is absent"));
        return rows.stream()
                .filter(row -> row.source() == source)
                .filter(row -> PropertyActivityOrdering.isOlder(row, anchor))
                .limit(pageable.getPageSize())
                .toList();
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
