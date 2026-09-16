package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "parser_model_versions", indexes = {
        @Index(name = "idx_parser_model_status_created", columnList = "model_status, created_at")
})
public class ParserModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_version", length = 100, nullable = false, unique = true)
    private String modelVersion;

    @Column(name = "artifact_path", length = 500, nullable = false)
    private String artifactPath;

    @Column(name = "artifact_checksum", length = 64, nullable = false)
    private String artifactChecksum;

    @Column(name = "dataset_fingerprint", length = 64, nullable = false)
    private String datasetFingerprint;

    @Column(name = "metrics_json", columnDefinition = "TEXT", nullable = false)
    private String metricsJson;

    @Column(name = "holdout_example_count", nullable = false)
    private int holdoutExampleCount;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_status", length = 16, nullable = false)
    private ParserModelStatus modelStatus = ParserModelStatus.CANDIDATE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public String getArtifactChecksum() { return artifactChecksum; }
    public void setArtifactChecksum(String artifactChecksum) { this.artifactChecksum = artifactChecksum; }
    public String getDatasetFingerprint() { return datasetFingerprint; }
    public void setDatasetFingerprint(String datasetFingerprint) { this.datasetFingerprint = datasetFingerprint; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public int getHoldoutExampleCount() { return holdoutExampleCount; }
    public void setHoldoutExampleCount(int holdoutExampleCount) { this.holdoutExampleCount = holdoutExampleCount; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public ParserModelStatus getModelStatus() { return modelStatus; }
    public void setModelStatus(ParserModelStatus modelStatus) { this.modelStatus = modelStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(LocalDateTime activatedAt) { this.activatedAt = activatedAt; }
}
