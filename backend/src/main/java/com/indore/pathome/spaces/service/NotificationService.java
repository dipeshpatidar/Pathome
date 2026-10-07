package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.NotificationAuthorizationClass;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final SystemNotificationRepository repository;
    private final OperationalNotificationAuthorizationService operationalAuthorization;
    private final UserRepository users;
    private final EmployeeProfileRepository employeeProfiles;
    private final LessorProfileRepository lessorProfiles;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Autowired
    public NotificationService(SystemNotificationRepository repository,
                               OperationalNotificationAuthorizationService operationalAuthorization,
                               UserRepository users,
                               EmployeeProfileRepository employeeProfiles,
                               LessorProfileRepository lessorProfiles) {
        this.repository = repository;
        this.operationalAuthorization = operationalAuthorization;
        this.users = users;
        this.employeeProfiles = employeeProfiles;
        this.lessorProfiles = lessorProfiles;
    }

    public NotificationService(SystemNotificationRepository repository) {
        this.repository = repository;
        this.operationalAuthorization = null;
        this.users = null;
        this.employeeProfiles = null;
        this.lessorProfiles = null;
    }

    /** Classifies Admin-created direct notices using persisted recipient identity, never request role alone. */
    public SystemNotification createAdminNotification(TargetRole targetRole, String recipientUserId, String title,
            String message, String details, String category, String type) {
        NotificationAuthorizationClass authorizationClass = trustedCustomerRecipient(targetRole, recipientUserId)
                ? NotificationAuthorizationClass.RECIPIENT
                : NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED;
        return createNotification(targetRole, recipientUserId, title, message, details, category, type,
                authorizationClass);
    }

    private boolean trustedCustomerRecipient(TargetRole targetRole, String recipientUserId) {
        Long recipientId = parseUserId(recipientUserId);
        if (recipientId == null || users == null) return false;
        Role persistedRole = users.findById(recipientId).map(user -> user.getRole()).orElse(null);
        if (persistedRole != Role.ROLE_TENANT && persistedRole != Role.ROLE_LANDLORD) return false;

        if (targetRole == TargetRole.TENANT) return true;
        if (targetRole == TargetRole.LANDLORD) {
            return persistedRole == Role.ROLE_LANDLORD
                    || (lessorProfiles != null && lessorProfiles.existsByLinkedUserId(recipientId));
        }
        return targetRole == TargetRole.ALL && employeeProfiles != null
                && employeeProfiles.findByUserId(recipientId).isEmpty();
    }

    public SystemNotification createNotification(TargetRole targetRole, String recipientUserId, String title,
            String message, String details, String category, String type,
            NotificationAuthorizationClass authorizationClass) {
        validateAuthorizationContext(authorizationClass, null);
        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message, details, category, type);
        notification.setAuthorizationClass(authorizationClass);
        SystemNotification saved = repository.save(notification);
        log.info("Dispatched role-scoped notification id={} targetRole={}", saved.getId(), targetRole);
        return saved;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<SystemNotification> createNotificationWithEventKey(
            TargetRole targetRole, String recipientUserId, String title, String message,
            String details, String category, String type, NotificationAuthorizationClass authorizationClass,
            Long operationalSessionId, String eventKey) {
        validateAuthorizationContext(authorizationClass, operationalSessionId);
        if (eventKey != null && !eventKey.isBlank()) {
            Optional<SystemNotification> existing = repository.findByEventKey(eventKey);
            if (existing.isPresent()) {
                log.info("Notification with eventKey [{}] already exists (id={}). Idempotent skip.",
                        eventKey, existing.get().getId());
                return existing;
            }
        }

        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message, details, category, type);
        notification.setAuthorizationClass(authorizationClass);
        notification.setOperationalSessionId(operationalSessionId);
        notification.setEventKey(eventKey);

        if (authorizationClass == NotificationAuthorizationClass.OPERATIONS_SESSION) {
            Long recipientId = parseUserId(recipientUserId);
            if (operationalAuthorization == null || recipientId == null
                    || !operationalAuthorization.lockAndCanDeliver(recipientId, operationalSessionId, targetRole.name())) {
                return Optional.empty();
            }
            return Optional.of(repository.saveAndFlush(notification));
        }

        TransactionTemplate requiresNew = null;
        if (transactionManager != null) {
            requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        try {
            SystemNotification saved;
            if (requiresNew != null) {
                saved = requiresNew.execute(status -> repository.saveAndFlush(notification));
            } else {
                saved = repository.saveAndFlush(notification);
            }
            if (saved != null) {
                log.info("Dispatched role-scoped notification id={} targetRole={} eventKey={}", saved.getId(), targetRole, eventKey);
                return Optional.of(saved);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("Concurrent notification race on eventKey [{}]. Idempotent replay.", eventKey);
            if (eventKey != null && !eventKey.isBlank()) {
                return repository.findByEventKey(eventKey);
            }
        }
        return Optional.empty();
    }

    /** Persists the outbox notification in the caller's transaction after current authorization is locked and checked. */
    public Optional<SystemNotification> createNotificationWithEventKeyInCurrentTransaction(
            TargetRole targetRole, String recipientUserId, String title, String message,
            String details, String category, String type, NotificationAuthorizationClass authorizationClass,
            Long operationalSessionId, String eventKey) {
        validateAuthorizationContext(authorizationClass, operationalSessionId);
        Optional<SystemNotification> existing = eventKey == null || eventKey.isBlank()
                ? Optional.empty() : repository.findByEventKey(eventKey);
        if (existing.isPresent()) return existing;
        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message,
                details, category, type);
        notification.setAuthorizationClass(authorizationClass);
        notification.setOperationalSessionId(operationalSessionId);
        notification.setEventKey(eventKey);
        return Optional.of(repository.saveAndFlush(notification));
    }

    public List<SystemNotification> getNotificationsForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return Collections.emptyList();
        }
        Long userId = parseUserId(recipientUserId);
        return userId == null ? Collections.emptyList() : repository.findCurrentlyVisibleForUser(userId);
    }

    public long getUnreadCountForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return 0L;
        }
        Long userId = parseUserId(recipientUserId);
        return userId == null ? 0L : repository.countCurrentlyVisibleUnreadForUser(userId);
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean markAsReadForUser(Long id, String recipientUserId) {
        if (id == null || recipientUserId == null || recipientUserId.isBlank()) {
            return false;
        }
        Long userId = parseUserId(recipientUserId);
        return userId != null && repository.markVisibleReadForUser(id, userId, java.time.LocalDateTime.now()) > 0;
    }

    @org.springframework.transaction.annotation.Transactional
    public int markAllAsReadForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return 0;
        }
        Long userId = parseUserId(recipientUserId);
        return userId == null ? 0 : repository.markAllCurrentlyVisibleReadForUser(
                userId, java.time.LocalDateTime.now());
    }

    private static Long parseUserId(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) return null;
        try {
            long userId = Long.parseLong(recipientUserId);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void validateAuthorizationContext(NotificationAuthorizationClass authorizationClass,
                                                     Long operationalSessionId) {
        if (authorizationClass == null) {
            throw new IllegalArgumentException("Notification authorization class is required");
        }
        if ((authorizationClass == NotificationAuthorizationClass.OPERATIONS_SESSION)
                != (operationalSessionId != null)) {
            throw new IllegalArgumentException("Operational Session reference must match notification authorization class");
        }
    }

    public void sanitizeLegacyNotifications() {
        try {
            List<SystemNotification> all = repository.findAll();
            for (SystemNotification n : all) {
                boolean itemChanged = false;
                if (n.getTitle() != null && n.getTitle().contains("Divyavastu")) {
                    n.setTitle(n.getTitle().replace("Divyavastu Spaces", "Pathome").replace("Divyavastu", "Pathome"));
                    itemChanged = true;
                }
                if (n.getMessage() != null && (n.getMessage().contains("Divyavastu") || n.getMessage().contains("100% direct listings"))) {
                    String msg = n.getMessage()
                            .replace("Divyavastu Spaces", "Pathome")
                            .replace("Divyavastu", "Pathome")
                            .replace("Browse verified 100% direct listings in Vijay Nagar, Palasia, and Nanda Nagar with zero brokerage hassle.",
                                    "Explore rental listings in Vijay Nagar, Palasia, and Nanda Nagar and request property visits online.");
                    n.setMessage(msg);
                    itemChanged = true;
                }
                if (n.getDetails() != null && (n.getDetails().contains("VIP Pass") || n.getDetails().contains("5 Free Visits"))) {
                    n.setDetails("Browse Rentals");
                    itemChanged = true;
                }
                if (itemChanged) {
                    repository.save(n);
                }
            }
        } catch (Exception e) {
            log.warn("Deferred legacy notification sanitization: {}", e.getMessage());
        }
    }
}
