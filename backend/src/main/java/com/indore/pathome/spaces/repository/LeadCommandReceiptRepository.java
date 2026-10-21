package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeadCommandReceiptRepository extends JpaRepository<LeadCommandReceipt, UUID> {
    Optional<LeadCommandReceipt> findByActorIdAndOperationIdAndAction(
            Long actorId, UUID operationId, String action);
}
