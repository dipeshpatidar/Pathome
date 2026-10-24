package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadContactPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeadContactPreferenceRepository extends JpaRepository<LeadContactPreference, Long> {
    Optional<LeadContactPreference> findFirstByLead_IdOrderByRevisionDesc(Long leadId);
    Optional<LeadContactPreference> findByLead_IdAndRevision(Long leadId, Long revision);
}
