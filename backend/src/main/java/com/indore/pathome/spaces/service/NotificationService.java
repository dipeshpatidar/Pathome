package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final SystemNotificationRepository repository;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    // L1 Concurrent In-Memory Cache for sub-millisecond HTTP handlers
    private final Map<TargetRole, List<SystemNotification>> roleL1Cache = new ConcurrentHashMap<>();

    public NotificationService(SystemNotificationRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        try {
            if (repository.count() == 0) {
                seedDefaultNotifications();
            }
            refreshCache();
        } catch (Exception e) {
            log.warn("Deferred Notification L1 cache init: {}", e.getMessage());
        }
    }

    public synchronized void refreshCache() {
        try {
            for (TargetRole role : TargetRole.values()) {
                List<TargetRole> allowedRoles = List.of(role, TargetRole.ALL);
                List<SystemNotification> list = repository.findByTargetRoleInOrderByCreatedAtDesc(allowedRoles);
                roleL1Cache.put(role, list);
            }
            log.info("Notification L1 Cache initialized for {} roles", roleL1Cache.size());
        } catch (Exception e) {
            log.warn("Failed to refresh notification cache: {}", e.getMessage());
        }
    }

    public SystemNotification createNotification(TargetRole targetRole, String recipientUserId, String title, String message, String details, String category, String type) {
        SystemNotification notification = new SystemNotification(targetRole, recipientUserId, title, message, details, category, type);
        SystemNotification saved = repository.save(notification);
        refreshCache();
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
            refreshCache();
            if (saved != null) {
                log.info("Dispatched role-scoped notification id={} targetRole={} eventKey={}", saved.getId(), targetRole, eventKey);
                return Optional.of(saved);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("Concurrent notification race on eventKey [{}]. Idempotent replay.", eventKey);
            refreshCache();
            if (eventKey != null && !eventKey.isBlank()) {
                return repository.findByEventKey(eventKey);
            }
        }
        return Optional.empty();
    }

    public List<SystemNotification> getNotificationsForRole(TargetRole role, String recipientUserId) {
        if (role == null) role = TargetRole.ALL;
        
        List<TargetRole> targetRoles = List.of(role, TargetRole.ALL);
        if (recipientUserId != null && !recipientUserId.isBlank()) {
            return repository.findForUserRoleAndRecipient(targetRoles, recipientUserId);
        }

        List<SystemNotification> cached = roleL1Cache.get(role);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        return repository.findByTargetRoleInOrderByCreatedAtDesc(targetRoles);
    }

    public boolean markAsRead(Long id) {
        Optional<SystemNotification> notifOpt = repository.findById(id);
        if (notifOpt.isPresent()) {
            SystemNotification notif = notifOpt.get();
            notif.setRead(true);
            repository.save(notif);
            refreshCache();
            return true;
        }
        return false;
    }

    private void seedDefaultNotifications() {
        log.info("Seeding initial role-scoped system notifications...");
        repository.save(new SystemNotification(
                TargetRole.ADMIN,
                null,
                "Master Admin Telemetry Active",
                "Pathome core engine is live with 100% database indexing & Cloudinary streaming.",
                "System status: 100% Operational",
                "SYSTEM",
                "success"
        ));

        repository.save(new SystemNotification(
                TargetRole.ADMIN,
                null,
                "Payroll & Brokerage Ledger Synchronized",
                "Brokerage split calculations and employee commission queues are up to date.",
                "Sub-Admin access restricted.",
                "PAYROLL",
                "info"
        ));

        repository.save(new SystemNotification(
                TargetRole.EMPLOYEE,
                null,
                "New Lead Assigned",
                "A new tenant inquiry for Vijay Nagar 3 BHK Penthouse has been assigned to your queue.",
                "Target Locality: Vijay Nagar",
                "LEAD",
                "info"
        ));

        repository.save(new SystemNotification(
                TargetRole.TENANT,
                null,
                "Welcome to Pathome!",
                "Browse verified 100% direct listings in Vijay Nagar, Palasia, and Nanda Nagar with zero brokerage hassle.",
                "VIP Pass Status: 5 Free Visits Active",
                "PROPERTY",
                "success"
        ));
    }
}
