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

    java.util.Optional<SystemNotification> findByEventKey(String eventKey);

    boolean existsByEventKey(String eventKey);
}
