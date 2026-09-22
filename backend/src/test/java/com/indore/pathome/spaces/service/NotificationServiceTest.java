package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
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
        when(repository.findByTargetRoleInOrderByCreatedAtDesc(any())).thenReturn(new ArrayList<>());
    }

    @Test
    public void testRoleScopedNotificationFiltering() {
        SystemNotification adminNotif = new SystemNotification(TargetRole.ADMIN, null, "Admin Alert", "Payroll data", null, "PAYROLL", "warning");
        SystemNotification allNotif = new SystemNotification(TargetRole.ALL, null, "General Notice", "Maintenance update", null, "SYSTEM", "info");

        when(repository.findByTargetRoleInOrderByCreatedAtDesc(argThat(roles -> roles != null && roles.contains(TargetRole.ADMIN))))
                .thenReturn(List.of(adminNotif, allNotif));

        when(repository.findByTargetRoleInOrderByCreatedAtDesc(argThat(roles -> roles != null && roles.contains(TargetRole.TENANT) && !roles.contains(TargetRole.ADMIN))))
                .thenReturn(List.of(allNotif));

        List<SystemNotification> adminResults = notificationService.getNotificationsForRole(TargetRole.ADMIN, null);
        assertNotNull(adminResults);
        assertFalse(adminResults.isEmpty());

        List<SystemNotification> tenantResults = notificationService.getNotificationsForRole(TargetRole.TENANT, null);
        assertNotNull(tenantResults);
        assertEquals(1, tenantResults.size());
        assertEquals("General Notice", tenantResults.get(0).getTitle());
    }

    @Test
    public void testCreateNotificationAndMarkAsRead() {
        SystemNotification notif = new SystemNotification(TargetRole.EMPLOYEE, "emp123", "Lead Assigned", "New inquiry", null, "LEAD", "info");
        notif.setId(10L);

        when(repository.save(any(SystemNotification.class))).thenReturn(notif);
        when(repository.findById(10L)).thenReturn(Optional.of(notif));

        SystemNotification created = notificationService.createNotification(TargetRole.EMPLOYEE, "emp123", "Lead Assigned", "New inquiry", null, "LEAD", "info");
        assertNotNull(created);
        assertEquals(10L, created.getId());

        boolean readSuccess = notificationService.markAsRead(10L);
        assertTrue(readSuccess);
        assertTrue(notif.isRead());
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
}
