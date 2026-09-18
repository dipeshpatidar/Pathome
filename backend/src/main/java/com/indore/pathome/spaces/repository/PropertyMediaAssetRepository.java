package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyMediaAssetRepository extends JpaRepository<PropertyMediaAsset, Long> {
    List<PropertyMediaAsset> findByListingIdOrderByUploadedAtDesc(Long listingId);
    List<PropertyMediaAsset> findByListingIdAndRoomTag(Long listingId, RoomTag roomTag);
    Optional<PropertyMediaAsset> findByListingIdAndUploadRequestId(Long listingId, String uploadRequestId);
}
