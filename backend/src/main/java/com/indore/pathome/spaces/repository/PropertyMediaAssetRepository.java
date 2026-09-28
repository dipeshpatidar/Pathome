package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyMediaAssetRepository extends JpaRepository<PropertyMediaAsset, Long> {
    @org.springframework.data.jpa.repository.Query("select m from PropertyMediaAsset m where m.listingId = :listingId " +
            "order by coalesce(m.sortOrder, 2147483647) asc, m.uploadedAt desc, m.id desc")
    List<PropertyMediaAsset> findByListingIdOrderByUploadedAtDesc(@org.springframework.data.repository.query.Param("listingId") Long listingId);
    List<PropertyMediaAsset> findByListingIdInOrderByUploadedAtDesc(java.util.Collection<Long> listingIds);
    List<PropertyMediaAsset> findByListingIdAndRoomTag(Long listingId, RoomTag roomTag);
    Optional<PropertyMediaAsset> findByListingIdAndUploadRequestId(Long listingId, String uploadRequestId);
}
