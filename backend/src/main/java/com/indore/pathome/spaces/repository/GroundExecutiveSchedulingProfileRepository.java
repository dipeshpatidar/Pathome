package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.GroundExecutiveSchedulingProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GroundExecutiveSchedulingProfileRepository
        extends JpaRepository<GroundExecutiveSchedulingProfile, Long> {
    @Query("select p from GroundExecutiveSchedulingProfile p where p.employeeProfile.user.id = :userId")
    Optional<GroundExecutiveSchedulingProfile> findByGroundExecutiveUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GroundExecutiveSchedulingProfile p where p.employeeProfile.user.id = :userId")
    Optional<GroundExecutiveSchedulingProfile> findLockedByGroundExecutiveUserId(@Param("userId") Long userId);
}
