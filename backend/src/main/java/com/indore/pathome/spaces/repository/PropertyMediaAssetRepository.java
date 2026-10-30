package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyMediaAssetRepository extends JpaRepository<PropertyMediaAsset, Long> {
    boolean existsByCloudinaryPublicId(String publicId);

    boolean existsByMediaUrl(String url);
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m where m.listingId = :listingId and m.retiredAt is null " +
            "order by coalesce(m.sortOrder, 2147483647) asc, m.uploadedAt desc, m.id desc")
    List<PropertyMediaAsset> findByListingIdOrderByUploadedAtDesc(@org.springframework.data.repository.query.Param("listingId") Long listingId);
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m " +
            "where m.listingId in :listingIds and m.retiredAt is null " +
            "order by m.uploadedAt desc, m.id desc")
    List<PropertyMediaAsset> findByListingIdInOrderByUploadedAtDesc(
            @org.springframework.data.repository.query.Param("listingIds") java.util.Collection<Long> listingIds);
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m " +
            "where m.listingId = :listingId and m.roomTag = :roomTag and m.retiredAt is null")
    List<PropertyMediaAsset> findByListingIdAndRoomTag(
            @org.springframework.data.repository.query.Param("listingId") Long listingId,
            @org.springframework.data.repository.query.Param("roomTag") RoomTag roomTag);
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m " +
            "where m.listingId = :listingId and m.uploadRequestId = :uploadRequestId and m.retiredAt is null")
    Optional<PropertyMediaAsset> findByListingIdAndUploadRequestId(
            @org.springframework.data.repository.query.Param("listingId") Long listingId,
            @org.springframework.data.repository.query.Param("uploadRequestId") String uploadRequestId);
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m " +
            "where m.listingId = :listingId order by m.uploadedAt desc, m.id desc")
    List<PropertyMediaAsset> findHistoricalByListingId(
            @org.springframework.data.repository.query.Param("listingId") Long listingId);

    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m " +
            "where m.listingId = :listingId and m.retiredAt is null " +
            "and m.mediaType = com.indore.pathome.spaces.entity.MediaType.IMAGE " +
            "order by m.isPrimaryCover desc, " +
            "coalesce(m.sortOrder, 2147483647) asc, m.uploadedAt desc, m.id desc")
    List<PropertyMediaAsset> findPublicCoverCandidates(
            @org.springframework.data.repository.query.Param("listingId") Long listingId,
            Pageable pageable);
}
