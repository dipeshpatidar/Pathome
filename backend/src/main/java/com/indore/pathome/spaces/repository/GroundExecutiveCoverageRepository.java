package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.GroundExecutiveCoverage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface GroundExecutiveCoverageRepository extends JpaRepository<GroundExecutiveCoverage, Long> {
    @EntityGraph(attributePaths = "locality")
    List<GroundExecutiveCoverage> findBySchedulingProfileEmployeeProfileIdOrderByCityAsc(
            Long profileId);

    void deleteBySchedulingProfileEmployeeProfileId(Long profileId);

    @EntityGraph(attributePaths = "locality")
    List<GroundExecutiveCoverage> findBySchedulingProfileEmployeeProfileIdIn(Collection<Long> profileIds);
}
