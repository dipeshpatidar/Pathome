package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final SystemNotificationRepository repository;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public NotificationService(SystemNotificationRepository repository) {
        this.repository = repository;
    }

    public SystemNotification createNotification(TargetRole targetRole, String recipientUserId, String title, String message, String details, String category, String type) {
        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message, details, category, type);
        SystemNotification saved = repository.save(notification);
        log.info("Dispatched role-scoped notification id={} targetRole={}", saved.getId(), targetRole);
        return saved;
    }

    public Optional<SystemNotification> createNotificationWithEventKey(
            TargetRole targetRole, String recipientUserId, String title, String message,
            String details, String category, String type, String eventKey) {
        if (eventKey != null && !eventKey.isBlank()) {
            Optional<SystemNotification> existing = repository.findByEventKey(eventKey);
            if (existing.isPresent()) {
                log.info("Notification with eventKey [{}] already exists (id={}). Idempotent skip.",
                        eventKey, existing.get().getId());
                return existing;
            }
        }

        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message, details, category, type);
        notification.setEventKey(eventKey);

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

    public List<SystemNotification> getNotificationsForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return Collections.emptyList();
        }
        return repository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId);
    }

    public long getUnreadCountForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return 0L;
        }
        return repository.countByRecipientUserIdAndIsReadFalse(recipientUserId);
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean markAsReadForUser(Long id, String recipientUserId) {
        if (id == null || recipientUserId == null || recipientUserId.isBlank()) {
            return false;
        }
        Optional<SystemNotification> notifOpt = repository.findByIdAndRecipientUserId(id, recipientUserId);
        if (notifOpt.isEmpty()) return false;
        SystemNotification notif = notifOpt.get();
        notif.setRead(true);
        notif.setReadAt(java.time.LocalDateTime.now());
        repository.save(notif);
        return true;
    }

    @org.springframework.transaction.annotation.Transactional
    public int markAllAsReadForUser(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return 0;
        }
        return repository.markAllReadForUser(recipientUserId, java.time.LocalDateTime.now());
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
