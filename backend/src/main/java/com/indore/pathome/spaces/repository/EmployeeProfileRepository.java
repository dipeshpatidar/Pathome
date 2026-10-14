package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

@Repository
public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {
    Optional<EmployeeProfile> findByUserId(Long userId);

    boolean existsByUserIdAndStaffActiveTrue(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EmployeeProfile p where p.user.id = :userId")
    Optional<EmployeeProfile> findLockedByUserId(@Param("userId") Long userId);

    /**
     * Holds the actor's staff profile row for the rest of the transaction without blocking another reader.
     *
     * <p>{@code FOR SHARE} is deliberately weaker than {@link #findLockedByUserId}: it conflicts with the
     * deactivation path's {@code FOR UPDATE} on this row, so a staff deactivation cannot commit between a
     * decision taken under this hold and that decision's own commit, while concurrent reviews by the same
     * actor - which only ever take share locks - do not serialise against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from EmployeeProfile p where p.user.id = :userId")
    Optional<EmployeeProfile> findSharedByUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EmployeeProfile p where p.user.id in :userIds order by p.user.id")
    List<EmployeeProfile> findLockedByUserIdsOrderByUserId(@Param("userIds") Collection<Long> userIds);

    Optional<EmployeeProfile> findByAssignedSectorAndRoleType(String assignedSector, String roleType);
}
