package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "parser_field_reviews", uniqueConstraints = {
        @UniqueConstraint(name = "uk_parser_field_example_name", columnNames = {"example_id", "field_name"})
}, indexes = {
        @Index(name = "idx_parser_field_example_eligible", columnList = "example_id, training_eligible"),
        @Index(name = "idx_parser_field_name_eligible", columnList = "field_name, training_eligible")
})
public class ParserFieldReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "example_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_parser_field_review_example"))
    private ParserTrainingExample example;

    @Column(name = "field_name", length = 64, nullable = false)
    private String fieldName;

    @Column(name = "predicted_value", columnDefinition = "TEXT")
    private String predictedValue;

    @Column(name = "reviewed_value", columnDefinition = "TEXT", nullable = false)
    private String reviewedValue;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "source_start")
    private Integer sourceStart;

    @Column(name = "source_end")
    private Integer sourceEnd;

    @Column(name = "was_corrected", nullable = false)
    private boolean corrected;

    @Column(name = "evidence_supported", nullable = false)
    private boolean evidenceSupported;

    @Column(name = "value_valid", nullable = false)
    private boolean valueValid;

    @Column(name = "training_eligible", nullable = false)
    private boolean trainingEligible;

    @Column(name = "exclusion_reason", length = 500)
    private String exclusionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ParserTrainingExample getExample() { return example; }
    public void setExample(ParserTrainingExample example) { this.example = example; }
    public String getExampleId() { return example != null ? example.getId() : null; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getPredictedValue() { return predictedValue; }
    public void setPredictedValue(String predictedValue) { this.predictedValue = predictedValue; }
    public String getReviewedValue() { return reviewedValue; }
    public void setReviewedValue(String reviewedValue) { this.reviewedValue = reviewedValue; }
    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public Integer getSourceStart() { return sourceStart; }
    public void setSourceStart(Integer sourceStart) { this.sourceStart = sourceStart; }
    public Integer getSourceEnd() { return sourceEnd; }
    public void setSourceEnd(Integer sourceEnd) { this.sourceEnd = sourceEnd; }
    public boolean isCorrected() { return corrected; }
    public void setCorrected(boolean corrected) { this.corrected = corrected; }
    public boolean isEvidenceSupported() { return evidenceSupported; }
    public void setEvidenceSupported(boolean evidenceSupported) { this.evidenceSupported = evidenceSupported; }
    public boolean isValueValid() { return valueValid; }
    public void setValueValid(boolean valueValid) { this.valueValid = valueValid; }
    public boolean isTrainingEligible() { return trainingEligible; }
    public void setTrainingEligible(boolean trainingEligible) { this.trainingEligible = trainingEligible; }
    public String getExclusionReason() { return exclusionReason; }
    public void setExclusionReason(String exclusionReason) { this.exclusionReason = exclusionReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
