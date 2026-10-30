package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadVisitLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface LeadVisitLinkRepository extends JpaRepository<LeadVisitLink, UUID> {
    Optional<LeadVisitLink> findByLead_Id(Long leadId);
    List<LeadVisitLink> findAllByLead_IdOrderByConfirmedAtAscIdAsc(Long leadId);
}
