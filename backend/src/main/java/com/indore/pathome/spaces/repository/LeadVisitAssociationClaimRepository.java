package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadVisitAssociationClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface LeadVisitAssociationClaimRepository
        extends JpaRepository<LeadVisitAssociationClaim, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LeadVisitAssociationClaim c where c.id = :id")
    Optional<LeadVisitAssociationClaim> findLockedById(@Param("id") UUID id);

    Optional<LeadVisitAssociationClaim> findByProposal_IdAndUser_Id(UUID proposalId, Long userId);

    Optional<LeadVisitAssociationClaim> findFirstByProposal_IdOrderByCreatedAtDesc(UUID proposalId);

    long countByProposal_IdAndState(
            UUID proposalId, com.indore.pathome.spaces.entity.LeadVisitAssociationStatus state);

    List<LeadVisitAssociationClaim> findAllByProposal_IdOrderByCreatedAtAscIdAsc(UUID proposalId);
}
