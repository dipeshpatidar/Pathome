package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.GroundExecutiveSchedulingProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface GroundExecutiveSchedulingProfileRepository
        extends JpaRepository<GroundExecutiveSchedulingProfile, Long> {
    @Query("select p from GroundExecutiveSchedulingProfile p where p.employeeProfile.user.id = :userId")
    Optional<GroundExecutiveSchedulingProfile> findByGroundExecutiveUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GroundExecutiveSchedulingProfile p where p.employeeProfile.user.id = :userId")
    Optional<GroundExecutiveSchedulingProfile> findLockedByGroundExecutiveUserId(@Param("userId") Long userId);

    @Query("select distinct p from GroundExecutiveSchedulingProfile p "
            + "join fetch p.employeeProfile ep join fetch ep.user u "
            + "where p.schedulingActive = true and ep.roleType = 'GROUND_BOY' "
            + "and u.role = com.indore.pathome.spaces.entity.Role.ROLE_GROUND_BOY "
            + "and exists (select c.id from GroundExecutiveCoverage c "
            + "where c.schedulingProfile = p and lower(c.city) = lower(:city)) order by u.id")
    List<GroundExecutiveSchedulingProfile> findActiveCoveringCity(@Param("city") String city, Pageable pageable);

    @Query("select distinct p from GroundExecutiveSchedulingProfile p "
            + "join fetch p.employeeProfile ep join fetch ep.user u "
            + "where p.schedulingActive = true and ep.staffActive = true and ep.roleType = 'GROUND_BOY' "
            + "and u.role = com.indore.pathome.spaces.entity.Role.ROLE_GROUND_BOY "
            + "and exists (select c.id from GroundExecutiveCoverage c "
            + "where c.schedulingProfile = p and lower(c.city) = lower(:city)) order by u.id")
    List<GroundExecutiveSchedulingProfile> findActiveStaffCoveringCity(@Param("city") String city, Pageable pageable);

    @Query("select p from GroundExecutiveSchedulingProfile p "
            + "join fetch p.employeeProfile ep join fetch ep.user u "
            + "where u.id > :afterId and p.schedulingActive = true and ep.staffActive = true "
            + "and ep.roleType = 'GROUND_BOY' "
            + "and u.role = com.indore.pathome.spaces.entity.Role.ROLE_GROUND_BOY "
            + "and exists (select c.id from GroundExecutiveCoverage c "
            + "where c.schedulingProfile = p and lower(c.city) = lower(:city)) order by u.id")
    List<GroundExecutiveSchedulingProfile> scanActiveStaffCoveringCity(
            @Param("city") String city, @Param("afterId") Long afterId, Pageable pageable);

    @Query("select p from GroundExecutiveSchedulingProfile p "
            + "join fetch p.employeeProfile ep join fetch ep.user u "
            + "where u.id > :afterId and p.schedulingActive = true and ep.staffActive = true "
            + "and ep.roleType = 'GROUND_BOY' "
            + "and u.role = com.indore.pathome.spaces.entity.Role.ROLE_GROUND_BOY "
            + "and (lower(u.fullName) like :namePattern escape '!' or u.id = :exactUserId) "
            + "and exists (select c.id from GroundExecutiveCoverage c "
            + "where c.schedulingProfile = p and lower(c.city) = lower(:city)) order by u.id")
    List<GroundExecutiveSchedulingProfile> scanActiveStaffCoveringCityMatching(
            @Param("city") String city, @Param("afterId") Long afterId,
            @Param("namePattern") String namePattern, @Param("exactUserId") Long exactUserId,
            Pageable pageable);

    @Query("select case when count(p) > 0 then true else false end "
            + "from GroundExecutiveSchedulingProfile p join p.employeeProfile ep join ep.user u "
            + "where u.id = :userId and p.schedulingActive = true and ep.staffActive = true "
            + "and ep.roleType = 'GROUND_BOY' "
            + "and u.role = com.indore.pathome.spaces.entity.Role.ROLE_GROUND_BOY "
            + "and exists (select c.id from GroundExecutiveCoverage c "
            + "where c.schedulingProfile = p and lower(c.city) = lower(:city))")
    boolean isPotentialActiveStaffInCity(@Param("userId") Long userId, @Param("city") String city);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GroundExecutiveSchedulingProfile p where p.employeeProfile.user.id = :userId")
    Optional<GroundExecutiveSchedulingProfile> findLockedByUserId(@Param("userId") Long userId);
}
