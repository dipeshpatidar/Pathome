package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {
    Optional<EmployeeProfile> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EmployeeProfile p where p.user.id = :userId")
    Optional<EmployeeProfile> findLockedByUserId(@Param("userId") Long userId);

    Optional<EmployeeProfile> findByAssignedSectorAndRoleType(String assignedSector, String roleType);
}
