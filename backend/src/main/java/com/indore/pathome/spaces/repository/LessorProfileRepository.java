package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LessorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface LessorProfileRepository extends JpaRepository<LessorProfile, Long> {
    Optional<LessorProfile> findByLinkedUserId(Long linkedUserId);
    boolean existsByLinkedUserId(Long linkedUserId);

    @Modifying
    @Query(value = """
            INSERT INTO lessor_profiles (
                linked_user_id,
                display_name,
                mobile_number,
                email,
                source_type,
                created_at,
                updated_at
            ) VALUES (
                :linkedUserId,
                :displayName,
                :mobileNumber,
                :email,
                :sourceType,
                :createdAt,
                :updatedAt
            )
            ON CONFLICT (linked_user_id) WHERE linked_user_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIfNotExists(
            @Param("linkedUserId") Long linkedUserId,
            @Param("displayName") String displayName,
            @Param("mobileNumber") String mobileNumber,
            @Param("email") String email,
            @Param("sourceType") String sourceType,
            @Param("createdAt") LocalDateTime createdAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
