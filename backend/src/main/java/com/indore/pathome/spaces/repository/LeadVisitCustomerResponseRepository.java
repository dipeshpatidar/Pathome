package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadVisitCustomerResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeadVisitCustomerResponseRepository
        extends JpaRepository<LeadVisitCustomerResponse, UUID> {
    Optional<LeadVisitCustomerResponse> findByProposal_IdAndUser_Id(UUID proposalId, Long userId);
}
