package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadPhoneDeliveryOutboxRepository extends JpaRepository<LeadPhoneDeliveryOutbox, UUID> {
    Optional<LeadPhoneDeliveryOutbox> findByChallenge_Id(UUID challengeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LeadPhoneDeliveryOutbox o where o.id = :id")
    Optional<LeadPhoneDeliveryOutbox> findLockedById(@Param("id") UUID id);

    @Query("""
            select o.id
            from LeadPhoneDeliveryOutbox o
            where (o.state = com.indore.pathome.spaces.entity.LeadPhoneDeliveryState.QUEUED
                   and o.availableAt <= :now and o.leaseUntil is null)
               or (o.state in (com.indore.pathome.spaces.entity.LeadPhoneDeliveryState.QUEUED,
                               com.indore.pathome.spaces.entity.LeadPhoneDeliveryState.DISPATCHING)
                   and o.leaseUntil is not null and o.leaseUntil <= :now)
            order by o.createdAt asc, o.id asc
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);
}
