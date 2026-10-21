package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, Long> {
    Page<LeadActivity> findAllByLead_IdOrderByOccurredAtDescIdDesc(Long leadId, Pageable pageable);

    Optional<LeadActivity> findFirstByLead_IdOrderByOccurredAtDescIdDesc(Long leadId);

    @Query("""
            select activity.lead.id, max(activity.occurredAt)
            from LeadActivity activity
            where activity.lead.id in :leadIds
            group by activity.lead.id
            """)
    List<Object[]> findLatestActivityByLeadIds(@Param("leadIds") Collection<Long> leadIds);
}
