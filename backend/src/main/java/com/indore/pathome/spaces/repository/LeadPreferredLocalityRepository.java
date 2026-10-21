package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadPreferredLocality;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadPreferredLocalityRepository extends JpaRepository<LeadPreferredLocality, Long> {
    List<LeadPreferredLocality> findAllByLead_IdOrderByLocality_IdAsc(Long leadId);
    void deleteAllByLead_Id(Long leadId);
}
