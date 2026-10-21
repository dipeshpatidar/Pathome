package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRoutingQueueRepository extends JpaRepository<LeadRoutingQueue, Long> {

    @Query(value = """
            select lead
            from LeadRoutingQueue lead
            where (:globalAdmin = true
                    or lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL)
              and (:globalAdmin = true
                    or (lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
              and (:cityId is null or lead.supportedCity.id = :cityId)
              and (:teamId is null or lead.operatingTeam.id = :teamId)
              and (:status is null or lead.status = :status)
              and (:ownerMe = false or lead.owner.id = :actorId)
              and (:followUpBefore is null or lead.followUpAt < :followUpBefore)
            order by lead.createdAt desc, lead.id desc
            """,
            countQuery = """
            select count(lead)
            from LeadRoutingQueue lead
            where (:globalAdmin = true
                    or lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL)
              and (:globalAdmin = true
                    or (lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
              and (:cityId is null or lead.supportedCity.id = :cityId)
              and (:teamId is null or lead.operatingTeam.id = :teamId)
              and (:status is null or lead.status = :status)
              and (:ownerMe = false or lead.owner.id = :actorId)
              and (:followUpBefore is null or lead.followUpAt < :followUpBefore)
            """)
    Page<LeadRoutingQueue> findVisibleLeadPage(
            @Param("actorId") Long actorId,
            @Param("globalAdmin") boolean globalAdmin,
            @Param("cityIds") List<Long> cityIds,
            @Param("ownedTeamIds") List<Long> ownedTeamIds,
            @Param("supervisedTeamIds") List<Long> supervisedTeamIds,
            @Param("cityId") Long cityId,
            @Param("teamId") Long teamId,
            @Param("status") LeadStatus status,
            @Param("ownerMe") boolean ownerMe,
            @Param("followUpBefore") Instant followUpBefore,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lead from LeadRoutingQueue lead where lead.id = :id")
    Optional<LeadRoutingQueue> findLockedById(@Param("id") Long id);
}
