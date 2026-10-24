package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadShortlist;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LeadShortlistRepository extends JpaRepository<LeadShortlist, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LeadShortlist s where s.id = :id")
    Optional<LeadShortlist> findLockedById(@Param("id") Long id);
}
