package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadShare;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LeadShareRepository extends JpaRepository<LeadShare, UUID> {
    Page<LeadShare> findAllByLead_IdOrderByRecordedAtDescIdDesc(Long leadId, Pageable pageable);
}
