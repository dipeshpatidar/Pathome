package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "visit_scheduling_decisions", indexes = {
        @Index(name = "idx_visit_scheduling_decision_session_created", columnList = "session_id, created_at, id")
})
public class VisitSchedulingDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private VisitSession session;

    @Column(name = "session_version_before", nullable = false)
    private Long sessionVersionBefore;

    @Column(name = "recommendation_generated_at", nullable = false)
    private Instant recommendationGeneratedAt;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_ground_executive_user_id")
    private User recommendedGroundExecutive;

    @Column(name = "recommended_scheduled_at")
    private Instant recommendedScheduledAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "selected_ground_executive_user_id", nullable = false)
    private User selectedGroundExecutive;

    @Column(name = "selected_scheduled_at", nullable = false)
    private Instant selectedScheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approved_by_user_id", nullable = false)
    private User approvedBy;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "was_override", nullable = false)
    private boolean override;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "location_assessment", nullable = false, length = 24)
    private String locationAssessment;

    @Column(name = "travel_confidence", nullable = false, length = 24)
    private String travelConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public VisitSession getSession() { return session; }
    public void setSession(VisitSession session) { this.session = session; }
    public Long getSessionVersionBefore() { return sessionVersionBefore; }
    public void setSessionVersionBefore(Long sessionVersionBefore) { this.sessionVersionBefore = sessionVersionBefore; }
    public Instant getRecommendationGeneratedAt() { return recommendationGeneratedAt; }
    public void setRecommendationGeneratedAt(Instant recommendationGeneratedAt) { this.recommendationGeneratedAt = recommendationGeneratedAt; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public User getRecommendedGroundExecutive() { return recommendedGroundExecutive; }
    public void setRecommendedGroundExecutive(User recommendedGroundExecutive) { this.recommendedGroundExecutive = recommendedGroundExecutive; }
    public Instant getRecommendedScheduledAt() { return recommendedScheduledAt; }
    public void setRecommendedScheduledAt(Instant recommendedScheduledAt) { this.recommendedScheduledAt = recommendedScheduledAt; }
    public User getSelectedGroundExecutive() { return selectedGroundExecutive; }
    public void setSelectedGroundExecutive(User selectedGroundExecutive) { this.selectedGroundExecutive = selectedGroundExecutive; }
    public Instant getSelectedScheduledAt() { return selectedScheduledAt; }
    public void setSelectedScheduledAt(Instant selectedScheduledAt) { this.selectedScheduledAt = selectedScheduledAt; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public User getApprovedBy() { return approvedBy; }
    public void setApprovedBy(User approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public boolean isOverride() { return override; }
    public void setOverride(boolean override) { this.override = override; }
    public String getOverrideReason() { return overrideReason; }
    public void setOverrideReason(String overrideReason) { this.overrideReason = overrideReason; }
    public String getLocationAssessment() { return locationAssessment; }
    public void setLocationAssessment(String locationAssessment) { this.locationAssessment = locationAssessment; }
    public String getTravelConfidence() { return travelConfidence; }
    public void setTravelConfidence(String travelConfidence) { this.travelConfidence = travelConfidence; }
    public Instant getCreatedAt() { return createdAt; }
}
