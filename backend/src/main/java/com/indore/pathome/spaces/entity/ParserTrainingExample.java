package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "parser_training_examples", indexes = {
        @Index(name = "idx_parser_example_status_created", columnList = "review_status, created_at"),
        @Index(name = "idx_parser_example_partition_status", columnList = "dataset_partition, review_status"),
        @Index(name = "idx_parser_example_batch_property", columnList = "batch_id, property_index"),
        @Index(name = "idx_parser_example_hash_status", columnList = "prompt_hash, review_status")
})
public class ParserTrainingExample {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "batch_id", length = 36, nullable = false, updatable = false)
    private String batchId;

    @Column(name = "property_index", nullable = false)
    private int propertyIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_source", length = 16, nullable = false)
    private ParserInputSource inputSource = ParserInputSource.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 40, nullable = false)
    private ParserReviewStatus reviewStatus = ParserReviewStatus.QUARANTINED;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_partition", length = 16, nullable = false)
    private ParserDatasetPartition datasetPartition = ParserDatasetPartition.EXCLUDED;

    @Column(name = "raw_prompt", columnDefinition = "TEXT", nullable = false)
    private String rawPrompt;

    @Column(name = "prompt_hash", length = 64, nullable = false)
    private String promptHash;

    @Column(name = "initial_prediction", columnDefinition = "TEXT", nullable = false)
    private String initialPredictionJson;

    @Column(name = "final_reviewed_values", columnDefinition = "TEXT")
    private String finalReviewedJson;

    @Column(name = "parser_version", length = 80, nullable = false)
    private String parserVersion;

    @Column(name = "published_listing_id")
    private Long publishedListingId;

    @Column(name = "reviewed_by", length = 254)
    private String reviewedBy;

    @Column(name = "created_by", length = 254, nullable = false)
    private String createdBy;

    @Column(name = "exclusion_reason", length = 500)
    private String exclusionReason;

    @Column(name = "label_count", nullable = false)
    private int labelCount;

    @Column(name = "eligible_label_count", nullable = false)
    private int eligibleLabelCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "curated_at")
    private LocalDateTime curatedAt;

    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public int getPropertyIndex() { return propertyIndex; }
    public void setPropertyIndex(int propertyIndex) { this.propertyIndex = propertyIndex; }
    public ParserInputSource getInputSource() { return inputSource; }
    public void setInputSource(ParserInputSource inputSource) { this.inputSource = inputSource; }
    public ParserReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(ParserReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public ParserDatasetPartition getDatasetPartition() { return datasetPartition; }
    public void setDatasetPartition(ParserDatasetPartition datasetPartition) { this.datasetPartition = datasetPartition; }
    public String getRawPrompt() { return rawPrompt; }
    public void setRawPrompt(String rawPrompt) { this.rawPrompt = rawPrompt; }
    public String getPromptHash() { return promptHash; }
    public void setPromptHash(String promptHash) { this.promptHash = promptHash; }
    public String getInitialPredictionJson() { return initialPredictionJson; }
    public void setInitialPredictionJson(String initialPredictionJson) { this.initialPredictionJson = initialPredictionJson; }
    public String getFinalReviewedJson() { return finalReviewedJson; }
    public void setFinalReviewedJson(String finalReviewedJson) { this.finalReviewedJson = finalReviewedJson; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public Long getPublishedListingId() { return publishedListingId; }
    public void setPublishedListingId(Long publishedListingId) { this.publishedListingId = publishedListingId; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getExclusionReason() { return exclusionReason; }
    public void setExclusionReason(String exclusionReason) { this.exclusionReason = exclusionReason; }
    public int getLabelCount() { return labelCount; }
    public void setLabelCount(int labelCount) { this.labelCount = labelCount; }
    public int getEligibleLabelCount() { return eligibleLabelCount; }
    public void setEligibleLabelCount(int eligibleLabelCount) { this.eligibleLabelCount = eligibleLabelCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCuratedAt() { return curatedAt; }
    public void setCuratedAt(LocalDateTime curatedAt) { this.curatedAt = curatedAt; }
}
