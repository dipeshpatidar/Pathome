package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.OperatingTeam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Package 3A provisioning read: every Team of one City, inactive ones included, so a retained
     * code is visible before a new creation is attempted. Deterministic by canonical code.
     */
    @Query(value = "select team from OperatingTeam team where team.city.id = :cityId "
            + "order by team.code asc, team.id asc",
            countQuery = "select count(team) from OperatingTeam team where team.city.id = :cityId")
    Page<OperatingTeam> findTeamPageByCityId(@Param("cityId") Long cityId, Pageable pageable);

    /**
     * Package 3B routing read: only <em>active</em> Teams of one City are eligible destinations, so
     * the eligibility predicate lives in PostgreSQL rather than in the request path.
     */
    @Query(value = "select team from OperatingTeam team where team.city.id = :cityId and team.active = true "
            + "order by team.code asc, team.id asc",
            countQuery = "select count(team) from OperatingTeam team where team.city.id = :cityId "
                    + "and team.active = true")
    Page<OperatingTeam> findActiveTeamPageByCityId(@Param("cityId") Long cityId, Pageable pageable);
}
