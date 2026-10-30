package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadLessorAvailabilityEnquiry;
import com.indore.pathome.spaces.entity.LeadLessorAvailabilityOutcome;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LeadLessorAvailabilityEnquiryRepository
        extends JpaRepository<LeadLessorAvailabilityEnquiry, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LeadLessorAvailabilityEnquiry e where e.id = :id")
    Optional<LeadLessorAvailabilityEnquiry> findLockedById(@Param("id") UUID id);

    Optional<LeadLessorAvailabilityEnquiry> findByIdAndLead_Id(UUID id, Long leadId);

    Optional<LeadLessorAvailabilityEnquiry>
            findFirstByLead_IdAndShortlistIdAndListingIdAndSourceEvent_IdAndOutcomeOrderByCreatedAtDesc(
                    Long leadId, Long shortlistId, Long listingId, UUID sourceEventId,
                    LeadLessorAvailabilityOutcome outcome);
}
