package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.ParserFieldReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.indore.pathome.spaces.entity.ParserReviewStatus;

@Repository
public interface ParserFieldReviewRepository extends JpaRepository<ParserFieldReview, Long> {

    List<ParserFieldReview> findByExample_IdOrderByFieldNameAsc(String exampleId);

    List<ParserFieldReview> findByExample_IdAndTrainingEligibleTrueOrderBySourceStartAsc(String exampleId);

    @Query("SELECT f FROM ParserFieldReview f WHERE f.example.id IN :exampleIds " +
            "ORDER BY f.example.id ASC, f.fieldName ASC")
    List<ParserFieldReview> findForExamples(@Param("exampleIds") Collection<String> exampleIds);

    void deleteByExample_Id(String exampleId);

    @Modifying
    @Query("DELETE FROM ParserFieldReview f WHERE f.example.id IN " +
            "(SELECT e.id FROM ParserTrainingExample e WHERE e.reviewStatus = :status AND e.createdAt < :cutoff)")
    int deleteExpiredForExampleStatus(
            @Param("status") ParserReviewStatus status,
            @Param("cutoff") LocalDateTime cutoff);
}
