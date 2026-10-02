package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.GroundExecutiveShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface GroundExecutiveShiftRepository extends JpaRepository<GroundExecutiveShift, Long> {
    @Query("select s from GroundExecutiveShift s where s.schedulingProfile.employeeProfileId = :profileId "
            + "and s.startsAt < :windowEnd and s.endsAt > :windowStart order by s.startsAt, s.id")
    List<GroundExecutiveShift> findOverlappingWindow(@Param("profileId") Long profileId,
                                                      @Param("windowStart") Instant windowStart,
                                                      @Param("windowEnd") Instant windowEnd);

    @Query("select s from GroundExecutiveShift s where s.schedulingProfile.employeeProfileId in :profileIds "
            + "and s.startsAt < :windowEnd and s.endsAt > :windowStart order by s.schedulingProfile.employeeProfileId, s.startsAt, s.id")
    List<GroundExecutiveShift> findOverlappingForProfiles(@Param("profileIds") Collection<Long> profileIds,
                                                          @Param("windowStart") Instant windowStart,
                                                          @Param("windowEnd") Instant windowEnd);

    @Query("select (count(s) > 0) from GroundExecutiveShift s "
            + "where s.schedulingProfile.employeeProfileId = :profileId "
            + "and s.startsAt <= :startsAt and s.endsAt >= :endsAt and s.zoneId = :zoneId")
    boolean existsShiftContaining(@Param("profileId") Long profileId,
                                  @Param("startsAt") Instant startsAt,
                                  @Param("endsAt") Instant endsAt,
                                  @Param("zoneId") String zoneId);

    @Query("select (count(s) > 0) from GroundExecutiveShift s "
            + "where s.schedulingProfile.employeeProfileId = :profileId and s.id <> :excludedId "
            + "and s.startsAt < :windowEnd and s.endsAt > :windowStart")
    boolean existsOverlappingExcept(@Param("profileId") Long profileId, @Param("excludedId") Long excludedId,
                                    @Param("windowStart") Instant windowStart,
                                    @Param("windowEnd") Instant windowEnd);

    Optional<GroundExecutiveShift> findByIdAndSchedulingProfileEmployeeProfileId(Long id, Long profileId);
}
