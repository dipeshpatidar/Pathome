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
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
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
import java.util.Optional;

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
    private final LeadPhoneChallengeRepository phoneChallenges =
            mock(LeadPhoneChallengeRepository.class);
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
    private final LeadService service = new LeadService(leads, preferredLocalities, phoneChallenges, sourceEvents,
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
        when(leadAccess.contextForDiagnostics(actorId)).thenReturn(
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
        admissionOrder.verify(leadAccess).contextForDiagnostics(actorId);
        admissionOrder.verify(commandBounds).apply();
        admissionOrder.verify(guards).acquireDuplicateLookup(
                List.of(cityId), List.of(), actorId);
        admissionOrder.verify(entityManager).clear();
        admissionOrder.verify(leadAccess).contextForDiagnostics(actorId);
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
        when(leadAccess.contextForDiagnostics(actorId)).thenReturn(
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
        when(leadAccess.contextForDiagnostics(actorId)).thenReturn(
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

    @Test
    void ambiguousReceiptsRequireEveryStoredLeadToRemainReadableBeforeConflict() {
        UUID key=UUID.randomUUID();
        var actor=mock(User.class);
        var one=new com.indore.pathome.spaces.entity.LeadCommandReceipt(actor,key,"LEAD_CAPTURE","hash","10",Instant.now());
        var two=new com.indore.pathome.spaces.entity.LeadCommandReceipt(actor,key,"LEAD_SOURCE_ATTACH","hash","20",Instant.now());
        when(receipts.findAllByActorIdAndOperationIdOrderByCompletedAtDesc(7L,key)).thenReturn(List.of(one,two));
        LeadRoutingQueue a=mock(LeadRoutingQueue.class),b=mock(LeadRoutingQueue.class);
        when(leads.findById(10L)).thenReturn(Optional.of(a));when(leads.findById(20L)).thenReturn(Optional.of(b));
        assertThrows(VisitOperationsConflictException.class,()->service.receipt(7L,key,null));
        org.mockito.Mockito.doThrow(LeadAccessContext.hidden()).when(leadAccess).requireReadable(any(),eq(a),eq(7L));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,()->service.receipt(7L,key,null));
        verify(receipts,never()).saveAndFlush(any());
    }

    @Test
    void unsupportedStoredReceiptActionIsConcealed() {
        UUID key=UUID.randomUUID();
        var receipt=new com.indore.pathome.spaces.entity.LeadCommandReceipt(mock(User.class),key,"UNKNOWN_ACTION","hash","10",Instant.now());
        when(receipts.findByActorIdAndOperationIdAndAction(7L,key,"UNKNOWN_ACTION")).thenReturn(Optional.of(receipt));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,()->service.receipt(7L,key,"UNKNOWN_ACTION"));
    }

    private static final List<String> STORED_REFERENCE_ROUTES = List.of("CAPTURE", "ATTACH",
            "EXPLICIT_CAPTURE", "EXPLICIT_ATTACH", "ACTIONLESS_CAPTURE", "ACTIONLESS_ATTACH",
            "AMBIGUOUS_VALID_FIRST", "AMBIGUOUS_INVALID_FIRST", "EXPLICIT_SHORTLIST",
            "ACTIONLESS_SHORTLIST", "EXPLICIT_SHARE", "ACTIONLESS_SHARE", "EXPLICIT_CONTACT", "ACTIONLESS_CONTACT");

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformedReferences() {
        List<String> values = java.util.Arrays.asList(null, "", " ", "\t\n", "invalid-stored-reference",
                "+", "-", "1+2", "1.0", "1e3", "1x", "１２", "١٢", "1\u00a0", " 1", "1 ",
                "0", "-1", "-9223372036854775808", "9223372036854775808", "9".repeat(160),
                "9".repeat(1000), "null", "NaN", "Infinity", "+1", "1\u0000");
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String route : STORED_REFERENCE_ROUTES) {
            for (int i=0;i<values.size();i++) cases.add(org.junit.jupiter.params.provider.Arguments.of(route,"invalid-"+i,values.get(i)));
        }
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name="{0} / {1}")
    @org.junit.jupiter.params.provider.MethodSource("malformedReferences")
    void malformedStoredReferencesUseSameConcealedHandler(String route,String label,String reference) {
        assertMalformedReference(route,reference);
    }

    @Test
    void malformedStoredLeadBaselineDiscriminator() {
        assertMalformedReference("CAPTURE",null);
    }

    private void assertMalformedReference(String route,String reference) {
        UUID key=installStoredReference(route,reference);
        var failure=assertThrows(jakarta.persistence.EntityNotFoundException.class,
                ()->invokeStoredReference(route,key));
        assertEquals("Lead not found",failure.getMessage());
        var response=new com.indore.pathome.spaces.exception.GlobalExceptionHandler().handleEntityNotFound(
                failure,new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/operations/leads"));
        assertEquals(404,response.getStatusCode().value());
        assertEquals("Lead not found",response.getBody().getMessage());
        verify(leads,times(route.equals("AMBIGUOUS_VALID_FIRST")?1:0)).findById(any());
        verify(jdbc,never()).queryForObject(anyString(),eq(Long.class),org.mockito.ArgumentMatchers.<Object>any());
        verify(receipts,never()).saveAndFlush(any());verify(leads,never()).saveAndFlush(any());
        verify(sourceEvents,never()).saveAndFlush(any());verify(activities,never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    private UUID installStoredReference(String route,String reference) {
        LeadAccessContext context=new LeadAccessContext(false,List.of(5L),List.of(),List.of(9L),List.of());
        when(leadAccess.context(7L)).thenReturn(context);
        LeadRoutingQueue request=mock(LeadRoutingQueue.class);when(request.getId()).thenReturn(10L);
        when(leads.findVisibleById(eq(10L),eq(7L),any(),any(),any())).thenReturn(Optional.of(request));
        LeadRoutingQueue readable=mock(LeadRoutingQueue.class);when(readable.getId()).thenReturn(20L);
        when(leads.findById(20L)).thenReturn(Optional.of(readable));
        UUID key=UUID.randomUUID();
        String action=route.contains("ATTACH")?"LEAD_SOURCE_ATTACH":route.contains("SHORTLIST")?"LEAD_SHORTLIST_CREATE":route.contains("SHARE")?"LEAD_SHARE_PREPARE":route.contains("CONTACT")?"LEAD_CONTACT_PREFERENCE_RECORD":"LEAD_CAPTURE";
        var receipt=new com.indore.pathome.spaces.entity.LeadCommandReceipt(mock(User.class),key,action,"0".repeat(64),reference,Instant.now());
        when(receipts.findByActorIdAndOperationIdAndAction(7L,key,action)).thenReturn(Optional.of(receipt));
        List<com.indore.pathome.spaces.entity.LeadCommandReceipt> candidates=List.of(receipt);
        if(route.startsWith("AMBIGUOUS")) {
            var valid=new com.indore.pathome.spaces.entity.LeadCommandReceipt(mock(User.class),key,"LEAD_SOURCE_ATTACH","0".repeat(64),"20",Instant.now());
            candidates=route.endsWith("VALID_FIRST")&&!route.endsWith("INVALID_FIRST")?List.of(valid,receipt):List.of(receipt,valid);
        }
        when(receipts.findAllByActorIdAndOperationIdOrderByCompletedAtDesc(7L,key)).thenReturn(candidates);
        return key;
    }

    private Object invokeStoredReference(String route,UUID key) {
        if(route.equals("CAPTURE")) return service.capture(7L,key.toString(),new ObjectMapper().convertValue(
                java.util.Map.of("cityId",5L,"customerName","Synthetic probe"),LeadCaptureCommand.class));
        if(route.equals("ATTACH")) return service.attachSource(7L,10L,key.toString(),
                new com.indore.pathome.spaces.dto.crm.LeadSourceAttachCommand(0L,UUID.randomUUID(),
                        com.indore.pathome.spaces.dto.crm.LeadSourceAttachCommand.Decision.ATTACH_ENQUIRY,null));
        String action=route.contains("ATTACH")?"LEAD_SOURCE_ATTACH":route.contains("SHORTLIST")?"LEAD_SHORTLIST_CREATE":route.contains("SHARE")?"LEAD_SHARE_PREPARE":route.contains("CONTACT")?"LEAD_CONTACT_PREFERENCE_RECORD":"LEAD_CAPTURE";
        return service.receipt(7L,key,route.startsWith("EXPLICIT")?action:null);
    }

    static java.util.stream.Stream<String> numericOrphanRoutes() {
        return java.util.stream.Stream.of("CAPTURE","ATTACH","EXPLICIT_CAPTURE","EXPLICIT_ATTACH","ACTIONLESS_CAPTURE","ACTIONLESS_ATTACH");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("numericOrphanRoutes")
    void orphanStoredLeadHasSameConcealedHandler(String route) {
        UUID key=installStoredReference(route,"9223372036854775807");
        var failure=assertThrows(jakarta.persistence.EntityNotFoundException.class,()->invokeStoredReference(route,key));
        assertEquals("Lead not found",failure.getMessage());verify(leads).findById(Long.MAX_VALUE);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EXPLICIT_SHARE","EXPLICIT_SHORTLIST"})
    void validStoredSqlBindingDoesNotConcealInfrastructureFailure(String route) {
        String reference=route.contains("SHARE")?UUID.randomUUID().toString():"20";
        UUID key=installStoredReference(route,reference);
        when(jdbc.queryForObject(anyString(),eq(Long.class),org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic database unavailable"));
        var failure=assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->invokeStoredReference(route,key));
        var response=new com.indore.pathome.spaces.exception.GlobalExceptionHandler().handleGenericException(
                failure,new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/operations/lead-commands/submitted-key"));
        assertEquals(500,response.getStatusCode().value());
        assertEquals("We could not complete this request right now. Please try again shortly.",response.getBody().getMessage());
        verify(receipts,never()).saveAndFlush(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("numericOrphanRoutes")
    void repositoryFailureAfterValidParsingRetainsInfrastructureHandler(String route) {
        UUID key=installStoredReference(route,"20");
        when(leads.findById(20L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic read failure"));
        var failure=assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->invokeStoredReference(route,key));
        var response=new com.indore.pathome.spaces.exception.GlobalExceptionHandler().handleGenericException(failure,
                new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/operations/leads"));
        assertEquals(500,response.getStatusCode().value());verify(receipts,never()).saveAndFlush(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EXPLICIT_SHARE","EXPLICIT_SHORTLIST"})
    void validStoredSqlBindingMissingRowIsConcealedButNonUniqueFailureIsNot(String route) {
        String reference=route.contains("SHARE")?UUID.randomUUID().toString():"20";
        UUID key=installStoredReference(route,reference);
        when(jdbc.queryForObject(anyString(),eq(Long.class),org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,()->invokeStoredReference(route,key));
        org.mockito.Mockito.doThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1,2))
                .when(jdbc).queryForObject(anyString(),eq(Long.class),org.mockito.ArgumentMatchers.<Object>any());
        assertThrows(org.springframework.dao.IncorrectResultSizeDataAccessException.class,()->invokeStoredReference(route,key));
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
