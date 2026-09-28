package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SystemNotificationRepository extends JpaRepository<SystemNotification, Long> {

    @Query("SELECT n FROM SystemNotification n WHERE n.targetRole IN :roles OR n.recipientUserId = :recipientUserId ORDER BY n.createdAt DESC")
    List<SystemNotification> findForUserRoleAndRecipient(
            @Param("roles") Collection<TargetRole> roles,
            @Param("recipientUserId") String recipientUserId
    );

    List<SystemNotification> findByTargetRoleInOrderByCreatedAtDesc(Collection<TargetRole> targetRoles);

    long countByTargetRoleInAndIsReadFalse(Collection<TargetRole> targetRoles);

    List<SystemNotification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId);

    long countByRecipientUserIdAndIsReadFalse(String recipientUserId);

    java.util.Optional<SystemNotification> findByIdAndRecipientUserId(Long id, String recipientUserId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE SystemNotification n SET n.isRead = true, n.readAt = :readAt WHERE n.recipientUserId = :recipientUserId AND n.isRead = false")
    int markAllReadForUser(@Param("recipientUserId") String recipientUserId, @Param("readAt") java.time.LocalDateTime readAt);

    java.util.Optional<SystemNotification> findByEventKey(String eventKey);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            INSERT INTO system_notifications
                (event_key, target_role, recipient_user_id, title, message, category, type,
                 is_read, created_at, listing_id, revision_id, action_type, action_target)
            VALUES
                (:eventKey, 'LANDLORD', :recipientUserId, :title, :message, 'PROPERTY', :type,
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
