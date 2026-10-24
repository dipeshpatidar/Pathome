package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidatesCommand;
import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.LeadLegacyProvenance;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadPreferredLocalityRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.exception.LocationLookupRateLimitException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadServiceTest {
    private final LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
    private final LeadPreferredLocalityRepository preferredLocalities =
            mock(LeadPreferredLocalityRepository.class);
    private final LeadSourceEventRepository sourceEvents = mock(LeadSourceEventRepository.class);
    private final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    private final LeadCommandReceiptRepository receipts = mock(LeadCommandReceiptRepository.class);
    private final LeadContactPreferenceRepository preferences =
            mock(LeadContactPreferenceRepository.class);
    private final LeadDataProtection protection = mock(LeadDataProtection.class);
    private final LocalityRepository localities = mock(LocalityRepository.class);
    private final SupportedCityRepository cities = mock(SupportedCityRepository.class);
    private final OperatingTeamRepository teams = mock(OperatingTeamRepository.class);
    private final StaffAccessService staffAccess = mock(StaffAccessService.class);
    private final LeadAccessService leadAccess = mock(LeadAccessService.class);
    private final OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final TransactionalCommandBounds commandBounds = mock(TransactionalCommandBounds.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LeadService service = new LeadService(leads, preferredLocalities, sourceEvents,
            activities, receipts, preferences, protection, localities, cities, teams, staffAccess, leadAccess,
            guards, audit, commandBounds, clock, entityManager, jdbc,
            new ObjectMapper().findAndRegisterModules(), users);

    @Test
    void queueSearchNormalizationIsBoundedAndWhitespaceStable() {
        assertEquals("Green Park", LeadService.normalizeQueueQuery("  Green \n\t Park  "));
        assertEquals(null, LeadService.normalizeQueueQuery("   "));
        assertThrows(IllegalArgumentException.class,
                () -> LeadService.normalizeQueueQuery("x".repeat(81)));
    }

    @Test
    void followUpTodayUsesTimezoneAwareHalfOpenDayAcrossDstBoundaries() {
        LeadService.FollowUpWindow spring = LeadService.followUpWindow(
                LeadFollowUpFilter.TODAY, "America/New_York",
                Instant.parse("2026-03-08T15:00:00Z"));
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), spring.todayStart());
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), spring.todayEnd());

        LeadService.FollowUpWindow autumn = LeadService.followUpWindow(
                LeadFollowUpFilter.TODAY, "America/New_York",
                Instant.parse("2026-11-01T15:00:00Z"));
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), autumn.todayStart());
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), autumn.todayEnd());

        LeadService.FollowUpWindow nonHour = LeadService.followUpWindow(
                LeadFollowUpFilter.TODAY, "America/St_Johns",
                Instant.parse("2026-10-22T04:30:00Z"));
        assertEquals(Instant.parse("2026-10-22T02:30:00Z"), nonHour.todayStart());
        assertEquals(Instant.parse("2026-10-23T02:30:00Z"), nonHour.todayEnd());

        assertThrows(IllegalArgumentException.class,
                () -> LeadService.followUpWindow(
                        LeadFollowUpFilter.TODAY, null, Instant.parse("2026-10-22T04:30:00Z")));
        assertThrows(IllegalArgumentException.class,
                () -> LeadService.followUpWindow(
                        LeadFollowUpFilter.TODAY, "Not/AZone", Instant.parse("2026-10-22T04:30:00Z")));
    }

    @Test
    void legacyCaptureFingerprintDoesNotChangeWhenResolutionFieldsAreAdded() throws Exception {
        LeadCaptureCommand command = new LeadCaptureCommand(5L, 9L, "Synthetic", "+919900001234",
                "Two bedrooms", LeadIntent.RENT, "Flat", "2 BHK", new BigDecimal("12000"),
                new BigDecimal("18000"), "INR", LocalDate.parse("2026-12-01"), "Semi-furnished",
                "Working professional", true, false, true, "Second floor", List.of(11, 12),
                Instant.parse("2026-10-23T04:30:00Z"), "Asia/Kolkata", "PHONE_CALL",
                "campaign-1", "referral-1", null, null, null, null);
        LegacyCapture expected = new LegacyCapture(5L, 9L, "Synthetic", "+919900001234",
                "Two bedrooms", LeadIntent.RENT, "Flat", "2 BHK", new BigDecimal("12000"),
                new BigDecimal("18000"), "INR", LocalDate.parse("2026-12-01"), "Semi-furnished",
                "Working professional", true, false, true, "Second floor", List.of(11, 12),
                Instant.parse("2026-10-23T04:30:00Z"), "Asia/Kolkata", "PHONE_CALL",
                "campaign-1", "referral-1");
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new ObjectMapper().findAndRegisterModules().writeValueAsBytes(expected)));

        assertEquals(expectedHash, service.capturePayloadHash(command));
        LeadCaptureCommand extended = new LeadCaptureCommand(5L, 9L, "Synthetic",
                "+919900001234", "Two bedrooms", LeadIntent.RENT, "Flat", "2 BHK",
                new BigDecimal("12000"), new BigDecimal("18000"), "INR",
                LocalDate.parse("2026-12-01"), "Semi-furnished", "Working professional",
                true, false, true, "Second floor", List.of(11, 12),
                Instant.parse("2026-10-23T04:30:00Z"), "Asia/Kolkata", "PHONE_CALL",
                "campaign-1", "referral-1",
                LeadCaptureCommand.DuplicateDecision.KEEP_DISTINCT, 44L, 3L,
                "OPERATOR_CONFIRMED_DISTINCT_ENQUIRY");
        assertNotEquals(expectedHash, service.capturePayloadHash(extended));
    }

    @Test
    void contradictoryDuplicateResolutionIsRejectedBeforeAnyMutation() {
        LeadCaptureCommand command = new LeadCaptureCommand(5L, 9L, null, null, null,
                LeadIntent.UNSPECIFIED, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), null, null, null, null, null,
                LeadCaptureCommand.DuplicateDecision.ATTACH_ENQUIRY, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.capture(7L,
                UUID.randomUUID().toString(), command));

        verify(leads, never()).findLockedById(any());
        verify(leads, never()).saveAndFlush(any());
        verify(sourceEvents, never()).saveAndFlush(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void manualAttachmentEncryptionFailureLeavesNoSourceLeadActivityOrReceiptWrite() {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        LeadRoutingQueue target = new LeadRoutingQueue();
        target.setId(44L);
        target.setSupportedCity(city);
        target.setOperatingTeam(team);
        target.setOwner(actor);
        target.setStatus(LeadStatus.NEW);
        target.setLegacyProvenance(LeadLegacyProvenance.VERIFIED_MANUAL);
        when(leads.findLockedById(44L)).thenReturn(java.util.Optional.of(target));
        when(staffAccess.requireAttributableUser(7L)).thenReturn(actor);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T05:00:00Z"));
        when(leadAccess.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(9L), List.of()));
        when(protection.protect(anyString())).thenThrow(
                new com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException(
                        "controlled encryption failure"));
        LeadCaptureCommand command = new LeadCaptureCommand(5L, 9L, "Synthetic",
                "+919900001234", "New evidence", LeadIntent.RENT, "Flat", "2 BHK",
                new BigDecimal("20000"), new BigDecimal("25000"), "INR", null, null,
                null, null, null, null, null, List.of(), null, null, "PHONE_CALL",
                null, null, LeadCaptureCommand.DuplicateDecision.ATTACH_ENQUIRY,
                44L, 0L, "OPERATOR_CONFIRMED_EXISTING_ENQUIRY");

        assertThrows(com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException.class,
                () -> service.capture(7L, UUID.randomUUID().toString(), command));

        verify(sourceEvents, never()).saveAndFlush(any());
        verify(leads, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(audit, never()).recordUserEvent(any(), anyString(), anyString(), any(), anyString(),
                any(), any(), any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void captureAcquiresAuthorityGuardsBeforeItsFreshAdmissionDecision() {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(team.getCity()).thenReturn(city);
        when(cities.findByIdAndActiveTrue(5L)).thenReturn(java.util.Optional.of(city));
        when(teams.findByIdAndActiveTrue(9L)).thenReturn(java.util.Optional.of(team));
        when(guards.teamCityId(9L)).thenReturn(5L);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        when(staffAccess.requireAttributableUser(7L)).thenReturn(actor);
        when(leadAccess.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(), List.of()));
        LeadCaptureCommand command = new LeadCaptureCommand(5L, 9L, null, null, null,
                LeadIntent.UNSPECIFIED, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), null, null, null, null, null,
                null, null, null, null);

        assertThrows(AccessDeniedException.class, () -> service.capture(7L,
                UUID.randomUUID().toString(), command));

        InOrder order = inOrder(guards, leadAccess);
        order.verify(guards).acquire(List.of(5L), List.of(9L), List.of(7L));
        order.verify(leadAccess).context(7L);
        verify(leads, never()).saveAndFlush(any());
    }

    @Test
    void duplicateLookupUsesOneDatabaseInstantForBothJdbcTimestamps() {
        long actorId = 7L;
        long cityId = 5L;
        Instant databaseNow = Instant.parse("2026-10-22T05:00:00.123456Z");

        when(staffAccess.requireAttributableUser(actorId)).thenReturn(mock(User.class));
        when(leadAccess.context(actorId)).thenReturn(
                new LeadAccessContext(false, List.of(cityId), List.of(), List.of(), List.of()));
        when(clock.now()).thenReturn(databaseNow);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(actorId), any()))
                .thenReturn(0);
        when(leads.findDuplicateCandidates(anyString(), eq(cityId), eq(actorId),
                any(), any(), any(), any())).thenReturn(List.of());
        when(users.existsByMobileNumberNormalized(anyString())).thenReturn(false);

        service.duplicateCandidates(actorId,
                new LeadDuplicateCandidatesCommand(cityId, "+919900001234"));

        InOrder admissionOrder = inOrder(leadAccess, commandBounds, guards, entityManager, clock);
        admissionOrder.verify(leadAccess).context(actorId);
        admissionOrder.verify(commandBounds).apply();
        admissionOrder.verify(guards).acquireDuplicateLookup(
                List.of(cityId), List.of(), actorId);
        admissionOrder.verify(entityManager).clear();
        admissionOrder.verify(leadAccess).context(actorId);
        admissionOrder.verify(clock).now();
        org.mockito.ArgumentCaptor<Object> cutoff = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.ArgumentCaptor<Object> eventTime = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(actorId), cutoff.capture());
        verify(jdbc).update(anyString(), eq(actorId), eventTime.capture());
        verify(clock, times(1)).now();
        assertEquals(Timestamp.from(databaseNow.minusSeconds(3600)), cutoff.getValue());
        assertEquals(Timestamp.from(databaseNow), eventTime.getValue());
        assertEquals(databaseNow, ((Timestamp) cutoff.getValue()).toInstant().plusSeconds(3600));
    }

    @Test
    void duplicateLookupRejectsAtTheThresholdBeforeCandidateOrIndicatorWork() {
        long actorId = 7L;
        long cityId = 5L;
        when(staffAccess.requireAttributableUser(actorId)).thenReturn(mock(User.class));
        when(leadAccess.context(actorId)).thenReturn(
                new LeadAccessContext(false, List.of(cityId), List.of(), List.of(), List.of()));
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T05:00:00.123456Z"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(actorId), any()))
                .thenReturn(60);

        assertThrows(LocationLookupRateLimitException.class, () -> service.duplicateCandidates(
                actorId, new LeadDuplicateCandidatesCommand(cityId, "+919900001234")));

        verify(leads, never()).findDuplicateCandidates(
                anyString(), eq(cityId), eq(actorId), any(), any(), any(), any());
        verify(users, never()).existsByMobileNumberNormalized(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void duplicateLookupRequiresWholeRequestRetryWhenFreshAuthorityNeedsAnUnheldTeam() {
        long actorId = 7L;
        long cityId = 5L;
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(cityId);
        OperatingTeam firstTeam = mock(OperatingTeam.class);
        when(firstTeam.getId()).thenReturn(9L);
        when(firstTeam.getCity()).thenReturn(city);
        OperatingTeam newlyEffectiveTeam = mock(OperatingTeam.class);
        when(newlyEffectiveTeam.getId()).thenReturn(10L);
        when(newlyEffectiveTeam.getCity()).thenReturn(city);

        when(staffAccess.requireAttributableUser(actorId)).thenReturn(mock(User.class));
        when(leadAccess.context(actorId)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(9L), List.of()),
                new LeadAccessContext(false, List.of(), List.of(), List.of(10L), List.of()));
        when(teams.findAllById(List.of(9L))).thenReturn(List.of(firstTeam));
        when(teams.findAllById(List.of(10L))).thenReturn(List.of(newlyEffectiveTeam));

        assertThrows(VisitOperationsConflictException.class, () -> service.duplicateCandidates(
                actorId, new LeadDuplicateCandidatesCommand(cityId, "+919900001234")));

        verify(guards).acquireDuplicateLookup(List.of(cityId), List.of(9L), actorId);
        verify(jdbc, never()).queryForObject(
                contains("lead_lookup_events"), eq(Integer.class), eq(actorId), any());
    }

    private record LegacyCapture(
            Long cityId,
            Long teamId,
            String customerName,
            String contactPhone,
            String customerSaid,
            LeadIntent intent,
            String propertyType,
            String bhk,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String currency,
            LocalDate targetMoveInDate,
            String furnishing,
            String tenantProfile,
            Boolean parkingRequired,
            Boolean petsAllowed,
            Boolean liftRequired,
            String floorRequirement,
            List<Object> preferredLocalityIds,
            Instant followUpAt,
            String followUpZoneId,
            String sourceChannel,
            String campaignRef,
            String referralRef) {}
}
