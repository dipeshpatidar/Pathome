package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.NotificationAuthorizationClass;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.entity.User;
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

    @Mock
    private UserRepository users;

    @Mock
    private EmployeeProfileRepository employeeProfiles;

    @Mock
    private LessorProfileRepository lessorProfiles;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testCreateNotificationAndRecipientScopedMarkAsRead() {
        SystemNotification notif = new SystemNotification(TargetRole.EMPLOYEE, "123", "Lead Assigned", "New inquiry", null, "LEAD", "info");
        notif.setId(10L);

        when(repository.save(any(SystemNotification.class))).thenReturn(notif);
        when(repository.markVisibleReadForUser(eq(10L), eq(123L), any())).thenReturn(1);

        SystemNotification created = notificationService.createNotification(TargetRole.EMPLOYEE, "123", "Lead Assigned", "New inquiry", null, "LEAD", "info", NotificationAuthorizationClass.RECIPIENT);
        assertNotNull(created);
        assertEquals(10L, created.getId());

        boolean readSuccess = notificationService.markAsReadForUser(10L, "123");
        assertTrue(readSuccess);
        verify(repository, never()).findById(10L);
    }

    @Test
    public void adminDefaultAllDirectNoticeUsesPersistedCustomerRecipientAndKeepsStaffQuarantined() {
        User customer = user(71L, Role.ROLE_TENANT);
        when(users.findById(71L)).thenReturn(Optional.of(customer));
        when(employeeProfiles.findByUserId(71L)).thenReturn(Optional.empty());
        when(repository.save(any(SystemNotification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SystemNotification directCustomerNotice = notificationService.createAdminNotification(TargetRole.ALL, "71",
                "Direct notice", "Customer message", null, "SYSTEM", "info");

        assertEquals(NotificationAuthorizationClass.RECIPIENT, directCustomerNotice.getAuthorizationClass());
        assertNull(directCustomerNotice.getOperationalSessionId());

        User bootstrapStaffWithCustomerRole = user(72L, Role.ROLE_TENANT);
        when(users.findById(72L)).thenReturn(Optional.of(bootstrapStaffWithCustomerRole));
        when(employeeProfiles.findByUserId(72L)).thenReturn(Optional.of(
                new EmployeeProfile(bootstrapStaffWithCustomerRole, "OPERATIONS", null, null)));
        SystemNotification staffNotice = notificationService.createAdminNotification(TargetRole.ALL, "72",
                "Direct staff notice", "Must remain scoped", null, "SYSTEM", "info");
        assertEquals(NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, staffNotice.getAuthorizationClass());

        User administrator = user(73L, Role.ROLE_ADMIN);
        when(users.findById(73L)).thenReturn(Optional.of(administrator));
        SystemNotification adminNotice = notificationService.createAdminNotification(TargetRole.ALL, "73",
                "Admin notice", "Must not become recipient scoped", null, "SYSTEM", "info");
        assertEquals(NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, adminNotice.getAuthorizationClass());
    }

    @Test
    public void adminExplicitCustomerTargetingKeepsDualCapabilityTenantAndLessorNoticesRecipientScoped() {
        User tenantWithStaffProfile = user(81L, Role.ROLE_TENANT);
        when(users.findById(81L)).thenReturn(Optional.of(tenantWithStaffProfile));
        when(employeeProfiles.findByUserId(81L)).thenReturn(Optional.of(
                new EmployeeProfile(tenantWithStaffProfile, "OPERATIONS", null, null)));
        when(repository.save(any(SystemNotification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SystemNotification tenantNotice = notificationService.createAdminNotification(TargetRole.TENANT, "81",
                "Tenant notice", "Customer message", null, "SYSTEM", "info");

        assertEquals(Role.ROLE_TENANT, tenantWithStaffProfile.getRole(), "staff activation does not replace customer identity");
        assertEquals(NotificationAuthorizationClass.RECIPIENT, tenantNotice.getAuthorizationClass());
        assertNull(tenantNotice.getOperationalSessionId());

        User tenantLessorWithStaffProfile = user(82L, Role.ROLE_TENANT);
        when(users.findById(82L)).thenReturn(Optional.of(tenantLessorWithStaffProfile));
        when(employeeProfiles.findByUserId(82L)).thenReturn(Optional.of(
                new EmployeeProfile(tenantLessorWithStaffProfile, "OPERATIONS", null, null)));
        when(lessorProfiles.existsByLinkedUserId(82L)).thenReturn(true);

        SystemNotification lessorNotice = notificationService.createAdminNotification(TargetRole.LANDLORD, "82",
                "Lessor notice", "Lessor customer message", null, "SYSTEM", "info");

        assertEquals(Role.ROLE_TENANT, tenantLessorWithStaffProfile.getRole());
        assertEquals(NotificationAuthorizationClass.RECIPIENT, lessorNotice.getAuthorizationClass());
        assertNull(lessorNotice.getOperationalSessionId());

        User tenantWithoutLessorProfile = user(83L, Role.ROLE_TENANT);
        when(users.findById(83L)).thenReturn(Optional.of(tenantWithoutLessorProfile));
        when(employeeProfiles.findByUserId(83L)).thenReturn(Optional.of(
                new EmployeeProfile(tenantWithoutLessorProfile, "OPERATIONS", null, null)));
        when(lessorProfiles.existsByLinkedUserId(83L)).thenReturn(false);
        SystemNotification unprovenLessorNotice = notificationService.createAdminNotification(TargetRole.LANDLORD, "83",
                "Unproven lessor notice", "Must remain quarantined", null, "SYSTEM", "info");
        assertEquals(NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED,
                unprovenLessorNotice.getAuthorizationClass());
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    @Test
    public void crossUserMarkAsReadCannotLoadOrMutateNotification() {
        when(repository.markVisibleReadForUser(eq(10L), eq(42L), any())).thenReturn(0);

        assertFalse(notificationService.markAsReadForUser(10L, "42"));

        verify(repository).markVisibleReadForUser(eq(10L), eq(42L), any());
        verify(repository, never()).save(any());
    }

    @Test
    public void privateFeedAndUnreadCountUseRecipientScopedRepositoryQueries() {
        SystemNotification ownNotification = new SystemNotification(TargetRole.TENANT, "tenant-a",
                "Visit updated", "Your visit time changed", null, "VISIT_SESSION", "info");
        when(repository.findCurrentlyVisibleForUser(42L))
                .thenReturn(List.of(ownNotification));
        when(repository.countCurrentlyVisibleUnreadForUser(42L)).thenReturn(1L);

        assertEquals(List.of(ownNotification), notificationService.getNotificationsForUser("42"));
        assertEquals(1L, notificationService.getUnreadCountForUser("42"));

        verify(repository).findCurrentlyVisibleForUser(42L);
        verify(repository).countCurrentlyVisibleUnreadForUser(42L);
    }

    @Test
    public void testCreateNotificationWithEventKey_Idempotency() {
        String eventKey = "PROPERTY_PUBLISHED:draft-123";
        SystemNotification existingNotif = new SystemNotification(TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-123", "PROPERTY", "success");
        existingNotif.setId(20L);
        existingNotif.setEventKey(eventKey);

        when(repository.findByEventKey(eventKey)).thenReturn(Optional.of(existingNotif));

        Optional<SystemNotification> result = notificationService.createNotificationWithEventKey(
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-123", "PROPERTY", "success", NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, null, eventKey
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
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-race", "PROPERTY", "success", NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, null, eventKey
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
                TargetRole.ADMIN, null, "Property published successfully", "1 property was published successfully.", "Draft ID: draft-tx-requires-new", "PROPERTY", "success", NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, null, eventKey
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
