package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SearchAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SearchAliasRepository extends JpaRepository<SearchAlias, Long> {

    /**
     * Fetch all ACTIVE locality aliases scoped to a given city (or cross-city if entityCity matches).
     * Loaded into the in-memory resolver cache at startup and after promotion events.
     */
    @Query("SELECT a FROM SearchAlias a " +
           "WHERE a.status = 'ACTIVE' " +
           "  AND a.entityType = 'LOCALITY' " +
           "  AND (:cityKey = '' OR lower(trim(a.entityCity)) = :cityKey)")
    List<SearchAlias> findActiveLocalityAliasesForCity(@Param("cityKey") String cityKey);

    /**
     * Look up a single ACTIVE locality alias by normalised term and city scope.
     * Used in the hot-path resolver before falling back to fuzzy.
     */
    @Query("SELECT a FROM SearchAlias a " +
           "WHERE a.status = 'ACTIVE' " +
           "  AND a.entityType = 'LOCALITY' " +
           "  AND a.aliasTerm = :aliasTerm " +
           "  AND (:cityKey = '' OR lower(trim(a.entityCity)) = :cityKey) " +
           "ORDER BY a.confidence DESC")
    List<SearchAlias> findActiveLocalityAlias(
            @Param("aliasTerm") String aliasTerm,
            @Param("cityKey") String cityKey);

    /** Check if an active alias already exists for this term+type+city combination. */
    @Query("SELECT a FROM SearchAlias a WHERE lower(trim(a.aliasTerm)) = lower(trim(:aliasTerm)) "
            + "AND a.entityType = :entityType AND lower(trim(coalesce(a.entityCity, ''))) = lower(trim(coalesce(:entityCity, '')))")
    Optional<SearchAlias> findByAliasTermAndEntityTypeAndEntityCity(
            @Param("aliasTerm") String aliasTerm, @Param("entityType") String entityType, @Param("entityCity") String entityCity);

    long countByStatus(String status);
}
