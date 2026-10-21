package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadSourceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeadSourceEventRepository extends JpaRepository<LeadSourceEvent, UUID> {
    Optional<LeadSourceEvent> findByProviderAndProviderAccountIdAndExternalEventId(
            String provider, String providerAccountId, String externalEventId);
}
