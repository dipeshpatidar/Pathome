package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadVisitProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface LeadVisitProposalRepository extends JpaRepository<LeadVisitProposal, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LeadVisitProposal p where p.id = :id")
    Optional<LeadVisitProposal> findLockedById(@Param("id") UUID id);

    Optional<LeadVisitProposal> findByTokenHash(String tokenHash);

    Optional<LeadVisitProposal> findByIdAndLead_Id(UUID id, Long leadId);

    List<LeadVisitProposal> findAllByLead_IdAndListingIdAndStateOrderByCreatedAtDesc(
            Long leadId, Long listingId, com.indore.pathome.spaces.entity.LeadVisitProposalState state);
}
