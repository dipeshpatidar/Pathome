package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.SupportedCity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupportedCityRepository extends JpaRepository<SupportedCity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select city from SupportedCity city where city.id = :id")
    Optional<SupportedCity> findLockedById(@Param("id") Long id);

    Optional<SupportedCity> findByCode(String code);

    List<SupportedCity> findAllByActiveTrueOrderByDisplayNameAsc();

    Optional<SupportedCity> findByIdAndActiveTrue(Long id);

    /**
     * Package 3A provisioning read: active canonical Cities in a deterministic order, filtered and
     * paginated in PostgreSQL rather than in the request path.
     */
    @Query(value = "select city from SupportedCity city where city.active = true "
            + "order by city.displayName asc, city.id asc",
            countQuery = "select count(city) from SupportedCity city where city.active = true")
    Page<SupportedCity> findActiveCityPage(Pageable pageable);

    @Query("SELECT city FROM SupportedCity city WHERE lower(trim(city.displayName)) = lower(:displayName)")
    List<SupportedCity> findExactDisplayNameMatches(@Param("displayName") String displayName);
}
