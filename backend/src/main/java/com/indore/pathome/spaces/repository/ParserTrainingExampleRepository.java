package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.ParserDatasetPartition;
import com.indore.pathome.spaces.entity.ParserReviewStatus;
import com.indore.pathome.spaces.entity.ParserTrainingExample;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface ParserTrainingExampleRepository extends JpaRepository<ParserTrainingExample, String> {

    Page<ParserTrainingExample> findByReviewStatusOrderByCreatedAtAsc(
            ParserReviewStatus reviewStatus, Pageable pageable);

    Page<ParserTrainingExample> findByReviewStatusAndDatasetPartitionOrderByCreatedAtAsc(
            ParserReviewStatus reviewStatus, ParserDatasetPartition datasetPartition, Pageable pageable);

    List<ParserTrainingExample> findTop20ByPromptHashAndReviewStatusOrderByCreatedAtDesc(
            String promptHash, ParserReviewStatus reviewStatus);

    long countByReviewStatus(ParserReviewStatus reviewStatus);

    long countByReviewStatusAndDatasetPartition(
            ParserReviewStatus reviewStatus, ParserDatasetPartition datasetPartition);

    @Modifying
    @Query("DELETE FROM ParserTrainingExample e WHERE e.reviewStatus = :status AND e.createdAt < :cutoff")
    int deleteExpiredByStatus(
            @Param("status") ParserReviewStatus status,
            @Param("cutoff") LocalDateTime cutoff);
}
