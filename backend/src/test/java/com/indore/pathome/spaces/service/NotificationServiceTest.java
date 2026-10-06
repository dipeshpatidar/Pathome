package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class NotificationServiceTest {

    @Mock
    private SystemNotificationRepository repository;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testCreateNotificationAndRecipientScopedMarkAsRead() {
        SystemNotification notif = new SystemNotification(TargetRole.EMPLOYEE, "emp123", "Lead Assigned", "New inquiry", null, "LEAD", "info");
        notif.setId(10L);

        when(repository.save(any(SystemNotification.class))).thenReturn(notif);
        when(repository.findByIdAndRecipientUserId(10L, "emp123")).thenReturn(Optional.of(notif));

        SystemNotification created = notificationService.createNotification(TargetRole.EMPLOYEE, "emp123", "Lead Assigned", "New inquiry", null, "LEAD", "info");
        assertNotNull(created);
        assertEquals(10L, created.getId());

        boolean readSuccess = notificationService.markAsReadForUser(10L, "emp123");
        assertTrue(readSuccess);
        assertTrue(notif.isRead());
        verify(repository, never()).findById(10L);
    }

    @Test
    public void crossUserMarkAsReadCannotLoadOrMutateNotification() {
        when(repository.findByIdAndRecipientUserId(10L, "other-user")).thenReturn(Optional.empty());

        assertFalse(notificationService.markAsReadForUser(10L, "other-user"));

        verify(repository).findByIdAndRecipientUserId(10L, "other-user");
        verify(repository, never()).save(any());
    }

    @Test
    public void privateFeedAndUnreadCountUseRecipientScopedRepositoryQueries() {
        SystemNotification ownNotification = new SystemNotification(TargetRole.TENANT, "tenant-a",
                "Visit updated", "Your visit time changed", null, "VISIT_SESSION", "info");
        when(repository.findByRecipientUserIdOrderByCreatedAtDesc("tenant-a"))
                .thenReturn(List.of(ownNotification));
        when(repository.countByRecipientUserIdAndIsReadFalse("tenant-a")).thenReturn(1L);

        assertEquals(List.of(ownNotification), notificationService.getNotificationsForUser("tenant-a"));
        assertEquals(1L, notificationService.getUnreadCountForUser("tenant-a"));

        verify(repository).findByRecipientUserIdOrderByCreatedAtDesc("tenant-a");
        verify(repository).countByRecipientUserIdAndIsReadFalse("tenant-a");
        verify(repository, never()).findByTargetRoleInOrderByCreatedAtDesc(any());
    }

    @Test
    public void testCreateNotificationWithEventKey_Idempotency() {
        String eventKey = "PROPERTY_PUBLISHED:draft-123";
        SystemNotification existingNotif = new SystemNotification(TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-123", "PROPERTY", "success");
        existingNotif.setId(20L);
        existingNotif.setEventKey(eventKey);

        when(repository.findByEventKey(eventKey)).thenReturn(Optional.of(existingNotif));

        Optional<SystemNotification> result = notificationService.createNotificationWithEventKey(
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-123", "PROPERTY", "success", eventKey
        );

        assertTrue(result.isPresent());
        assertEquals(20L, result.get().getId());
        assertEquals(eventKey, result.get().getEventKey());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    public void testCreateNotificationWithEventKey_ConcurrentRaceHandled() {
        String eventKey = "PROPERTY_PUBLISHED:draft-race";
        SystemNotification winner = new SystemNotification(TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-race", "PROPERTY", "success");
        winner.setId(30L);
        winner.setEventKey(eventKey);

        when(repository.findByEventKey(eventKey))
                .thenReturn(Optional.empty()) // Initial check: empty
                .thenReturn(Optional.of(winner)); // Post-exception check: winner found

        when(repository.saveAndFlush(any(SystemNotification.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint"));

        Optional<SystemNotification> result = notificationService.createNotificationWithEventKey(
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-race", "PROPERTY", "success", eventKey
        );

        assertTrue(result.isPresent());
        assertEquals(30L, result.get().getId());
        assertEquals(eventKey, result.get().getEventKey());
    }

    @Test
    public void testCreateNotificationWithEventKey_WithRequiresNewTransactionManager() {
        org.springframework.transaction.PlatformTransactionManager txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        notificationService.setTransactionManager(txManager);

        String eventKey = "PROPERTY_PUBLISHED:draft-tx-requires-new";
        SystemNotification created = new SystemNotification(TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-tx-requires-new", "PROPERTY", "success");
        created.setId(45L);
        created.setEventKey(eventKey);

        when(repository.findByEventKey(eventKey)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(SystemNotification.class))).thenReturn(created);

        Optional<SystemNotification> result = notificationService.createNotificationWithEventKey(
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-tx-requires-new", "PROPERTY", "success", eventKey
        );

        assertTrue(result.isPresent());
        assertEquals(45L, result.get().getId());
        assertEquals(eventKey, result.get().getEventKey());
        verify(txManager, times(1)).commit(txStatus);
    }

    @Test
    public void testSanitizeLegacyNotifications() {
        SystemNotification legacyNotif = new SystemNotification(
                TargetRole.TENANT,
                null,
                "Welcome to Divyavastu Spaces!",
                "Browse verified 100% direct listings in Vijay Nagar, Palasia, and Nanda Nagar with zero brokerage hassle.",
                "VIP Pass Status: 5 Free Visits Active",
                "PROPERTY",
                "success"
        );
        legacyNotif.setId(99L);

        when(repository.findAll()).thenReturn(List.of(legacyNotif));

        notificationService.sanitizeLegacyNotifications();

        assertEquals("Welcome to Pathome!", legacyNotif.getTitle());
        assertEquals("Explore rental listings in Vijay Nagar, Palasia, and Nanda Nagar and request property visits online.", legacyNotif.getMessage());
        assertEquals("Browse Rentals", legacyNotif.getDetails());
        verify(repository, times(1)).save(legacyNotif);
    }
}
