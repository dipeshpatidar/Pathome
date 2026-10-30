package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.entity.LeadLessorAvailabilityOutcome;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.entity.LeadVisitAssociationStatus;
import com.indore.pathome.spaces.entity.LeadVisitCustomerResponseState;
import com.indore.pathome.spaces.entity.LeadVisitProposalState;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Narrow A1-A7 command and response contracts. No OTP or lessor contact is returned by metadata reads. */
public final class LeadAssistanceDtos {
    private LeadAssistanceDtos() {}

    public record PhoneIssueCommand(
            Long expectedLeadVersion,
            LeadSourceEventReference sourceEvent,
            Long expectedContactPhoneRevision,
            boolean noticeAgreed) {}

    public record LeadSourceEventReference(UUID id) {}

    public record PhoneResendCommand(
            Long expectedLeadVersion,
            Long expectedChallengeVersion,
            int expectedGeneration,
            boolean noticeAgreed) {}

    public record PhoneConfirmCommand(
            Long expectedLeadVersion,
            Long expectedChallengeVersion,
            String code) {}

    public record PhoneConfirmationExchangeCommand(String token) {}

    public record PhoneConfirmationConfirmCommand(String token, String code) {}

    public record PhoneVerificationView(
            UUID challengeId,
            Long leadId,
            LeadPhoneChallengeState state,
            LeadPhoneDeliveryState deliveryState,
            String maskedDestination,
            int failedAttempts,
            int maxFailedAttempts,
            int generation,
            Long challengeVersion,
            Long contactPhoneRevision,
            Long boundContactPhoneRevision,
            boolean currentProofUsable,
            Instant proofUsableUntil,
            Instant createdAt,
            Instant expiresAt,
            Instant verifiedAt,
            String entryMethod,
            String message) {}

    public record PhoneConfirmationView(
            LeadPhoneChallengeState state,
            LeadPhoneDeliveryState deliveryState,
            String maskedDestination,
            int attemptsRemaining,
            Instant expiresAt,
            String message) {}

    public record AvailabilityCreateCommand(
            Long shortlistId,
            Long listingId,
            UUID sourceEventId,
            Long expectedLeadVersion,
            Long expectedShortlistVersion,
            Long expectedListingVersion,
            boolean genuineEnquiryAttested) {}

    public record AvailabilityRevealCommand(
            Long expectedLeadVersion,
            Long expectedShortlistVersion,
            Long expectedListingVersion,
            Long expectedEnquiryVersion,
            Long expectedContactPhoneRevision,
            boolean genuineEnquiryAttested) {}

    public record AvailabilityResponseCommand(
            Long expectedLeadVersion,
            Long expectedEnquiryVersion,
            LeadLessorAvailabilityOutcome outcome,
            String note) {}

    public record AvailabilityView(
            UUID enquiryId,
            Long leadId,
            Long shortlistId,
            Long listingId,
            UUID sourceEventId,
            LeadLessorAvailabilityOutcome outcome,
            Instant createdAt,
            Instant expiresAt,
            Instant reportedAt,
            Long version,
            Long leadVersion,
            Long shortlistVersion,
            Long listingVersion,
            Long contactPhoneRevision,
            boolean contactRevealEligible,
            String phoneVerificationState,
            String message) {}

    public record VisitProposalCreateCommand(
            Long expectedLeadVersion,
            Long shortlistId,
            Long listingId,
            Long expectedShortlistVersion,
            Long expectedListingVersion,
            Long contactPreferenceRevision) {}

    public record VisitProposalIssuedView(
            UUID proposalId,
            Long leadId,
            Long listingId,
            LeadVisitProposalState state,
            Instant expiresAt,
            Long version,
            String handoffUrl) {}

    public record VisitProposalView(
            UUID proposalId,
            Long leadId,
            Long listingId,
            LeadVisitProposalState state,
            LeadVisitAssociationStatus associationStatus,
            Instant createdAt,
            Instant expiresAt,
            Long version,
            List<AssociationClaimReviewView> associationClaims) {}

    public record AssociationClaimReviewView(
            UUID claimId,
            Long requestId,
            LeadVisitAssociationStatus state,
            Long version,
            Instant createdAt) {}

    public record VisitHandoffExchangeCommand(String token) {}

    public record ProposalPropertyView(
            Long id,
            String title,
            String location,
            String bhk,
            BigDecimal monthlyRent,
            Long version) {}

    public record VisitHandoffView(
            UUID contextId,
            UUID proposalId,
            Instant expiresAt,
            ProposalPropertyView property) {}

    public record VisitCustomerResponseCommand(
            LeadVisitCustomerResponseState response,
            Long requestId,
            Long expectedResponseVersion,
            boolean associateEnquiry,
            boolean explicitEnquiryOwnershipConsent,
            String consentWordingVersion) {}

    public record VisitCustomerResponseView(
            UUID responseId,
            UUID proposalId,
            LeadVisitCustomerResponseState response,
            Long requestId,
            LeadVisitAssociationStatus associationStatus,
            Instant recordedAt,
            Long version,
            String message) {}

    public record CreateOwnVisitRequestCommand(
            UUID operationId,
            Long listingId,
            Long expectedListingVersion,
            Long expectedRequestVersion,
            boolean explicitVisitIntent,
            boolean acknowledgeExistingRequest,
            UUID handoffContextId,
            boolean associateEnquiry,
            String consentWordingVersion,
            CreatePropertyVisitRequest request) {}

    public record OwnVisitRequestView(
            Long requestId,
            Long propertyId,
            Long requestVersion,
            String status,
            boolean created,
            boolean requiresAcknowledgement,
            LeadVisitAssociationStatus associationStatus,
            String message,
            LocalDateTime receivedAt) {}

    public record AssociationDecisionCommand(
            String decision,
            Long expectedClaimVersion,
            String reasonCode) {}

    public record AssociationClaimView(
            UUID claimId,
            UUID proposalId,
            Long requestId,
            LeadVisitAssociationStatus state,
            Instant createdAt,
            Instant decidedAt,
            Long version,
            String message) {}

    public record AssistanceCommandStatusView(
            String state,
            String action,
            String resultReference,
            Instant completedAt) {}
}
