package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SystemNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {

    @Query(value = "SELECT n.* FROM system_notifications n "
            + "WHERE n.recipient_user_id = CAST(:userId AS VARCHAR) AND "
            + OperationalVisibilitySql.NOTIFICATION_ROW_VISIBLE
            + " ORDER BY n.created_at DESC, n.id DESC", nativeQuery = true)
    List<SystemNotification> findCurrentlyVisibleForUser(@Param("userId") Long userId);

    @Query(value = "SELECT count(*) FROM system_notifications n "
            + "WHERE n.recipient_user_id = CAST(:userId AS VARCHAR) AND n.is_read = FALSE AND "
            + OperationalVisibilitySql.NOTIFICATION_ROW_VISIBLE, nativeQuery = true)
    long countCurrentlyVisibleUnreadForUser(@Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE system_notifications n SET is_read = TRUE, read_at = :readAt "
            + "WHERE n.id = :id AND n.recipient_user_id = CAST(:userId AS VARCHAR) AND "
            + OperationalVisibilitySql.NOTIFICATION_ROW_VISIBLE, nativeQuery = true)
    int markVisibleReadForUser(@Param("id") Long id, @Param("userId") Long userId,
                               @Param("readAt") java.time.LocalDateTime readAt);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE system_notifications n SET is_read = TRUE, read_at = :readAt "
            + "WHERE n.recipient_user_id = CAST(:userId AS VARCHAR) AND n.is_read = FALSE AND "
            + OperationalVisibilitySql.NOTIFICATION_ROW_VISIBLE, nativeQuery = true)
    int markAllCurrentlyVisibleReadForUser(@Param("userId") Long userId,
                                           @Param("readAt") java.time.LocalDateTime readAt);

    java.util.Optional<SystemNotification> findByEventKey(String eventKey);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            INSERT INTO system_notifications
                (event_key, target_role, recipient_user_id, authorization_class, title, message, category, type,
                 is_read, created_at, listing_id, revision_id, action_type, action_target)
            VALUES
                (:eventKey, 'LANDLORD', :recipientUserId, 'RECIPIENT', :title, :message, 'PROPERTY', :type,
                 false, :createdAt, :listingId, :revisionId, :actionType, :actionTarget)
            ON CONFLICT (event_key) DO NOTHING
            """, nativeQuery = true)
    int insertWorkflowIfAbsent(@Param("eventKey") String eventKey,
                               @Param("recipientUserId") String recipientUserId,
                               @Param("title") String title,
                               @Param("message") String message,
                               @Param("type") String type,
                               @Param("createdAt") java.time.LocalDateTime createdAt,
                               @Param("listingId") Long listingId,
                               @Param("revisionId") String revisionId,
                               @Param("actionType") String actionType,
                               @Param("actionTarget") String actionTarget);
}
