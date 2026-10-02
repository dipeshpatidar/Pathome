package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.GroundExecutiveUnavailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface GroundExecutiveUnavailabilityRepository extends JpaRepository<GroundExecutiveUnavailability, Long> {
    @Query("select u from GroundExecutiveUnavailability u where u.schedulingProfile.employeeProfileId = :profileId "
            + "and u.startsAt < :windowEnd and u.endsAt > :windowStart order by u.startsAt, u.id")
    List<GroundExecutiveUnavailability> findOverlappingWindow(@Param("profileId") Long profileId,
                                                               @Param("windowStart") Instant windowStart,
                                                               @Param("windowEnd") Instant windowEnd);

    @Query("select u from GroundExecutiveUnavailability u where u.schedulingProfile.employeeProfileId in :profileIds "
            + "and u.startsAt < :windowEnd and u.endsAt > :windowStart order by u.schedulingProfile.employeeProfileId, u.startsAt, u.id")
    List<GroundExecutiveUnavailability> findOverlappingForProfiles(@Param("profileIds") Collection<Long> profileIds,
                                                                   @Param("windowStart") Instant windowStart,
                                                                   @Param("windowEnd") Instant windowEnd);

    @Query("select (count(u) > 0) from GroundExecutiveUnavailability u "
            + "where u.schedulingProfile.employeeProfileId = :profileId "
            + "and u.startsAt < :endsAt and u.endsAt > :startsAt")
    boolean existsOverlapping(@Param("profileId") Long profileId,
                              @Param("startsAt") Instant startsAt,
                              @Param("endsAt") Instant endsAt);

    @Query("select (count(u) > 0) from GroundExecutiveUnavailability u "
            + "where u.schedulingProfile.employeeProfileId = :profileId "
            + "and u.intervalType = com.indore.pathome.spaces.entity.GroundExecutiveUnavailableType.BREAK "
            + "and u.startsAt < :endsAt and u.endsAt > :startsAt")
    boolean existsBreakOverlapping(@Param("profileId") Long profileId,
                                   @Param("startsAt") Instant startsAt,
                                   @Param("endsAt") Instant endsAt);

    @Query("select (count(u) > 0) from GroundExecutiveUnavailability u "
            + "where u.schedulingProfile.employeeProfileId = :profileId "
            + "and u.intervalType = com.indore.pathome.spaces.entity.GroundExecutiveUnavailableType.BREAK "
            + "and u.startsAt < :oldEnd and u.endsAt > :oldStart "
            + "and (u.startsAt < :newStart or u.endsAt > :newEnd or u.zoneId <> :newZoneId)")
    boolean existsBreakMadeInvalidByShiftUpdate(@Param("profileId") Long profileId,
                                                @Param("oldStart") Instant oldStart,
                                                @Param("oldEnd") Instant oldEnd,
                                                @Param("newStart") Instant newStart,
                                                @Param("newEnd") Instant newEnd,
                                                @Param("newZoneId") String newZoneId);

    @Query("select (count(u) > 0) from GroundExecutiveUnavailability u "
            + "where u.schedulingProfile.employeeProfileId = :profileId and u.id <> :excludedId "
            + "and u.startsAt < :windowEnd and u.endsAt > :windowStart")
    boolean existsOverlappingExcept(@Param("profileId") Long profileId, @Param("excludedId") Long excludedId,
                                    @Param("windowStart") Instant windowStart,
                                    @Param("windowEnd") Instant windowEnd);

    Optional<GroundExecutiveUnavailability> findByIdAndSchedulingProfileEmployeeProfileId(Long id, Long profileId);
}
