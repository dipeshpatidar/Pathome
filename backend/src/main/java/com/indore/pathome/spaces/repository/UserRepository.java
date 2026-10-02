package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET landlord_activated_at = :activatedAt, landlord_activated_by_user_id = :userId " +
            "WHERE id = :userId AND landlord_activated_at IS NULL " +
            "AND role IN ('ROLE_TENANT', 'ROLE_LANDLORD')", nativeQuery = true)
    int activateLandlordCapability(@Param("userId") Long userId, @Param("activatedAt") LocalDateTime activatedAt);
}
