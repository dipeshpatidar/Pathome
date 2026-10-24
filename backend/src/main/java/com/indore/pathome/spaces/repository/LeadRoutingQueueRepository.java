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
            where lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL
              and ((lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
              and (cast(:cityId as long) is null or lead.supportedCity.id = :cityId)
              and (cast(:teamId as long) is null or lead.operatingTeam.id = :teamId)
              and (cast(:status as string) is null or lead.status = :status)
              and (:ownerMe = false or lead.owner.id = :actorId)
              and (
                    cast(:queryPattern as string) is null
                    or lower(coalesce(lead.tenantName, '')) like :queryPattern escape '!'
                    or (cast(:queryLeadId as long) is not null and lead.id = :queryLeadId)
                    or (
                        cast(:queryPhoneSuffix as string) is not null
                        and lead.contactPhoneNormalized like :queryPhoneSuffix
                    )
                    or exists (
                        select preferred.id
                        from LeadPreferredLocality preferred
                        where preferred.lead = lead
                          and (
                              lower(preferred.locality.sectorName) like :queryPattern escape '!'
                              or lower(coalesce(preferred.locality.aliases, ''))
                                  like :queryPattern escape '!'
                          )
                    )
              )
              and (:overdue = false or lead.followUpAt < :now)
              and (:today = false or (
                    lead.followUpAt >= :todayStart and lead.followUpAt < :todayEnd
              ))
              and (:noFollowUp = false or lead.followUpAt is null)
              and (cast(:legacyFollowUpBefore as timestamp) is null
                   or lead.followUpAt < :legacyFollowUpBefore)
            order by lead.createdAt desc, lead.id desc
            """,
            countQuery = """
            select count(lead)
            from LeadRoutingQueue lead
            where lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL
              and ((lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
              and (cast(:cityId as long) is null or lead.supportedCity.id = :cityId)
              and (cast(:teamId as long) is null or lead.operatingTeam.id = :teamId)
              and (cast(:status as string) is null or lead.status = :status)
              and (:ownerMe = false or lead.owner.id = :actorId)
              and (
                    cast(:queryPattern as string) is null
                    or lower(coalesce(lead.tenantName, '')) like :queryPattern escape '!'
                    or (cast(:queryLeadId as long) is not null and lead.id = :queryLeadId)
                    or (
                        cast(:queryPhoneSuffix as string) is not null
                        and lead.contactPhoneNormalized like :queryPhoneSuffix
                    )
                    or exists (
                        select preferred.id
                        from LeadPreferredLocality preferred
                        where preferred.lead = lead
                          and (
                              lower(preferred.locality.sectorName) like :queryPattern escape '!'
                              or lower(coalesce(preferred.locality.aliases, ''))
                                  like :queryPattern escape '!'
                          )
                    )
              )
              and (:overdue = false or lead.followUpAt < :now)
              and (:today = false or (
                    lead.followUpAt >= :todayStart and lead.followUpAt < :todayEnd
              ))
              and (:noFollowUp = false or lead.followUpAt is null)
              and (cast(:legacyFollowUpBefore as timestamp) is null
                   or lead.followUpAt < :legacyFollowUpBefore)
            """)
    Page<LeadRoutingQueue> findVisibleLeadPage(
            @Param("actorId") Long actorId,
            @Param("cityIds") List<Long> cityIds,
            @Param("ownedTeamIds") List<Long> ownedTeamIds,
            @Param("supervisedTeamIds") List<Long> supervisedTeamIds,
            @Param("cityId") Long cityId,
            @Param("teamId") Long teamId,
            @Param("status") LeadStatus status,
            @Param("ownerMe") boolean ownerMe,
            @Param("queryPattern") String queryPattern,
            @Param("queryLeadId") Long queryLeadId,
            @Param("queryPhoneSuffix") String queryPhoneSuffix,
            @Param("overdue") boolean overdue,
            @Param("today") boolean today,
            @Param("noFollowUp") boolean noFollowUp,
            @Param("now") Instant now,
            @Param("todayStart") Instant todayStart,
            @Param("todayEnd") Instant todayEnd,
            @Param("legacyFollowUpBefore") Instant legacyFollowUpBefore,
            Pageable pageable);

    @Query("""
            select lead
            from LeadRoutingQueue lead
            where lead.id = :leadId
              and lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL
              and ((lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
            """)
    Optional<LeadRoutingQueue> findVisibleById(
            @Param("leadId") Long leadId,
            @Param("actorId") Long actorId,
            @Param("cityIds") List<Long> cityIds,
            @Param("ownedTeamIds") List<Long> ownedTeamIds,
            @Param("supervisedTeamIds") List<Long> supervisedTeamIds);

    @Query("""
            select lead
            from LeadRoutingQueue lead
            where lead.contactPhoneNormalized = :contactPhone
              and lead.legacyProvenance = com.indore.pathome.spaces.entity.LeadLegacyProvenance.VERIFIED_MANUAL
              and (:cityId is null or lead.supportedCity.id = :cityId)
              and ((lead.operatingTeam is not null and lead.owner is not null
                        and lead.operatingTeam.id in :ownedTeamIds and lead.owner.id = :actorId)
                    or (lead.operatingTeam is not null and lead.operatingTeam.id in :supervisedTeamIds)
                    or (lead.operatingTeam is null and lead.owner is null
                        and lead.supportedCity is not null and lead.supportedCity.id in :cityIds))
            order by lead.createdAt desc, lead.id desc
            """)
    List<LeadRoutingQueue> findDuplicateCandidates(
            @Param("contactPhone") String contactPhone,
            @Param("cityId") Long cityId,
            @Param("actorId") Long actorId,
            @Param("cityIds") List<Long> cityIds,
            @Param("ownedTeamIds") List<Long> ownedTeamIds,
            @Param("supervisedTeamIds") List<Long> supervisedTeamIds,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lead from LeadRoutingQueue lead where lead.id = :id")
    Optional<LeadRoutingQueue> findLockedById(@Param("id") Long id);
}
