package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PropertyVisitRequestRepository extends JpaRepository<PropertyVisitRequest, Long> {
    Optional<PropertyVisitRequest> findByTenantIdAndListingId(Long tenantId, Long listingId);
}
