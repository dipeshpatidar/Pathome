package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyUploadDraftRepository extends JpaRepository<PropertyUploadDraft, Long> {

    List<PropertyUploadDraft> findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(String adminId, String status);

    Optional<PropertyUploadDraft> findByDraftIdAndAdminId(String draftId, String adminId);

    Optional<PropertyUploadDraft> findByDraftIdAndLandlordUserId(String draftId, Long landlordUserId);

    Optional<PropertyUploadDraft> findFirstByPublishedPropertyIdAndLandlordUserIdAndStatusInOrderByIdDesc(
            Long publishedPropertyId, Long landlordUserId, List<String> statuses);

    List<PropertyUploadDraft> findByPublishedPropertyIdInAndLandlordUserIdAndStatusIn(
            List<Long> listingIds, Long landlordUserId, List<String> statuses);

    Slice<PropertyUploadDraft> findByStatusAndPublishedPropertyIdIsNotNullAndLandlordUserIdIsNotNullOrderByUpdatedAtDescIdDesc(
            String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM PropertyUploadDraft d WHERE d.draftId = :draftId AND d.landlordUserId = :ownerId")
    Optional<PropertyUploadDraft> findLandlordDraftForUpdate(@Param("draftId") String draftId,
                                                             @Param("ownerId") Long ownerId);

    Slice<PropertyUploadDraft> findByLandlordUserIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long landlordUserId, String status, Pageable pageable);

    long countByLandlordUserIdAndStatus(Long landlordUserId, String status);

    Optional<PropertyUploadDraft> findByDraftId(String draftId);

    @Query("SELECT d FROM PropertyUploadDraft d WHERE d.status = 'DISCARDED' AND d.id > :lastId " +
           "AND (d.landlordUserId IS NOT NULL OR d.guestTokenHash IS NOT NULL) " +
           "AND EXISTS (SELECT m.id FROM PropertyDraftMedia m WHERE m.draftId = d.draftId) ORDER BY d.id ASC")
    List<PropertyUploadDraft> findDiscardedLessorAfterId(@Param("lastId") Long lastId, Pageable pageable);

    Optional<PropertyUploadDraft> findByGuestTokenHashAndStatus(String guestTokenHash, String status);

    List<PropertyUploadDraft> findByGuestTokenHashIsNotNullAndGuestExpiresAtBeforeAndIdGreaterThanOrderByIdAsc(
            LocalDateTime cutoff, Long lastId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM PropertyUploadDraft d WHERE d.draftId = :draftId")
    Optional<PropertyUploadDraft> findByDraftIdForUpdate(@Param("draftId") String draftId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PropertyUploadDraft d SET d.updatedAt = :now WHERE d.draftId = :draftId AND d.adminId = :adminId")
    int touchUpdatedAt(@Param("draftId") String draftId, @Param("adminId") String adminId, @Param("now") LocalDateTime now);


    List<PropertyUploadDraft> findAllByStatusNotInAndUpdatedAtBefore(
            List<String> statuses,
            LocalDateTime cutoff,
            Pageable pageable
    );

    List<PropertyUploadDraft> findAllByAdminIdIsNotNullAndStatusNotInAndUpdatedAtBefore(
            List<String> statuses, LocalDateTime cutoff, Pageable pageable);

    long deleteByDraftIdAndAdminId(String draftId, String adminId);
}
