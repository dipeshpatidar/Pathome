package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deterministic JUnit 5 test suite verifying persistent property publication notifications,
 * exact wording, count semantics, and database-backed idempotency across all publication flows.
 */
public class PropertyPublishNotificationTest {

    private PropertyUploadDraftRepository draftRepository;
    private PropertyDraftMediaRepository draftMediaRepository;
    private MediaStagingService mediaStagingService;
    private ObjectMapper objectMapper;
    private ListingRepository listingRepository;
    private SystemNotificationRepository notificationRepository;
    private NotificationService notificationService;
    private PropertyDraftService draftService;

    private static final String ADMIN_ID = "admin@pathome.in";

    @BeforeEach
    public void setUp() {
        draftRepository = mock(PropertyUploadDraftRepository.class);
        draftMediaRepository = mock(PropertyDraftMediaRepository.class);
        mediaStagingService = mock(MediaStagingService.class);
        objectMapper = new ObjectMapper();
        listingRepository = mock(ListingRepository.class);
        notificationRepository = mock(SystemNotificationRepository.class);

        notificationService = spy(new NotificationService(notificationRepository));
        when(notificationRepository.findByTargetRoleInOrderByCreatedAtDesc(any())).thenReturn(new ArrayList<>());

        draftService = new PropertyDraftService(
                draftRepository,
                draftMediaRepository,
                mediaStagingService,
                objectMapper,
                null // null transaction manager -> direct in-tx execution
        );
        draftService.setListingRepository(listingRepository);
        draftService.setNotificationService(notificationService);
    }

    private PropertyUploadDraft createDraft(String adminId, String draftId, String draftType) {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setAdminId(adminId);
        draft.setDraftId(draftId);
        draft.setDraftType(draftType);
        return draft;
    }

    // ------------------------------------------------------------------------
    // TEST 1 — Normal Single Publication
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 1: Normal single publication creates exactly 1 persistent PROPERTY/ADMIN notification")
    public void testNormalSinglePublication() {
        String draftId = "draft-single-101";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("PUBLISHING");
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        when(notificationRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            SystemNotification n = inv.getArgument(0);
            n.setId(101L);
            return n;
        });

        // Act: complete publication for listing #75
        draftService.onPropertyPublished(ADMIN_ID, draftId, 75L);

        // Assert: Draft status is PUBLISHED
        assertEquals("PUBLISHED", draft.getStatus());
        assertEquals(75L, draft.getPublishedPropertyId());

        // Assert: exactly 1 notification dispatched
        verify(notificationRepository, times(1)).saveAndFlush(any(SystemNotification.class));

        SystemNotification saved = captor.getValue();
        assertEquals(TargetRole.ADMIN, saved.getTargetRole());
        assertEquals("PROPERTY", saved.getCategory());
        assertEquals("success", saved.getType());
        assertEquals("Property published successfully", saved.getTitle());
        assertEquals("1 property was published successfully.", saved.getMessage());
        assertTrue(saved.getDetails().contains("Draft ID: " + draftId));
        assertTrue(saved.getDetails().contains("Listing #75"));
        assertEquals("PROPERTY_PUBLISHED:" + draftId, saved.getEventKey());
    }

    // ------------------------------------------------------------------------
    // TEST 2 — Normal Batch Publication (3 properties)
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 2: Normal batch publication (3 properties) creates exactly 1 notification with count = 3 and batch wording")
    public void testNormalBatchPublication() {
        String draftId = "draft-batch-301";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "BATCH");
        draft.setStatus("PUBLISHING");

        // Payload with 3 cards
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode cards = objectMapper.createArrayNode();
        cards.add(objectMapper.createObjectNode().put("id", "c1").put("title", "Viman"));
        cards.add(objectMapper.createObjectNode().put("id", "c2").put("title", "Kali"));
        cards.add(objectMapper.createObjectNode().put("id", "c3").put("title", "Mhow"));
        payload.set("stagedCards", cards);
        draft.setPayload(payload.toString());

        when(draftRepository.findByDraftIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        when(notificationRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            SystemNotification n = inv.getArgument(0);
            n.setId(301L);
            return n;
        });

        // Act: all 3 cards completed
        List<Map<String, Object>> completedListings = List.of(
                Map.of("cardId", "c1", "listingId", 80L, "title", "Viman"),
                Map.of("cardId", "c2", "listingId", 81L, "title", "Kali"),
                Map.of("cardId", "c3", "listingId", 82L, "title", "Mhow")
        );
        draftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c1", "c2", "c3"), completedListings);

        // Assert: Draft status is PUBLISHED
        assertEquals("PUBLISHED", draft.getStatus());

        // Assert: exactly 1 notification dispatched
        verify(notificationRepository, times(1)).saveAndFlush(any(SystemNotification.class));

        SystemNotification saved = captor.getValue();
        assertEquals(TargetRole.ADMIN, saved.getTargetRole());
        assertEquals("PROPERTY", saved.getCategory());
        assertEquals("success", saved.getType());
        assertEquals("3 properties published successfully", saved.getTitle());
        assertEquals("3 properties were published successfully.", saved.getMessage());
        assertTrue(saved.getDetails().contains("Draft ID: " + draftId));
        assertTrue(saved.getDetails().contains("#80"));
        assertTrue(saved.getDetails().contains("#81"));
        assertTrue(saved.getDetails().contains("#82"));
        assertTrue(saved.getDetails().contains("3 properties published"));
        assertEquals("PROPERTY_PUBLISHED:" + draftId, saved.getEventKey());
    }

    // ------------------------------------------------------------------------
    // TEST 3 — Interrupted Then Resume
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 3: Interrupted batch does NOT notify prematurely, then creates exactly 1 notification upon Resume")
    public void testInterruptedThenResume() {
        String draftId = "draft-batch-interrupted";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "BATCH");
        draft.setStatus("PUBLISHING");

        // Payload with 2 cards
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode cards = objectMapper.createArrayNode();
        cards.add(objectMapper.createObjectNode().put("id", "c1").put("title", "Viman"));
        cards.add(objectMapper.createObjectNode().put("id", "c2").put("title", "Kali"));
        payload.set("stagedCards", cards);
        draft.setPayload(payload.toString());

        when(draftRepository.findByDraftIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        when(notificationRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            SystemNotification n = inv.getArgument(0);
            n.setId(401L);
            return n;
        });

        // Phase 1: Only card c1 finishes; card c2 was interrupted
        draftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c1"), List.of(
                Map.of("cardId", "c1", "listingId", 80L, "title", "Viman")
        ));

        // Assert Phase 1: Incomplete -> draft remains DRAFT/PUBLISHING, ZERO notifications
        assertNotEquals("PUBLISHED", draft.getStatus());
        verify(notificationRepository, never()).saveAndFlush(any());

        // Phase 2: Resume finishes card c2
        draftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c2"), List.of(
                Map.of("cardId", "c2", "listingId", 81L, "title", "Kali")
        ));

        // Assert Phase 2: Draft reaches PUBLISHED, exactly 1 notification dispatched
        assertEquals("PUBLISHED", draft.getStatus());
        verify(notificationRepository, times(1)).saveAndFlush(any(SystemNotification.class));
        assertEquals("2 properties published successfully", captor.getValue().getTitle());
        assertEquals("PROPERTY_PUBLISHED:" + draftId, captor.getValue().getEventKey());
    }

    // ------------------------------------------------------------------------
    // TEST 4 — Failed Media Then Successful Retry/Reconciliation
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 4: Failed media retry reconciliation finalizes draft and creates exactly 1 notification")
    public void testFailedMediaThenSuccessfulRetry() {
        String draftId = "draft-retry-final";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "BATCH");
        draft.setStatus("PUBLISHING");

        // Payload with 1 remaining card (staged media failed previously)
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode cards = objectMapper.createArrayNode();
        cards.add(objectMapper.createObjectNode().put("id", "c3").put("title", "Mhow"));
        ArrayNode completed = objectMapper.createArrayNode();
        completed.add(objectMapper.createObjectNode().put("cardId", "c1").put("listingId", 80L).put("title", "Viman"));
        completed.add(objectMapper.createObjectNode().put("cardId", "c2").put("listingId", 81L).put("title", "Kali"));
        payload.set("stagedCards", cards);
        payload.set("completedListings", completed);
        draft.setPayload(payload.toString());

        when(draftRepository.findByDraftIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        when(notificationRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            SystemNotification n = inv.getArgument(0);
            n.setId(501L);
            return n;
        });

        // Act: card c3 retry completes successfully
        draftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c3"), List.of(
                Map.of("cardId", "c3", "listingId", 82L, "title", "Mhow")
        ));

        // Assert: Draft reaches PUBLISHED, exactly 1 notification covering all 3 completed listings
        assertEquals("PUBLISHED", draft.getStatus());
        verify(notificationRepository, times(1)).saveAndFlush(any(SystemNotification.class));
        assertEquals("3 properties published successfully", captor.getValue().getTitle());
        assertTrue(captor.getValue().getDetails().contains("#80"));
        assertTrue(captor.getValue().getDetails().contains("#81"));
        assertTrue(captor.getValue().getDetails().contains("#82"));
    }

    // ------------------------------------------------------------------------
    // TEST 5 — Repeated Finalization (Idempotency)
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 5: Repeated finalization for already-PUBLISHED draft does NOT create a second notification")
    public void testRepeatedFinalizationIdempotency() {
        String draftId = "draft-already-published";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("PUBLISHED");
        draft.setPublishedPropertyId(88L);
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));

        // Act: Call finalization again
        draftService.onPropertyPublished(ADMIN_ID, draftId, 88L);

        // Assert: Early exit on status == PUBLISHED, 0 saveAndFlush invocations
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------------
    // TEST 6 — Concurrency / Idempotency Race Handling
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 6: Two competing completion attempts for same draft identity are caught by database uniqueness")
    public void testConcurrencyIdempotencyRace() {
        String draftId = "draft-race-idempotent";
        String eventKey = "PROPERTY_PUBLISHED:" + draftId;

        SystemNotification winner = new SystemNotification(TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: " + draftId, "PROPERTY", "success");
        winner.setId(99L);
        winner.setEventKey(eventKey);

        // Simulate Thread 1 created notification; Thread 2 hits unique constraint
        when(notificationRepository.findByEventKey(eventKey))
                .thenReturn(Optional.empty()) // First check: not found yet
                .thenReturn(Optional.of(winner)); // Second check after exception: found winner

        when(notificationRepository.saveAndFlush(any(SystemNotification.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint uk_notif_event_key"));

        // Act: Thread 2 attempts to create notification with same eventKey
        Optional<SystemNotification> result = notificationService.createNotificationWithEventKey(
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: " + draftId, "PROPERTY", "success", eventKey
        );

        // Assert: Cleanly caught DataIntegrityViolationException and returned winner without error
        assertTrue(result.isPresent());
        assertEquals(99L, result.get().getId());
        assertEquals(eventKey, result.get().getEventKey());
    }

    // ------------------------------------------------------------------------
    // TEST 7 — Incomplete Publication
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 7: Incomplete publication with cards remaining creates ZERO publication notifications")
    public void testIncompletePublicationNoNotification() {
        String draftId = "draft-batch-incomplete";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "BATCH");
        draft.setStatus("PUBLISHING");

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode cards = objectMapper.createArrayNode();
        cards.add(objectMapper.createObjectNode().put("id", "c1"));
        cards.add(objectMapper.createObjectNode().put("id", "c2"));
        payload.set("stagedCards", cards);
        draft.setPayload(payload.toString());

        when(draftRepository.findByDraftIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));

        // Act: Only card c1 reconciled; card c2 remains
        draftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c1"), List.of(
                Map.of("cardId", "c1", "listingId", 90L)
        ));

        // Assert: Draft is still not PUBLISHED; 0 notifications
        assertNotEquals("PUBLISHED", draft.getStatus());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------------
    // TEST 8 — Notification Metadata / Content Verification
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 8: Verify exact metadata fields: targetRole=ADMIN, category=PROPERTY, type=success, correct eventKey and details")
    public void testNotificationMetadataAndContent() {
        String draftId = "draft-meta-check";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("DRAFT");
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        when(notificationRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        draftService.onPropertyPublished(ADMIN_ID, draftId, 105L);

        SystemNotification notif = captor.getValue();
        assertNotNull(notif);
        assertEquals(TargetRole.ADMIN, notif.getTargetRole());
        assertNull(notif.getRecipientUserId(), "Admin broadcast notification has null recipientUserId");
        assertEquals("PROPERTY", notif.getCategory());
        assertEquals("success", notif.getType());
        assertEquals("Property published successfully", notif.getTitle());
        assertEquals("1 property was published successfully.", notif.getMessage());
        assertEquals("PROPERTY_PUBLISHED:draft-meta-check", notif.getEventKey());
        assertEquals("Draft ID: draft-meta-check • Listing #105 • 1 property published", notif.getDetails());
        assertFalse(notif.isRead());
    }

    // ------------------------------------------------------------------------
    // TEST 9 — Single Publication After-Commit Synchronization
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 9: Single publication registers afterCommit synchronization; does NOT notify before commit, notifies exactly once after commit")
    public void testSinglePublicationAfterCommit_DispatchIsolation() {
        String draftId = "draft-after-commit-1";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("PUBLISHING");
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            draftService.onPropertyPublished(ADMIN_ID, draftId, 120L);

            // Assert: Before commit, 0 notifications dispatched
            verify(notificationRepository, never()).saveAndFlush(any());

            // Trigger commit
            TransactionSynchronizationUtils.triggerAfterCommit();

            // Assert: After commit, exactly 1 notification dispatched
            verify(notificationRepository, times(1)).saveAndFlush(any(SystemNotification.class));
            assertEquals("PUBLISHED", draft.getStatus());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------------
    // TEST 10 — Single Publication Rollback (Zero Notification)
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 10: Single publication rollback never triggers afterCommit; creates ZERO notification")
    public void testSinglePublicationRollback_ZeroNotification() {
        String draftId = "draft-rollback-single";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("PUBLISHING");
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            draftService.onPropertyPublished(ADMIN_ID, draftId, 121L);

            // Assert: before commit, zero notifications
            verify(notificationRepository, never()).saveAndFlush(any());

            // Simulate transaction rollback (triggerAfterCommit is NOT called, only triggerAfterCompletion(STATUS_ROLLED_BACK))
            TransactionSynchronizationUtils.triggerAfterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

            // Assert: after rollback, still ZERO notifications
            verify(notificationRepository, never()).saveAndFlush(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------------
    // TEST 11 — Batch Publication Rollback (Zero Notification)
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 11: Batch publication transaction rollback prevents Phase C post-commit notification dispatch")
    public void testBatchPublicationRollback_ZeroNotification() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doThrow(new RuntimeException("Simulated DB deadlock or rollback during batch draft finalization"))
                .when(txManager).commit(any());

        PropertyDraftService transactionalDraftService = new PropertyDraftService(
                draftRepository,
                draftMediaRepository,
                mediaStagingService,
                objectMapper,
                txManager
        );
        transactionalDraftService.setNotificationService(notificationService);

        String draftId = "draft-batch-rollback";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "BATCH");
        draft.setStatus("PUBLISHING");

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode cards = objectMapper.createArrayNode();
        cards.add(objectMapper.createObjectNode().put("id", "c1").put("title", "Batch 1"));
        payload.set("stagedCards", cards);
        draft.setPayload(payload.toString());

        when(draftRepository.findByDraftIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));

        List<Map<String, Object>> completedListings = List.of(
                Map.of("cardId", "c1", "listingId", 80L, "title", "Batch 1")
        );

        assertThrows(RuntimeException.class, () ->
                transactionalDraftService.reconcileBatchDraft(ADMIN_ID, draftId, List.of("c1"), completedListings)
        );

        // Assert: 0 notifications dispatched because transaction rolled back before Phase C post-commit
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------------
    // TEST 12 — Notification Persistence Failure After Publication Commit
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("TEST 12: Notification persistence failure after publication commit does NOT fail or corrupt published draft")
    public void testNotificationFailureAfterPublicationCommit_PublicationRemainsCommitted() {
        String draftId = "draft-notif-fail";
        PropertyUploadDraft draft = createDraft(ADMIN_ID, draftId, "SINGLE");
        draft.setStatus("PUBLISHING");
        draft.setPayload("{}");

        when(draftRepository.findByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Optional.of(draft));
        when(draftMediaRepository.findAllByDraftIdAndAdminId(draftId, ADMIN_ID)).thenReturn(Collections.emptyList());

        // Simulate notification repository throwing an unexpected database error
        when(notificationRepository.saveAndFlush(any()))
                .thenThrow(new RuntimeException("Simulated unexpected database failure saving notification"));

        // Act: complete publication
        draftService.onPropertyPublished(ADMIN_ID, draftId, 130L);

        // Assert: draft is still successfully PUBLISHED and linked to listing #130
        assertEquals("PUBLISHED", draft.getStatus());
        assertEquals(130L, draft.getPublishedPropertyId());
    }
}
