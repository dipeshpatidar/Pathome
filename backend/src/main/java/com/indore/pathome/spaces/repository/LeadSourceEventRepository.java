package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadSourceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface LeadSourceEventRepository extends JpaRepository<LeadSourceEvent, UUID> {
    Optional<LeadSourceEvent> findByProviderAndProviderAccountIdAndExternalEventId(
            String provider, String providerAccountId, String externalEventId);

    List<LeadSourceEvent> findAllByLead_IdOrderByReceivedAtDescIdDesc(Long leadId);

    Optional<LeadSourceEvent> findFirstByProviderAndProviderAccountIdAndExternalEventIdOrderByReceivedAtDesc(
            String provider, String providerAccountId, String externalEventId);

    @org.springframework.data.jpa.repository.Query("""
            select e from LeadSourceEvent e
            where e.processingState in (com.indore.pathome.spaces.entity.LeadSourceEventState.PENDING,
                                        com.indore.pathome.spaces.entity.LeadSourceEventState.PROCESSING,
                                        com.indore.pathome.spaces.entity.LeadSourceEventState.AWAITING_REVIEW,
                                        com.indore.pathome.spaces.entity.LeadSourceEventState.QUARANTINED)
            order by e.receivedAt desc, e.id desc
            """)
    org.springframework.data.domain.Page<LeadSourceEvent> findExceptionPage(
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from LeadSourceEvent e where e.id = :id")
    Optional<LeadSourceEvent> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
}
