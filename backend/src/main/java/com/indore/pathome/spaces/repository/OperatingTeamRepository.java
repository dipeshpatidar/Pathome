package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.OperatingTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperatingTeamRepository extends JpaRepository<OperatingTeam, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from OperatingTeam team where team.id = :id")
    Optional<OperatingTeam> findLockedById(@Param("id") Long id);

    List<OperatingTeam> findAllByCityIdOrderByIdAsc(Long cityId);

    List<OperatingTeam> findAllByCityIdOrderByCodeAsc(Long cityId);

    Optional<OperatingTeam> findByCityIdAndCode(Long cityId, String code);

    Optional<OperatingTeam> findByIdAndActiveTrue(Long id);
}
