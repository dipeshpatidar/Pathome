package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.OperatingTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperatingTeamRepository extends JpaRepository<OperatingTeam, Long> {
    List<OperatingTeam> findAllByCityIdOrderByCodeAsc(Long cityId);

    Optional<OperatingTeam> findByCityIdAndCode(Long cityId, String code);
}
