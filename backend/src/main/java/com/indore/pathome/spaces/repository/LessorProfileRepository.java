package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LessorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LessorProfileRepository extends JpaRepository<LessorProfile, Long> {
    Optional<LessorProfile> findByLinkedUserId(Long linkedUserId);
    boolean existsByLinkedUserId(Long linkedUserId);
}
