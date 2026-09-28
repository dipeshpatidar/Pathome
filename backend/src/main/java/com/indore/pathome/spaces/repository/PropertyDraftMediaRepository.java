package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyDraftMediaRepository extends JpaRepository<PropertyDraftMedia, Long> {
    boolean existsByDraftIdAndGuestOwnedTrueAndIsCoverTrueAndUploadStatusAndMediaIdNot(
            String draftId, String uploadStatus, String mediaId);

    List<PropertyDraftMedia> findAllByDraftIdAndAdminId(String draftId, String adminId);

    Optional<PropertyDraftMedia> findByMediaIdAndAdminId(String mediaId, String adminId);

    Optional<PropertyDraftMedia> findByMediaId(String mediaId);

    boolean existsByCloudinaryPublicIdAndIdNot(String publicId, Long id);

    boolean existsByCloudinaryUrlAndIdNot(String url, Long id);

    boolean existsByStagingObjectKeyAndIdNot(String stagingObjectKey, Long id);

    List<PropertyDraftMedia> findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(String draftId);

    Optional<PropertyDraftMedia> findByMediaIdAndDraftIdAndGuestOwnedTrue(String mediaId, String draftId);

    List<PropertyDraftMedia> findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(String draftId, Long landlordUserId);

    Optional<PropertyDraftMedia> findByMediaIdAndDraftIdAndLandlordUserId(String mediaId, String draftId, Long landlordUserId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE PropertyDraftMedia m SET m.stagingObjectKey = null WHERE m.mediaId = :mediaId " +
           "AND m.landlordUserId = :ownerId AND m.cloudinaryUrl IS NOT NULL")
    int clearPromotedStagingKey(@Param("mediaId") String mediaId, @Param("ownerId") Long ownerId);

    boolean existsByDraftIdAndLandlordUserIdAndUploadStatusAndIsCoverTrueAndContentTypeStartingWith(
            String draftId, Long landlordUserId, String uploadStatus, String contentTypePrefix);

    @Query("SELECT m FROM PropertyDraftMedia m WHERE m.draftId IN :draftIds " +
            "AND m.landlordUserId = :ownerId AND m.uploadStatus = 'UPLOADED' " +
            "AND m.isCover = true AND m.contentType LIKE 'image/%'")
    List<PropertyDraftMedia> findUploadedCoversForDraftIds(@Param("draftIds") List<String> draftIds,
                                                            @Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE PropertyDraftMedia m SET m.isCover = false WHERE m.draftId = :draftId AND m.adminId = :adminId")
    int clearCoverFlagForDraft(@Param("draftId") String draftId, @Param("adminId") String adminId);

    @Modifying
    @Query("UPDATE PropertyDraftMedia m SET m.isCover = false WHERE m.draftId = :draftId AND m.adminId = :adminId AND m.cardId = :cardId")
    int clearCoverFlagForCard(@Param("draftId") String draftId, @Param("adminId") String adminId, @Param("cardId") String cardId);

    @Modifying
    @Query("UPDATE PropertyDraftMedia m SET m.cardId = :cardId WHERE m.draftId = :draftId AND m.adminId = :adminId AND (m.cardId IS NULL OR m.cardId = '')")
    int reassignUnassignedMediaToCard(@Param("draftId") String draftId, @Param("adminId") String adminId, @Param("cardId") String cardId);

    long deleteAllByDraftIdAndAdminId(String draftId, String adminId);
}
