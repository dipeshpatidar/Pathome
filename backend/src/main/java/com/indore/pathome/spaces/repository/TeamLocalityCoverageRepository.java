package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.TeamLocalityCoverage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamLocalityCoverageRepository extends JpaRepository<TeamLocalityCoverage, Long> {
    interface ActiveCoverageRow {
        Long getId();
        Long getCityId();
        Long getLocalityId();
        Long getTeamId();
        Long getCoverageVersion();
        Long getTeamVersion();
    }

    String ACTIVE_BY_LOCALITY = """
            SELECT c.id AS id, c.supported_city_id AS "cityId",
                   c.canonical_locality_id AS "localityId", c.operating_team_id AS "teamId",
                   c.version AS "coverageVersion", t.version AS "teamVersion"
            FROM team_locality_coverage c
            JOIN supported_cities city ON city.id = c.supported_city_id AND city.active = TRUE
            JOIN operating_teams t ON t.id = c.operating_team_id
                                  AND t.city_id = c.supported_city_id AND t.active = TRUE
            WHERE c.active = TRUE AND c.supported_city_id = :cityId
              AND c.canonical_locality_id = :localityId
            ORDER BY t.id, c.id
            """;

    @Query(value = ACTIVE_BY_LOCALITY, nativeQuery = true)
    List<ActiveCoverageRow> findActiveRoutingCandidates(@Param("cityId") Long cityId,
                                                        @Param("localityId") Long localityId);

    Optional<TeamLocalityCoverage> findByCanonicalLocalityIdAndOperatingTeamId(Long localityId, Long teamId);

    @Query("""
            SELECT c FROM TeamLocalityCoverage c
            JOIN FETCH c.canonicalLocality
            JOIN FETCH c.operatingTeam
            WHERE c.supportedCity.id = :cityId
              AND c.canonicalLocality.id = :localityId
              AND c.active = TRUE
            ORDER BY c.operatingTeam.id, c.id
            """)
    List<TeamLocalityCoverage> findActiveByCityAndLocality(@Param("cityId") Long cityId,
                                                           @Param("localityId") Long localityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select coverage from TeamLocalityCoverage coverage where coverage.id = :id")
    Optional<TeamLocalityCoverage> findLockedById(@Param("id") Long id);

    @Query(value = """
            SELECT c FROM TeamLocalityCoverage c
            JOIN FETCH c.canonicalLocality
            JOIN FETCH c.operatingTeam
            WHERE c.supportedCity.id IN :cityIds
            ORDER BY c.supportedCity.id, c.canonicalLocality.id, c.operatingTeam.id
            """,
            countQuery = "select count(c) from TeamLocalityCoverage c where c.supportedCity.id in :cityIds")
    Page<TeamLocalityCoverage> findPageByCityIds(@Param("cityIds") List<Long> cityIds, Pageable pageable);

    @Query(value = """
            SELECT c FROM TeamLocalityCoverage c
            JOIN FETCH c.canonicalLocality
            JOIN FETCH c.operatingTeam
            ORDER BY c.supportedCity.id, c.canonicalLocality.id, c.operatingTeam.id
            """,
            countQuery = "select count(c) from TeamLocalityCoverage c")
    Page<TeamLocalityCoverage> findAllPage(Pageable pageable);
}
