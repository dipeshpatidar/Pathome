package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface LeadAssistanceCommandReceiptRepository
        extends JpaRepository<LeadAssistanceCommandReceipt, UUID> {
    Optional<LeadAssistanceCommandReceipt> findByActor_IdAndOperationIdAndAction(
            Long actorId, UUID operationId, String action);

    List<LeadAssistanceCommandReceipt> findAllByActor_IdAndOperationId(
            Long actorId, UUID operationId);
}
