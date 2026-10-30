package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneIssueCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.LeadSourceEventReference;
import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneEntryMethod;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.LeadPhoneVerificationRejectedException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadPhoneVerificationServiceTest {
    private final LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
    private final LeadSourceEventRepository sourceEvents = mock(LeadSourceEventRepository.class);
    private final LeadPhoneChallengeRepository challenges = mock(LeadPhoneChallengeRepository.class);
    private final LeadPhoneDeliveryOutboxRepository outbox =
            mock(LeadPhoneDeliveryOutboxRepository.class);
    private final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    private final LeadAssistanceCommandReceiptRepository receipts =
            mock(LeadAssistanceCommandReceiptRepository.class);
    private final LeadAccessService leadAccess = mock(LeadAccessService.class);
    private final StaffAccessService staffAccess = mock(StaffAccessService.class);
    private final OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
    private final TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final LeadDataProtection protection = mock(LeadDataProtection.class);
    private final LeadAssistanceSecretService secrets = mock(LeadAssistanceSecretService.class);
    private final LeadPhoneDeliveryProvider provider = mock(LeadPhoneDeliveryProvider.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final LeadPhoneVerificationService service = new LeadPhoneVerificationService(
            leads, sourceEvents, challenges, outbox, activities, receipts, leadAccess,
            staffAccess, guards, bounds, clock, protection, secrets, provider, audit, jdbc,
            new ObjectMapper().findAndRegisterModules(), "https://app.pathome.test");

    @Test
    void missingNoticeAgreementCannotSendAProductionCode() {
        PhoneIssueCommand command = new PhoneIssueCommand(5L,
                new LeadSourceEventReference(UUID.randomUUID()), 0L, false);
        assertThrows(IllegalArgumentException.class,
                () -> service.issue(5L, 1L, UUID.randomUUID().toString(), command));
    }

    @Test
    void wrongOperatorCodePersistsAttemptBeforeTypedRejection() throws Exception {
        Fixture fixture = fixture();
        when(secrets.codeMatches(anyString(), anyString(), anyString())).thenReturn(false);

        LeadPhoneVerificationRejectedException rejected = assertThrows(
                LeadPhoneVerificationRejectedException.class,
                () -> service.confirmFromOperator(5L, 1L, fixture.challenge().getId(),
                        UUID.randomUUID().toString(),
                        new PhoneConfirmCommand(5L, 0L, "000000")));

        assertEquals(400, rejected.getStatus().value());
        assertEquals(1, fixture.challenge().getFailedAttempts());
        assertTrue(rejected.getMessage().contains("4 attempts remain"));
        Transactional transactional = LeadPhoneVerificationService.class.getMethod(
                        "confirmFromOperator", Long.class, Long.class, UUID.class,
                        String.class, PhoneConfirmCommand.class)
                .getAnnotation(Transactional.class);
        assertTrue(List.of(transactional.noRollbackFor())
                .contains(LeadPhoneVerificationRejectedException.class));
    }

    @Test
    void oeEnteredCorrectCodeStoresWeakerEntryMethodAndVerifiesOnlyOnce() throws Exception {
        Fixture fixture = fixture();
        when(secrets.codeMatches(anyString(), anyString(), anyString())).thenReturn(true);

        var result = service.confirmFromOperator(5L, 1L, fixture.challenge().getId(),
                UUID.randomUUID().toString(),
                new PhoneConfirmCommand(5L, 0L, "000123"));

        assertEquals(LeadPhoneChallengeState.VERIFIED, result.state());
        assertEquals("OE_ENTERED", result.entryMethod());
        assertTrue(result.currentProofUsable());
        assertEquals(0L, result.boundContactPhoneRevision());
        assertEquals(LeadPhoneEntryMethod.OE_ENTERED, fixture.challenge().getEntryMethod());
        assertTrue(fixture.outbox().isPayloadPurged());

        when(fixture.challenge().getLead().getContactPhoneRevision()).thenReturn(1L);
        var historical = service.get(5L, 1L, fixture.challenge().getId());
        assertEquals(false, historical.currentProofUsable());
        assertEquals(1L, historical.contactPhoneRevision());
        assertEquals(0L, historical.boundContactPhoneRevision());
        assertTrue(historical.message().contains("no longer current"));
    }

    @Test
    void changedOwnerCancelsPendingConfirmationBeforeCodeIsChecked() {
        Fixture fixture = fixture();
        User newOwner = new User();
        newOwner.setId(6L);
        when(fixture.challenge().getLead().getOwner()).thenReturn(newOwner);

        LeadPhoneVerificationRejectedException rejected = assertThrows(
                LeadPhoneVerificationRejectedException.class,
                () -> service.confirmFromOperator(5L, 1L, fixture.challenge().getId(),
                        UUID.randomUUID().toString(),
                        new PhoneConfirmCommand(5L, 0L, "000123")));

        assertEquals(409, rejected.getStatus().value());
        assertEquals(LeadPhoneChallengeState.CANCELLED, fixture.challenge().getState());
        assertTrue(fixture.outbox().isPayloadPurged());
        verify(secrets, never()).codeMatches(anyString(), anyString(), anyString());
    }

    @Test
    void foreignOrMalformedStoredChallengeIsConcealedBeforePayloadConflict() {
        Fixture fixture = fixture();
        UUID operationId = UUID.randomUUID();
        LeadAssistanceCommandReceipt receipt = new LeadAssistanceCommandReceipt(
                fixture.challenge().getCreatedBy(), operationId, "LEAD_PHONE_ISSUE",
                "different-payload", fixture.challenge().getId().toString(),
                Instant.parse("2026-10-27T10:00:00Z"));
        when(receipts.findByActor_IdAndOperationIdAndAction(
                5L, operationId, "LEAD_PHONE_ISSUE"))
                .thenReturn(Optional.of(receipt));
        PhoneIssueCommand command = new PhoneIssueCommand(5L,
                new LeadSourceEventReference(fixture.challenge().getSourceEvent().getId()),
                0L, true);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.issue(5L, 2L, operationId.toString(), command));

        LeadAssistanceCommandReceipt malformed = new LeadAssistanceCommandReceipt(
                fixture.challenge().getCreatedBy(), operationId, "LEAD_PHONE_ISSUE",
                "different-payload", " +1 ", Instant.parse("2026-10-27T10:00:00Z"));
        when(receipts.findByActor_IdAndOperationIdAndAction(
                5L, operationId, "LEAD_PHONE_ISSUE")).thenReturn(Optional.of(malformed));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.issue(5L, 1L, operationId.toString(), command));
    }

    @Test
    void customerConfirmationExpiredDuringSecurityGuardWaitCannotVerify() {
        Fixture fixture = fixture();
        AtomicBoolean guardsAcquired = new AtomicBoolean();
        Instant beforeGuard = Instant.parse("2026-10-27T10:00:00Z");
        when(clock.now()).thenAnswer(ignored ->
                guardsAcquired.get() ? beforeGuard.plusSeconds(360) : beforeGuard);
        doAnswer(ignored -> {
            guardsAcquired.set(true);
            return null;
        }).when(guards).acquire(any(), any(), any());
        when(secrets.phoneConfirmationTokenHash("token")).thenReturn("token");
        when(challenges.findByConfirmationTokenHash("token"))
                .thenReturn(Optional.of(fixture.challenge()));
        when(secrets.codeMatches(anyString(), anyString(), anyString())).thenReturn(true);

        LeadPhoneVerificationRejectedException rejected = assertThrows(
                LeadPhoneVerificationRejectedException.class,
                () -> service.confirmCustomer(new PhoneConfirmationConfirmCommand(
                        "token", "000123")));

        assertEquals(410, rejected.getStatus().value());
        assertEquals(LeadPhoneChallengeState.EXPIRED, fixture.challenge().getState());
    }

    @Test
    void validCustomerConfirmationUsesPostGuardTime() {
        Fixture fixture = fixture();
        AtomicBoolean guardsAcquired = new AtomicBoolean();
        Instant beforeGuard = Instant.parse("2026-10-27T10:00:00Z");
        Instant afterGuard = beforeGuard.plusSeconds(30);
        when(clock.now()).thenAnswer(ignored ->
                guardsAcquired.get() ? afterGuard : beforeGuard);
        doAnswer(ignored -> {
            guardsAcquired.set(true);
            return null;
        }).when(guards).acquire(any(), any(), any());
        when(secrets.phoneConfirmationTokenHash("token")).thenReturn("token");
        when(challenges.findByConfirmationTokenHash("token"))
                .thenReturn(Optional.of(fixture.challenge()));
        when(secrets.codeMatches(anyString(), anyString(), anyString())).thenReturn(true);

        service.confirmCustomer(new PhoneConfirmationConfirmCommand("token", "000123"));

        assertEquals(LeadPhoneChallengeState.VERIFIED, fixture.challenge().getState());
        assertEquals(afterGuard, fixture.challenge().getVerifiedAt());
    }

    @Test
    void foreignClosedLeadIsConcealedBeforeLifecycleAndVersionChecks() {
        Fixture fixture = fixture();
        LeadRoutingQueue lead = fixture.challenge().getLead();
        when(lead.getStatus()).thenReturn(LeadStatus.CLOSED);
        doThrow(new jakarta.persistence.EntityNotFoundException("Lead not found"))
                .when(leadAccess).requireContactOwner(any(), eq(lead), eq(99L));
        PhoneIssueCommand command = new PhoneIssueCommand(4L,
                new LeadSourceEventReference(fixture.challenge().getSourceEvent().getId()),
                0L, true);

        var foreign = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.issue(99L, 1L, UUID.randomUUID().toString(), command));
        when(leads.findLockedById(2L)).thenReturn(Optional.empty());
        var absent = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.issue(99L, 2L, UUID.randomUUID().toString(), command));

        verify(leadAccess).requireContactOwner(any(), eq(lead), eq(99L));
        assertEquals(absent.getMessage(), foreign.getMessage());
    }

    @Test
    void currentOwnerWithStaleLeadVersionReceivesVisibleConflict() {
        Fixture fixture = fixture();
        PhoneIssueCommand command = new PhoneIssueCommand(4L,
                new LeadSourceEventReference(fixture.challenge().getSourceEvent().getId()),
                0L, true);

        assertThrows(VisitOperationsConflictException.class,
                () -> service.issue(5L, 1L, UUID.randomUUID().toString(), command));
    }

    private Fixture fixture() {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        User owner = new User();
        owner.setId(5L);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(1L);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(2L);
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        when(lead.getId()).thenReturn(1L);
        when(lead.getVersion()).thenReturn(5L);
        when(lead.getStatus()).thenReturn(LeadStatus.QUALIFYING);
        when(lead.getOwner()).thenReturn(owner);
        when(lead.getSupportedCity()).thenReturn(city);
        when(lead.getOperatingTeam()).thenReturn(team);
        when(lead.getContactPhoneNormalized()).thenReturn("+919876543210");
        when(lead.getContactPhoneRevision()).thenReturn(0L);
        when(leads.findById(1L)).thenReturn(Optional.of(lead));
        when(leads.findLockedById(1L)).thenReturn(Optional.of(lead));

        LeadSourceEvent source = mock(LeadSourceEvent.class);
        UUID sourceId = UUID.randomUUID();
        when(source.getId()).thenReturn(sourceId);
        when(source.getLead()).thenReturn(lead);
        when(source.getProcessingState()).thenReturn(LeadSourceEventState.PROCESSED);
        when(sourceEvents.findById(sourceId)).thenReturn(Optional.of(source));
        when(sourceEvents.findLockedById(sourceId)).thenReturn(Optional.of(source));

        when(protection.keyedDigest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(protection.keyedDigest(eq("LEAD_PHONE_DESTINATION"), anyString()))
                .thenReturn("fingerprint");
        when(staffAccess.requireAttributableUser(5L)).thenReturn(owner);
        when(guards.cityIsActive(1L)).thenReturn(true);
        when(guards.teamIsActive(2L)).thenReturn(true);
        when(guards.teamCityId(2L)).thenReturn(1L);
        when(staffAccess.hasCapabilityAt(eq(5L),
                eq(com.indore.pathome.spaces.entity.StaffCapability.OPS_COORDINATE),
                eq(com.indore.pathome.spaces.entity.StaffScopeType.TEAM), eq(2L)))
                .thenReturn(true);
        when(clock.now()).thenReturn(now);
        when(leadAccess.context(5L)).thenReturn(new LeadAccessContext(false,
                List.of(), List.of(), List.of(2L), List.of()));

        UUID challengeId = UUID.randomUUID();
        LeadPhoneChallenge challenge = new LeadPhoneChallenge(challengeId, lead, source,
                LeadPhoneVerificationService.PURPOSE, "fingerprint", 0L, "**********3210",
                1, "context", "digest", "token",
                LeadPhoneDeliveryState.ACCEPTED, "SIMULATED", owner,
                now.minusSeconds(10), now.plusSeconds(290));
        when(challenges.findById(challengeId)).thenReturn(Optional.of(challenge));
        when(challenges.findLockedById(challengeId)).thenReturn(Optional.of(challenge));
        LeadPhoneDeliveryOutbox outboxRow = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                challenge, "fingerprint", "ciphertext", "iv", "key", "SIMULATED",
                now.minusSeconds(10));
        when(outbox.findByChallenge_Id(challengeId)).thenReturn(Optional.of(outboxRow));
        when(receipts.findByActor_IdAndOperationIdAndAction(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        return new Fixture(challenge, outboxRow);
    }

    private record Fixture(LeadPhoneChallenge challenge, LeadPhoneDeliveryOutbox outbox) {}
}
