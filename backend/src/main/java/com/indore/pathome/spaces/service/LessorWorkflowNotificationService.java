package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class LessorWorkflowNotificationService {
    private static final Logger log = LoggerFactory.getLogger(LessorWorkflowNotificationService.class);

    private final SystemNotificationRepository notificationRepository;
    private final LessorProfileRepository lessorProfileRepository;

    public LessorWorkflowNotificationService(
            SystemNotificationRepository notificationRepository,
            LessorProfileRepository lessorProfileRepository) {
        this.notificationRepository = notificationRepository;
        this.lessorProfileRepository = lessorProfileRepository;
    }

    /**
     * Resolves the canonical notification recipient user ID strictly via:
     * Property / Listing -> LessorProfile -> linked User.
     * Never identifies recipient using email matching, phone matching,
     * or frontend-supplied user ID.
     * Returns null if LessorProfile has no linked User.
     */
    public Long resolveRecipientUserId(Listing listing) {
        if (listing == null) {
            return null;
        }
        Long profileId = listing.getLessorProfileId();
        return profileId == null ? null : lessorProfileRepository.findById(profileId)
                .map(LessorProfile::getLinkedUserId).orElse(null);
    }

    private String formatPropertyName(Listing listing) {
        if (listing == null || listing.getTitle() == null || listing.getTitle().isBlank()) {
            return "Your property";
        }
        return listing.getTitle().trim();
    }

    @Transactional
    public Optional<SystemNotification> createWorkflowNotification(
            Listing listing,
            String eventType,
            String eventKeySuffix,
            String title,
            String message,
            String actionType,
            String actionTarget,
            String revisionId) {

        Long recipientUserId = resolveRecipientUserId(listing);
        if (recipientUserId == null) {
            log.info("LessorProfile has no linked user for listing id={}. Skipping in-app notification (eventType={}).",
                    listing != null ? listing.getId() : null, eventType);
            return Optional.empty();
        }

        String eventKey = eventType + ":" + (listing != null ? listing.getId() : "0") + ":" + eventKeySuffix;
        if (eventKey.length() > 120) {
            eventKey = eventKey.substring(0, 120);
        }

        int inserted = notificationRepository.insertWorkflowIfAbsent(eventKey, String.valueOf(recipientUserId),
                title, message, eventType.contains("CHANGES_REQUIRED") ? "warning" : "info",
                LocalDateTime.now(), listing != null ? listing.getId() : null, revisionId, actionType, actionTarget);
        log.info("Lessor workflow notification eventKey={} inserted={} recipientUserId={}",
                eventKey, inserted == 1, recipientUserId);
        return notificationRepository.findByEventKey(eventKey);
    }

    // 1. PROPERTY_SUBMITTED
    @Transactional
    public Optional<SystemNotification> notifyPropertySubmitted(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "PROPERTY_SUBMITTED",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Property submitted",
                prop + " was submitted for review.",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }

    // 2. REVIEW_STARTED
    @Transactional
    public Optional<SystemNotification> notifyReviewStarted(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "REVIEW_STARTED",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Review started",
                "Our team has started reviewing " + prop + ".",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }

    // 3. CHANGES_REQUIRED
    @Transactional
    public Optional<SystemNotification> notifyChangesRequired(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "CHANGES_REQUIRED",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Updates needed",
                "Updates are needed before " + prop + " can be published.",
                "REVIEW_CHANGES",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }

    // 4. PROPERTY_PUBLISHED
    @Transactional
    public Optional<SystemNotification> notifyPropertyPublished(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "PROPERTY_PUBLISHED",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Your property is live",
                prop + " has been approved and is now visible to renters.",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }

    // 5. REVISION_SUBMITTED
    @Transactional
    public Optional<SystemNotification> notifyRevisionSubmitted(Listing listing, String draftId, int submissionVersion) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        boolean isLive = listing.getWorkflowStatus() == ListingWorkflowStatus.PUBLISHED;
        String message = isLive
                ? "Your recent changes to " + prop + " were submitted for review. Your live listing remains visible."
                : "Your recent changes to " + prop + " were submitted for review.";
        return createWorkflowNotification(
                listing,
                "REVISION_SUBMITTED",
                draftId + ":" + submissionVersion,
                "Changes submitted",
                message,
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                draftId
        );
    }

    // 6. REVISION_UNDER_REVIEW
    @Transactional
    public Optional<SystemNotification> notifyRevisionUnderReview(Listing listing, String draftId) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        boolean isLive = listing.getWorkflowStatus() == ListingWorkflowStatus.PUBLISHED;
        String message = isLive
                ? "We're reviewing your recent changes to " + prop + ". Your live listing remains visible."
                : "We're reviewing your recent changes to " + prop + ".";
        return createWorkflowNotification(
                listing,
                "REVISION_UNDER_REVIEW",
                draftId != null ? draftId : "0",
                "Changes under review",
                message,
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                draftId
        );
    }

    // 7. REVISION_CHANGES_REQUIRED
    @Transactional
    public Optional<SystemNotification> notifyRevisionChangesRequired(Listing listing, PropertyUploadDraft draft) {
        if (listing == null || draft == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        String versionSuffix = draft.getDraftId() + ":" + (draft.getVersion() != null ? draft.getVersion() : 0);
        return createWorkflowNotification(
                listing,
                "REVISION_CHANGES_REQUIRED",
                versionSuffix,
                "Changes need attention",
                "Updates are needed before your recent changes to " + prop + " can be published.",
                "REVIEW_CHANGES",
                "/lessor/listings/" + listing.getId(),
                draft.getDraftId()
        );
    }

    // 8. REVISION_PUBLISHED
    @Transactional
    public Optional<SystemNotification> notifyRevisionPublished(Listing listing, String draftId) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        boolean live = listing.getWorkflowStatus() == ListingWorkflowStatus.PUBLISHED;
        return createWorkflowNotification(
                listing,
                "REVISION_PUBLISHED",
                draftId != null ? draftId : "0",
                live ? "Changes published" : "Changes approved",
                live ? "Your approved changes to " + prop + " are now live."
                        : listing.getWorkflowStatus() == ListingWorkflowStatus.PAUSED
                            ? "Your recent changes to " + prop + " were approved. This property remains paused."
                            : "Your recent changes to " + prop + " were approved.",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                draftId
        );
    }

    // 9. PROPERTY_PAUSED_BY_OPERATIONS
    @Transactional
    public Optional<SystemNotification> notifyPropertyPausedByOperations(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "PROPERTY_PAUSED_BY_OPERATIONS",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Property paused",
                prop + " is currently not visible to renters.",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }

    // 10. PROPERTY_ARCHIVED_BY_OPERATIONS
    @Transactional
    public Optional<SystemNotification> notifyPropertyArchivedByOperations(Listing listing) {
        if (listing == null) return Optional.empty();
        String prop = formatPropertyName(listing);
        return createWorkflowNotification(
                listing,
                "PROPERTY_ARCHIVED_BY_OPERATIONS",
                String.valueOf(listing.getVersion() != null ? listing.getVersion() : 0),
                "Property archived",
                prop + " has been archived.",
                "VIEW_PROPERTY",
                "/lessor/listings/" + listing.getId(),
                null
        );
    }
}
