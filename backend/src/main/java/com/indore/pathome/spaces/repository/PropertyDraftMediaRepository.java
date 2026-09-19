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

    List<PropertyDraftMedia> findAllByDraftIdAndAdminId(String draftId, String adminId);

    Optional<PropertyDraftMedia> findByMediaIdAndAdminId(String mediaId, String adminId);

    Optional<PropertyDraftMedia> findByMediaId(String mediaId);

    @Modifying
    @Query("UPDATE PropertyDraftMedia m SET m.isCover = false WHERE m.draftId = :draftId AND m.adminId = :adminId")
    int clearCoverFlagForDraft(@Param("draftId") String draftId, @Param("adminId") String adminId);

    @Modifying
    @Query("UPDATE PropertyDraftMedia m SET m.isCover = false WHERE m.draftId = :draftId AND m.adminId = :adminId AND m.cardId = :cardId")
    int clearCoverFlagForCard(@Param("draftId") String draftId, @Param("adminId") String adminId, @Param("cardId") String cardId);

    long deleteAllByDraftIdAndAdminId(String draftId, String adminId);
}
