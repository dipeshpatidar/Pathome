package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.Locality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocalityRepository extends JpaRepository<Locality, Long> {
    Optional<Locality> findByCityIgnoreCaseAndSectorNameIgnoreCase(String city, String sectorName);
    Optional<Locality> findBySectorNameIgnoreCase(String sectorName);
    List<Locality> findAllBySectorNameIgnoreCase(String sectorName);
    List<Locality> findTop8BySectorNameIgnoreCaseOrderByCityAsc(String sectorName);
    List<Locality> findByCityIgnoreCase(String city);

    @Query("SELECT DISTINCT l.city FROM Locality l WHERE l.city IS NOT NULL")
    List<String> findDistinctCities();

    @Query(value = "SELECT l.* FROM localities l WHERE lower(l.city) = lower(:city) " +
            "AND (lower(l.sector_name) LIKE lower(concat(:term, '%')) " +
            "OR similarity(lower(l.sector_name), lower(:term)) >= 0.38) " +
            "ORDER BY CASE WHEN lower(l.sector_name) = lower(:term) THEN 0 " +
            "WHEN lower(l.sector_name) LIKE lower(concat(:term, '%')) THEN 1 ELSE 2 END, " +
            "similarity(lower(l.sector_name), lower(:term)) DESC, l.sector_name ASC",
            nativeQuery = true)
    List<Locality> findOnboardingSuggestions(@Param("city") String city,
                                             @Param("term") String term, Pageable pageable);
}
