package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.Locality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocalityRepository extends JpaRepository<Locality, Long> {
    Optional<Locality> findByCityIgnoreCaseAndSectorNameIgnoreCase(String city, String sectorName);
    Optional<Locality> findBySectorNameIgnoreCase(String sectorName);
    List<Locality> findAllBySectorNameIgnoreCase(String sectorName);
    List<Locality> findByCityIgnoreCase(String city);

    @Query("SELECT DISTINCT l.city FROM Locality l WHERE l.city IS NOT NULL")
    List<String> findDistinctCities();
}

