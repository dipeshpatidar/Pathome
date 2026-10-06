package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SupportedCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupportedCityRepository extends JpaRepository<SupportedCity, Long> {
    Optional<SupportedCity> findByCode(String code);

    List<SupportedCity> findAllByActiveTrueOrderByDisplayNameAsc();

    Optional<SupportedCity> findByIdAndActiveTrue(Long id);

    @Query("SELECT city FROM SupportedCity city WHERE lower(trim(city.displayName)) = lower(:displayName)")
    List<SupportedCity> findExactDisplayNameMatches(@Param("displayName") String displayName);
}
