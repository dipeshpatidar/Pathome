package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadPhoneChallengeRepository extends JpaRepository<LeadPhoneChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LeadPhoneChallenge c where c.id = :id")
    Optional<LeadPhoneChallenge> findLockedById(@Param("id") UUID id);

    Optional<LeadPhoneChallenge> findFirstByLead_IdOrderByCreatedAtDescIdDesc(Long leadId);

    Optional<LeadPhoneChallenge> findByConfirmationTokenHash(String tokenHash);

    @Query("""
            select c
            from LeadPhoneChallenge c
            where c.lead.id = :leadId
              and c.sourceEvent.id = :sourceEventId
              and c.purpose = :purpose
              and c.contactPhoneRevision = :contactPhoneRevision
            order by c.generation desc, c.createdAt desc
            """)
    List<LeadPhoneChallenge> findGenerations(@Param("leadId") Long leadId,
                                              @Param("sourceEventId") UUID sourceEventId,
                                              @Param("purpose") String purpose,
                                              @Param("contactPhoneRevision") Long contactPhoneRevision);

    @Query("""
            select count(c)
            from LeadPhoneChallenge c
            where c.createdBy.id = :actorId and c.createdAt >= :since
            """)
    long countByActorSince(@Param("actorId") Long actorId, @Param("since") Instant since);

    @Query("""
            select count(c)
            from LeadPhoneChallenge c
            where c.numberFingerprint = :fingerprint and c.createdAt >= :since
            """)
    long countByDestinationSince(@Param("fingerprint") String fingerprint,
                                 @Param("since") Instant since);

    @Query("""
            select c
            from LeadPhoneChallenge c
            where c.lead.id = :leadId
              and c.sourceEvent.id = :sourceEventId
              and c.purpose = :purpose
              and c.contactPhoneRevision = :contactPhoneRevision
              and c.numberFingerprint = :fingerprint
              and c.state = com.indore.pathome.spaces.entity.LeadPhoneChallengeState.VERIFIED
              and c.verifiedAt >= :verifiedSince
            order by c.verifiedAt desc, c.generation desc
            """)
    List<LeadPhoneChallenge> findCurrentProofs(@Param("leadId") Long leadId,
                                                @Param("sourceEventId") UUID sourceEventId,
                                                @Param("purpose") String purpose,
                                                @Param("contactPhoneRevision") Long contactPhoneRevision,
                                                @Param("fingerprint") String fingerprint,
                                                @Param("verifiedSince") Instant verifiedSince);

    @Modifying
    @Query("""
            update LeadPhoneChallenge c
            set c.state = com.indore.pathome.spaces.entity.LeadPhoneChallengeState.CANCELLED,
                c.cancelledAt = :at
            where c.lead.id = :leadId
              and c.state = com.indore.pathome.spaces.entity.LeadPhoneChallengeState.PENDING
            """)
    int cancelPendingForLead(@Param("leadId") Long leadId, @Param("at") Instant at);
}
