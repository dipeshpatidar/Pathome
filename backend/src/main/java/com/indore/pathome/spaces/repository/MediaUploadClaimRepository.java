package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.MediaUploadClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface MediaUploadClaimRepository extends JpaRepository<MediaUploadClaim, Long> {

    Optional<MediaUploadClaim> findByListingIdAndUploadRequestId(Long listingId, String uploadRequestId);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO media_upload_claims (listing_id, upload_request_id, owner_token, status, expires_at, created_at, updated_at) " +
                   "VALUES (:listingId, :uploadRequestId, :ownerToken, 'IN_PROGRESS', CURRENT_TIMESTAMP + INTERVAL '30 second', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                   "ON CONFLICT (listing_id, upload_request_id) DO NOTHING", nativeQuery = true)
    int insertClaimIfAbsent(
            @Param("listingId") Long listingId,
            @Param("uploadRequestId") String uploadRequestId,
            @Param("ownerToken") String ownerToken);

    @Modifying
    @Transactional
    @Query(value = "UPDATE media_upload_claims " +
                   "SET owner_token = :newToken, " +
                   "    status = 'IN_PROGRESS', " +
                   "    expires_at = CURRENT_TIMESTAMP + INTERVAL '30 second', " +
                   "    error_message = NULL, " +
                   "    updated_at = CURRENT_TIMESTAMP " +
                   "WHERE listing_id = :listingId " +
                   "  AND upload_request_id = :uploadRequestId " +
                   "  AND (status = 'FAILED' OR (status = 'IN_PROGRESS' AND expires_at < CURRENT_TIMESTAMP))", nativeQuery = true)
    int atomicTakeover(
            @Param("listingId") Long listingId,
            @Param("uploadRequestId") String uploadRequestId,
            @Param("newToken") String newToken);

    @Modifying
    @Transactional
    @Query(value = "UPDATE media_upload_claims " +
                   "SET expires_at = CURRENT_TIMESTAMP + INTERVAL '30 second', " +
                   "    updated_at = CURRENT_TIMESTAMP " +
                   "WHERE listing_id = :listingId " +
                   "  AND upload_request_id = :uploadRequestId " +
                   "  AND owner_token = :ownerToken " +
                   "  AND status = 'IN_PROGRESS'", nativeQuery = true)
    int renewLease(
            @Param("listingId") Long listingId,
            @Param("uploadRequestId") String uploadRequestId,
            @Param("ownerToken") String ownerToken);

    @Modifying
    @Transactional
    @Query(value = "UPDATE media_upload_claims " +
                   "SET status = 'COMPLETED', " +
                   "    updated_at = CURRENT_TIMESTAMP " +
                   "WHERE listing_id = :listingId " +
                   "  AND upload_request_id = :uploadRequestId " +
                   "  AND owner_token = :ownerToken " +
                   "  AND status = 'IN_PROGRESS'", nativeQuery = true)
    int markCompleted(
            @Param("listingId") Long listingId,
            @Param("uploadRequestId") String uploadRequestId,
            @Param("ownerToken") String ownerToken);

    @Modifying
    @Transactional
    @Query(value = "UPDATE media_upload_claims " +
                   "SET status = 'FAILED', " +
                   "    error_message = :errorMessage, " +
                   "    updated_at = CURRENT_TIMESTAMP " +
                   "WHERE listing_id = :listingId " +
                   "  AND upload_request_id = :uploadRequestId " +
                   "  AND owner_token = :ownerToken " +
                   "  AND status = 'IN_PROGRESS'", nativeQuery = true)
    int markFailed(
            @Param("listingId") Long listingId,
            @Param("uploadRequestId") String uploadRequestId,
            @Param("ownerToken") String ownerToken,
            @Param("errorMessage") String errorMessage);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM media_upload_claims " +
                   "WHERE (status IN ('COMPLETED', 'FAILED') AND updated_at < CURRENT_TIMESTAMP - INTERVAL '7 day') " +
                   "   OR (status = 'IN_PROGRESS' AND expires_at < CURRENT_TIMESTAMP - INTERVAL '7 day')", nativeQuery = true)
    int cleanupOldClaims();
}
