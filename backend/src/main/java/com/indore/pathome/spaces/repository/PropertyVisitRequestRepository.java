package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PropertyVisitRequestRepository extends JpaRepository<PropertyVisitRequest, Long> {
    Optional<PropertyVisitRequest> findByTenantIdAndListingId(Long tenantId, Long listingId);

    @EntityGraph(attributePaths = "listing")
    Page<PropertyVisitRequest> findByTenantId(Long tenantId, Pageable pageable);
}
