package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyRemovalRequest;
import com.indore.pathome.spaces.entity.PropertyRemovalRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyRemovalRequestRepository extends JpaRepository<PropertyRemovalRequest, Long> {
    Optional<PropertyRemovalRequest> findFirstByListingIdAndRequestStatusOrderByIdDesc(
            Long listingId, PropertyRemovalRequestStatus status);

    Optional<PropertyRemovalRequest> findByIdAndListingIdAndRequesterUserId(
            Long id, Long listingId, Long requesterUserId);

    Optional<PropertyRemovalRequest> findByListingIdAndRequesterUserIdAndCreationOperationId(
            Long listingId, Long requesterUserId, UUID creationOperationId);

    Optional<PropertyRemovalRequest> findByTerminalOperationId(UUID operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PropertyRemovalRequest r where r.id = :id")
    Optional<PropertyRemovalRequest> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PropertyRemovalRequest r where r.listingId = :listingId and r.requestStatus = com.indore.pathome.spaces.entity.PropertyRemovalRequestStatus.PENDING")
    List<PropertyRemovalRequest> findPendingForUpdate(@Param("listingId") Long listingId);

    @Query("select r from PropertyRemovalRequest r " +
            "where (:status is null or r.requestStatus = :status) " +
            "and (:cityIdsEmpty = true or exists (" +
            "  select 1 from Listing l, Locality loc " +
            "  where l.id = r.listingId and loc.id = l.canonicalLocalityId and loc.supportedCity.id in :cityIds" +
            ")) " +
            "order by r.requestedAt desc, r.id desc")
    Slice<PropertyRemovalRequest> findAdminQueue(@Param("status") PropertyRemovalRequestStatus status,
                                                 @Param("cityIds") List<Long> cityIds,
                                                 @Param("cityIdsEmpty") boolean cityIdsEmpty,
                                                 Pageable pageable);

    @Query("select r from PropertyRemovalRequest r " +
            "where r.listingId = :listingId and r.requesterUserId = :requesterUserId " +
            "order by r.requestedAt desc, r.id desc")
    Slice<PropertyRemovalRequest> findOwnerHistory(@Param("listingId") Long listingId,
                                                    @Param("requesterUserId") Long requesterUserId,
                                                    Pageable pageable);
}

