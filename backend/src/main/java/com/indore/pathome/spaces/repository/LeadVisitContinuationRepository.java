package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadVisitContinuation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LeadVisitContinuationRepository extends JpaRepository<LeadVisitContinuation, UUID> {
    Optional<LeadVisitContinuation> findBySecretHash(String secretHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LeadVisitContinuation c where c.id = :id")
    Optional<LeadVisitContinuation> findLockedById(@Param("id") UUID id);
}
