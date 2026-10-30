package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.support.DbA01LeadHarnessContextInitializer;
import com.indore.pathome.spaces.support.DbA01LeadRuntimeConfiguration;
import com.indore.pathome.spaces.support.DbA01LeadSchemaHarness;
import com.indore.pathome.spaces.support.DbA01LeadTestTarget;
import com.indore.pathome.spaces.support.DbA01LeadFixtureHarness;
import com.indore.pathome.spaces.support.DbA01BrowserLauncher;
import com.indore.pathome.spaces.support.DbA01BrowserProcess;
import com.indore.pathome.spaces.security.JwtUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-db-a01-lead-jwt-secret-32-bytes",
        "pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "pathome.crm.contact-encryption-key-id=db-a01-primary",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "cloudinary.api-key=db-a01-test-key",
        "cloudinary.api-secret=db-a01-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(DbA01LeadRuntimeConfiguration.class)
@ContextConfiguration(initializers = DbA01LeadHarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = DbA01LeadTestTarget.TEST_FLAG, matches = "true")
class DbA01LeadOwnerAuthorizationHttpPostgresTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private JwtUtils jwt;
    @Autowired private ObjectMapper mapper;
    @Autowired private LeadDataProtection protection;
    @org.springframework.boot.test.mock.mockito.SpyBean private OperationalAuditService malformedAuditFault;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.indore.pathome.spaces.repository.LeadShortlistRepository parserShortlistFault;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.indore.pathome.spaces.repository.LeadShareRepository parserShareFault;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private LeadPublicPropertyService propertyGuardFault;
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("leadFixtureJdbc") private JdbcTemplate fixture;

    @BeforeAll
    static void migrateOnce() throws Exception {
        DbA01LeadSchemaHarness.migrate(DbA01LeadTestTarget.requireFromEnvironment());
    }

    @BeforeEach
    void verifyTarget() {
        assertEquals(DbA01LeadTestTarget.DATABASE,
                jdbc.queryForObject("select current_database()", String.class));
        assertEquals(DbA01LeadTestTarget.RUNTIME_ROLE,
                jdbc.queryForObject("select current_user", String.class));
        if (Boolean.getBoolean("pathome.db-a01.parser-closure")) {
            // This closure may reuse accepted fixture hooks but must issue no DDL.
            assertTrue(Boolean.TRUE.equals(fixture.queryForObject("select "
                    + "to_regclass('public.db_a01_activity_failure') is not null and "
                    + "to_regclass('public.db_a01_event_gate_seq') is not null and "
                    + "to_regprocedure('public.db_a01_fail_lead_activity()') is not null and "
                    + "to_regprocedure('public.db_a01_hold_share_event()') is not null and "
                    + "to_regprocedure('public.similarity(text,text)') is not null", Boolean.class)));
        } else {
            try (Connection connection = fixture.getDataSource().getConnection()) {
                DbA01LeadFixtureHarness.verifyInstalled(
                        connection, DbA01LeadTestTarget.requireFromEnvironment());
            } catch (Exception exception) {
                throw new IllegalStateException("Approved DB-A01 fixtures changed", exception);
            }
        }
    }

    @Test
    void cityIntakeDeniesSensitiveCommandsButCanPerformApprovedEdit() throws Exception {
        World world = world("d01");
        long leadId = fixtureLead(world.cityId(), null, null, "D01 unassigned");
        long initialVersion = leadVersion(leadId);

        HttpResponse<String> edit = send("PATCH", "/api/v1/operations/leads/" + leadId,
                world.intake().token(),
                "{\"expectedVersion\":" + initialVersion
                        + ",\"changes\":{\"customerSaid\":\"Legitimate intake edit\"}}");
        assertEquals(200, edit.statusCode(), edit.body());
        assertEquals(initialVersion + 1, leadVersion(leadId));
        Snapshot afterEdit = sensitiveSnapshot(leadId);

        for (String choice : java.util.List.of("SAME", "DIFFERENT", "NONE")) {
            HttpResponse<String> denied = send("POST",
                    "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                    world.intake().token(), contactBody(leadVersion(leadId), 0L, choice, "GRANTED"),
                    "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(404, denied.statusCode(), denied.body());
            assertFalse(denied.body().contains("+9198"));
            assertFalse(denied.body().contains("intake edit"));
        }

        assertEquals(afterEdit, sensitiveSnapshot(leadId));
        assertEquals(1L, fixture.queryForObject(
                "select count(*) from lead_activities where lead_id = ?", Long.class, leadId));
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from lead_command_receipts where action = 'LEAD_CONTACT_PREFERENCE_RECORD' "
                        + "and result_reference like 'preference:%' and actor_user_id = ?",
                Long.class, world.intake().userId()));
    }

    @Test
    void ownerCanRunFullManualShareLifecycleWhileIntakeCannotPrepareOrRecordEvents()
            throws Exception {
        World world = world("d02-d03");
        long listingId = fixtureProperty(world.cityId(), world.localityId(), "D03 public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "d02-d03");
        Snapshot beforeIntake = sensitiveSnapshot(setup.leadId());

        HttpResponse<String> intakeConsent = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/contact-preferences",
                world.intake().token(),
                contactBody(leadVersion(setup.leadId()),
                        setup.preference().path("revision").asLong(), "NONE", "REVOKED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(404, intakeConsent.statusCode(), intakeConsent.body());

        String intakePrepare = shareBody(UUID.randomUUID(), leadVersion(setup.leadId()),
                setup.shortlist().path("id").asLong(),
                setup.shortlist().path("version").asLong(),
                setup.preference().path("revision").asLong());
        HttpResponse<String> deniedPrepare = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.intake().token(), intakePrepare);
        assertEquals(404, deniedPrepare.statusCode(), deniedPrepare.body());

        for (String eventType : List.of("CLIPBOARD_COPIED", "HANDOFF_ATTEMPTED",
                "OPERATOR_REPORTED_MANUAL_SEND", "HANDOFF_CANCELLED")) {
            HttpResponse<String> deniedEvent = send("POST",
                    "/api/v1/operations/leads/" + setup.leadId() + "/shares/"
                            + setup.shareId() + "/events",
                    world.intake().token(),
                    "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + eventType + "\"}");
            assertEquals(404, deniedEvent.statusCode(), deniedEvent.body());
        }
        assertEquals(beforeIntake, sensitiveSnapshot(setup.leadId()));

        String ownerShareBody = shareBody(UUID.randomUUID(), leadVersion(setup.leadId()),
                setup.shortlist().path("id").asLong(),
                setup.shortlist().path("version").asLong(),
                setup.preference().path("revision").asLong());
        HttpResponse<String> prepared = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), ownerShareBody);
        assertEquals(201, prepared.statusCode(), prepared.body());
        UUID shareId = UUID.fromString(mapper.readTree(prepared.body()).path("id").asText());
        assertTrue(prepared.body().contains("https://pathome.test/property/" + listingId));
        assertFalse(prepared.body().contains("+919876543210"));

        HttpResponse<String> replay = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), ownerShareBody);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(shareId, UUID.fromString(mapper.readTree(replay.body()).path("id").asText()));
        assertEquals(2L, fixture.queryForObject(
                "select count(*) from lead_shares where lead_id = ?",
                Long.class, setup.leadId()));

        Map<String, UUID> events = new LinkedHashMap<>();
        for (String eventType : List.of("CLIPBOARD_COPIED", "HANDOFF_ATTEMPTED",
                "OPERATOR_REPORTED_MANUAL_SEND", "HANDOFF_CANCELLED")) {
            UUID eventId = UUID.randomUUID();
            events.put(eventType, eventId);
            HttpResponse<String> recorded = send("POST",
                    "/api/v1/operations/leads/" + setup.leadId() + "/shares/" + shareId + "/events",
                    world.owner().token(),
                    "{\"eventId\":\"" + eventId + "\",\"type\":\"" + eventType + "\"}");
            assertEquals(200, recorded.statusCode(), recorded.body());
            HttpResponse<String> eventReplay = send("POST",
                    "/api/v1/operations/leads/" + setup.leadId() + "/shares/" + shareId + "/events",
                    world.owner().token(),
                    "{\"eventId\":\"" + eventId + "\",\"type\":\"" + eventType + "\"}");
            assertEquals(200, eventReplay.statusCode(), eventReplay.body());
        }
        assertEquals(4L, fixture.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, shareId));
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from visit_notification_outbox", Long.class));

        JsonNode history = mapper.readTree(send("GET",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares?page=0&size=10",
                world.owner().token(), null).body());
        JsonNode current = history.path("items").get(0);
        assertTrue(current.path("currentAuthorityMatches").asBoolean());
        assertTrue(current.path("actionReady").asBoolean());
        assertTrue(current.path("maskedDestination").asText().endsWith("3210"));
        assertFalse(history.toString().contains("+919876543210"));

        for (String choice : List.of("DIFFERENT", "NONE")) {
            long otherLead = fixtureLead(world.cityId(), world.teamId(), world.owner().userId(),
                    "D03 " + choice);
            HttpResponse<String> preference = send("POST",
                    "/api/v1/operations/leads/" + otherLead + "/contact-preferences",
                    world.owner().token(),
                    contactBody(leadVersion(otherLead), 0L, choice, "GRANTED"),
                    "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(201, preference.statusCode(), preference.body());
        }
    }

    @Test
    void intakeOwnerGuardIsTheOnlyDiscriminatorForSeededUnassignedShareCommands()
            throws Exception {
        World world = world("d02-owner-guard");
        long listingId = fixtureProperty(world.cityId(), world.localityId(), "D02 owner guard home");
        long leadId = fixtureLead(world.cityId(), null, null, "D02 unassigned sharing");
        LeadDataProtection.ProtectedValue destination = protection.protect("+919876543210");
        long preferenceId = insertReturningLong(
                "insert into lead_contact_preferences(lead_id,revision,channel,destination_choice,"
                        + "destination_ciphertext,destination_iv,destination_key_version,"
                        + "destination_fingerprint,consent_status,consent_purpose,"
                        + "consent_wording_version,evidence_method,recorded_at,captured_by_user_id) "
                        + "values (?,1,'WHATSAPP','SAME',?,?,?,?,'GRANTED','PROPERTY_SHARING',"
                        + "'property-sharing-v1','CUSTOMER_VERBAL',now(),?) returning id",
                leadId, destination.ciphertext(), destination.iv(), destination.keyId(),
                destination.fingerprint(), world.intake().userId());
        assertTrue(preferenceId > 0);
        long shortlistId = insertReturningLong(
                "insert into lead_shortlists(lead_id,version,created_at,updated_at) "
                        + "values (?,0,now(),now()) returning id", leadId);
        fixture.update("insert into lead_shortlist_items(shortlist_id,listing_id,position) "
                + "values (?,?,0)", shortlistId, listingId);
        UUID shareId = UUID.randomUUID();
        String preview = """
                🏡 Your shortlisted home from Pathome
                D02 owner guard home
                Synthetic sector, Bhopal Municipal Central Residential Corridor
                Rent: INR 18,000 / month · 2 BHK
                View property details: https://pathome.test/property/%d
                Interested? Open Pathome to request a visit.
                Pathome — Your Dreams, Our Efforts.""".formatted(listingId);
        fixture.update("insert into lead_shares(id,lead_id,shortlist_id,shortlist_version,"
                        + "contact_preference_revision,mode,public_links_json,preview_text,"
                        + "masked_destination,recorded_at,created_by_user_id) "
                        + "values (?,?,?,0,1,'COPY_LINKS',?,?,?,now(),?)",
                shareId, leadId, shortlistId,
                "[\"https://pathome.test/property/" + listingId + "\"]",
                preview, "********3210", world.intake().userId());
        Snapshot before = sensitiveSnapshot(leadId);

        String command = shareBody(UUID.randomUUID(), leadVersion(leadId), shortlistId, 0L, 1L)
                .replace("MANUAL_BUSINESS_HANDOFF", "COPY_LINKS");
        HttpResponse<String> prepare = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shares",
                world.intake().token(), command);
        assertEquals(404, prepare.statusCode(), prepare.body());
        for (String type : List.of("CLIPBOARD_COPIED", "HANDOFF_ATTEMPTED",
                "OPERATOR_REPORTED_MANUAL_SEND", "HANDOFF_CANCELLED")) {
            HttpResponse<String> event = send("POST",
                    "/api/v1/operations/leads/" + leadId + "/shares/" + shareId + "/events",
                    world.intake().token(),
                    "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\"}");
            assertEquals(404, event.statusCode(), event.body());
        }
        assertEquals(before, sensitiveSnapshot(leadId));
    }

    @Test
    void partialGrantsAndAdministrativeRolesDoNotUnionIntoContactAuthority() throws Exception {
        World world = world("d04");
        long leadId = fixtureLead(world.cityId(), world.teamId(), world.owner().userId(), "D04 assigned");
        String valid = contactBody(leadVersion(leadId), 0L, "SAME", "GRANTED");
        Snapshot baseline = sensitiveSnapshot(leadId);

        long otherCity = insertReturningLong(
                "insert into supported_cities(code,display_name,active) values (?,?,true) returning id",
                "db-a01-other-" + UUID.randomUUID().toString().substring(0, 8),
                "DB A01 Other City");
        long otherTeam = insertReturningLong(
                "insert into operating_teams(city_id,code,display_name,active) "
                        + "values (?,?,?,true) returning id",
                otherCity, "db-a01-other-team-" + UUID.randomUUID().toString().substring(0, 8),
                "DB A01 Other Team");
        Actor peer = staff("peer-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, world.teamId(), null, null));
        Actor supervisor = staff("supervisor-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_SUPERVISE", "TEAM", null, world.teamId(), null, null));
        Actor cityAdmin = staff("city-admin-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("CITY_TEAM_ADMIN", "CITY", world.cityId(), null, null, null));
        Actor globalOnly = staff("global-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("STAFF_ADMIN", "GLOBAL", null, null, null, null));
        Actor mixedForeignTeam = staff("mixed-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_INTAKE", "CITY", world.cityId(), null, null, null),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, otherTeam, null, null));
        Actor inactive = staff("inactive-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, world.teamId(), null, null));
        fixture.update("update employee_profiles set staff_active=false, "
                + "staff_deactivated_at=now() where user_id=?", inactive.userId());

        String forgedEmail = "forged-role-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        long forgedId = insertReturningLong(
                "insert into users(email,full_name,role) values (?,?,'ROLE_TENANT') returning id",
                forgedEmail, "Forged role token");
        Actor forgedRole = new Actor(forgedId, forgedEmail,
                jwt.generateToken(forgedId, forgedEmail, "ROLE_ADMIN"), List.of());

        Map<Actor, Integer> expected = new LinkedHashMap<>();
        expected.put(peer, 404);
        expected.put(supervisor, 404);
        expected.put(cityAdmin, 404);
        expected.put(globalOnly, 403);
        expected.put(mixedForeignTeam, 404);
        expected.put(inactive, 403);
        expected.put(forgedRole, 403);
        for (Map.Entry<Actor, Integer> entry : expected.entrySet()) {
            HttpResponse<String> denied = send("POST",
                    "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                    entry.getKey().token(), valid,
                    "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(entry.getValue(), denied.statusCode(), denied.body());
            assertFalse(denied.body().contains("+919876543210"));
            assertFalse(denied.body().contains("D04 assigned"));
        }
        assertEquals(baseline, sensitiveSnapshot(leadId));

        long inactiveLead = fixtureLead(world.cityId(), world.teamId(), inactive.userId(),
                "D04 inactive profile owner");
        HttpResponse<String> inactiveOwner = send("POST",
                "/api/v1/operations/leads/" + inactiveLead + "/contact-preferences",
                inactive.token(), contactBody(leadVersion(inactiveLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, inactiveOwner.statusCode(), inactiveOwner.body());

        HttpResponse<String> supervisorRead = send("GET",
                "/api/v1/operations/leads/" + leadId, supervisor.token(), null);
        assertEquals(200, supervisorRead.statusCode(), supervisorRead.body());
        assertFalse(supervisorRead.body().contains("+919876543210"));

        HttpResponse<String> mixedForeignOwner = send("POST",
                "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                mixedForeignTeam.token(), valid,
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(404, mixedForeignOwner.statusCode(), mixedForeignOwner.body());

        long ownerLead = fixtureLead(world.cityId(), world.teamId(), world.owner().userId(),
                "D05 owner positive");
        HttpResponse<String> ownerPositive = send("POST",
                "/api/v1/operations/leads/" + ownerLead + "/contact-preferences",
                world.owner().token(), contactBody(leadVersion(ownerLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, ownerPositive.statusCode(), ownerPositive.body());
    }

    @Test
    void reassignmentRevocationExpiryAndInactiveScopeFailClosedBeforeWrites()
            throws Exception {
        World world = world("d06");
        long leadId = fixtureLead(world.cityId(), world.teamId(), world.owner().userId(), "D06 lead");

        HttpResponse<String> wrongVersion = send("POST",
                "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                world.owner().token(),
                contactBody(leadVersion(leadId) + 9, 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(409, wrongVersion.statusCode(), wrongVersion.body());

        long peerId = insertReturningLong(
                "insert into users(email,full_name,role) values (?,?,'ROLE_TENANT') returning id",
                "db-a01-reassigned-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@example.test", "Reassigned owner");
        fixture.update("update lead_routing_queue set owner_user_id=?, version=version+1 where id=?",
                peerId, leadId);
        Snapshot afterReassignment = sensitiveSnapshot(leadId);
        HttpResponse<String> formerOwner = send("POST",
                "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                world.owner().token(),
                contactBody(leadVersion(leadId), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(404, formerOwner.statusCode(), formerOwner.body());
        assertEquals(afterReassignment, sensitiveSnapshot(leadId));

        World revokeWorld = world("d06-revoke");
        long revokedLead = fixtureLead(revokeWorld.cityId(), revokeWorld.teamId(),
                revokeWorld.owner().userId(), "D06 revoked");
        fixture.update("update staff_access_grants set revoked_at=now(), revoked_by_user_id=?, "
                        + "revoke_reason_code='DB_A01_REVOKED' where id=?",
                revokeWorld.owner().userId(), revokeWorld.owner().grantIds().get(0));
        Snapshot beforeRevoked = sensitiveSnapshot(revokedLead);
        HttpResponse<String> revoked = send("POST",
                "/api/v1/operations/leads/" + revokedLead + "/contact-preferences",
                revokeWorld.owner().token(),
                contactBody(leadVersion(revokedLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, revoked.statusCode(), revoked.body());
        assertEquals(beforeRevoked, sensitiveSnapshot(revokedLead));

        World expiryWorld = world("d06-expiry");
        Actor future = staff("future-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, expiryWorld.teamId(),
                        Instant.now().plus(1, ChronoUnit.HOURS), null));
        Actor expired = staff("expired-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, expiryWorld.teamId(),
                        Instant.now().minus(2, ChronoUnit.HOURS),
                        Instant.now().minus(1, ChronoUnit.HOURS)));
        for (Actor actor : List.of(future, expired)) {
            long expiryLead = fixtureLead(expiryWorld.cityId(), expiryWorld.teamId(),
                    actor.userId(), "D06 expiry");
            HttpResponse<String> denied = send("POST",
                    "/api/v1/operations/leads/" + expiryLead + "/contact-preferences",
                    actor.token(), contactBody(leadVersion(expiryLead), 0L, "SAME", "GRANTED"),
                    "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(403, denied.statusCode(), denied.body());
        }

        World inactiveWorld = world("d06-inactive");
        long inactiveLead = fixtureLead(inactiveWorld.cityId(), inactiveWorld.teamId(),
                inactiveWorld.owner().userId(), "D06 inactive");
        fixture.update("update supported_cities set active=false,version=version+1 where id=?",
                inactiveWorld.cityId());
        HttpResponse<String> inactiveCity = send("POST",
                "/api/v1/operations/leads/" + inactiveLead + "/contact-preferences",
                inactiveWorld.owner().token(),
                contactBody(leadVersion(inactiveLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, inactiveCity.statusCode(), inactiveCity.body());

        World inactiveTeamWorld = world("d06-inactive-team");
        long inactiveTeamLead = fixtureLead(inactiveTeamWorld.cityId(), inactiveTeamWorld.teamId(),
                inactiveTeamWorld.owner().userId(), "D06 inactive team");
        fixture.update("update operating_teams set active=false,version=version+1 where id=?",
                inactiveTeamWorld.teamId());
        HttpResponse<String> inactiveTeam = send("POST",
                "/api/v1/operations/leads/" + inactiveTeamLead + "/contact-preferences",
                inactiveTeamWorld.owner().token(),
                contactBody(leadVersion(inactiveTeamLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, inactiveTeam.statusCode(), inactiveTeam.body());

        World closedWorld = world("d06-closed");
        long closedLead = fixtureLead(closedWorld.cityId(), closedWorld.teamId(),
                closedWorld.owner().userId(), "D06 closed");
        fixture.update("update lead_routing_queue set status='CLOSED',version=version+1 where id=?",
                closedLead);
        HttpResponse<String> closed = send("POST",
                "/api/v1/operations/leads/" + closedLead + "/contact-preferences",
                closedWorld.owner().token(),
                contactBody(leadVersion(closedLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(409, closed.statusCode(), closed.body());
    }

    @Test
    void lostAcknowledgementReplayAndHistoryRemainOwnerScopedAndPrivacySafe()
            throws Exception {
        World world = world("d09-d10");
        long listingId = fixtureProperty(world.cityId(), world.localityId(), "D09 public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "d09-d10");

        UUID operationId = UUID.randomUUID();
        String replayBody = shareBody(operationId, leadVersion(setup.leadId()),
                setup.shortlist().path("id").asLong(), setup.shortlist().path("version").asLong(),
                setup.preference().path("revision").asLong());
        LostResponse lost = sendWithoutClientAcknowledgement(
                "POST", "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), replayBody, null, null);
        assertTrue(lost.serverResponse().startsWith("HTTP/1.1 201"), lost.serverResponse());
        long sharesAfterLostResponse = fixture.queryForObject(
                "select count(*) from lead_shares where lead_id = ?",
                Long.class, setup.leadId());

        HttpResponse<String> exactReplay = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), replayBody);
        assertEquals(201, exactReplay.statusCode(), exactReplay.body());
        assertEquals(sharesAfterLostResponse, fixture.queryForObject(
                "select count(*) from lead_shares where lead_id = ?",
                Long.class, setup.leadId()));

        String changed = replayBody.replace("MANUAL_BUSINESS_HANDOFF", "COPY_LINKS");
        HttpResponse<String> changedConflict = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), changed);
        assertEquals(409, changedConflict.statusCode(), changedConflict.body());

        Actor supervisor = staff("supervisor-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_SUPERVISE", "TEAM", null, world.teamId(), null, null));
        HttpResponse<String> supervisorHistory = send("GET",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares?page=0&size=10",
                supervisor.token(), null);
        assertEquals(200, supervisorHistory.statusCode(), supervisorHistory.body());
        JsonNode history = mapper.readTree(supervisorHistory.body());
        assertFalse(history.path("items").get(0).path("currentAuthorityMatches").asBoolean());
        assertFalse(history.path("items").get(0).path("actionReady").asBoolean());
        assertTrue(history.toString().contains("********3210"));
        assertFalse(history.toString().contains("+919876543210"));

        HttpResponse<String> contactHistory = send("GET",
                "/api/v1/operations/leads/" + setup.leadId() + "/contact-preferences",
                supervisor.token(), null);
        assertEquals(200, contactHistory.statusCode(), contactHistory.body());
        assertTrue(contactHistory.body().contains("********3210"));
        assertFalse(contactHistory.body().contains("+919876543210"));

        long supervisorGrant = insertReturningLong(
                "insert into staff_access_grants(user_id,capability,scope_type,team_id,"
                        + "effective_at,grant_reason_code,provisioning_source) "
                        + "values (?,'OPS_SUPERVISE','TEAM',?,now(),'DB_A01_READ_ONLY','ADMIN_API') "
                        + "returning id",
                world.owner().userId(), world.teamId());
        fixture.update("update staff_access_grants set revoked_at=now(), revoked_by_user_id=?, "
                        + "revoke_reason_code='DB_A01_COORDINATE_REVOKED' where id=?",
                world.owner().userId(), world.owner().grantIds().get(0));
        HttpResponse<String> retainedRead = send("GET",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares?page=0&size=10",
                world.owner().token(), null);
        assertEquals(200, retainedRead.statusCode(), retainedRead.body());
        JsonNode retainedHistory = mapper.readTree(retainedRead.body());
        assertFalse(retainedHistory.path("items").get(0).path("currentAuthorityMatches").asBoolean());
        assertFalse(retainedHistory.path("items").get(0).path("actionReady").asBoolean());

        HttpResponse<String> hiddenConflict = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), changed);
        assertEquals(404, hiddenConflict.statusCode(), hiddenConflict.body());
        assertFalse(hiddenConflict.body().contains("different share request"));
        assertTrue(supervisorGrant > 0);
    }

    @Test
    void admissionWinsRevocationAndExpiryIsEvaluatedAfterRealLockWaits()
            throws Exception {
        World world = world("d07");
        long listingId = fixtureProperty(world.cityId(), world.localityId(), "D07 public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "d07");

        try (Connection gate = fixture.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var lock = gate.createStatement()) {
                lock.execute("select pg_advisory_xact_lock(918273645)");
            }
            long gateBefore = fixture.queryForObject(
                    "select last_value from public.db_a01_event_gate_seq", Long.class);
            UUID admittedEventId = UUID.randomUUID();
            CompletableFuture<HttpResponse<String>> admitted = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST",
                            "/api/v1/operations/leads/" + setup.leadId()
                                    + "/shares/" + setup.shareId() + "/events",
                            world.owner().token(),
                            "{\"eventId\":\"" + admittedEventId
                                    + "\",\"type\":\"CLIPBOARD_COPIED\"}");
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            awaitGateCounter(gateBefore, admitted);

            CompletableFuture<Void> revocation = CompletableFuture.runAsync(() -> {
                try (Connection revoke = fixture.getDataSource().getConnection()) {
                    revoke.setAutoCommit(false);
                    try (var statement = revoke.prepareStatement(
                            "update staff_access_grants set revoked_at=clock_timestamp(), "
                                    + "revoked_by_user_id=?, revoke_reason_code='DB_A01_CONCURRENT_REVOKE' "
                                    + "where id=?")) {
                        statement.setLong(1, world.owner().userId());
                        statement.setLong(2, world.owner().grantIds().get(0));
                        statement.executeUpdate();
                    }
                    revoke.commit();
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            awaitRevocationWait(revocation);
            gate.rollback();

            HttpResponse<String> admittedResponse = admitted.get(20, TimeUnit.SECONDS);
            assertEquals(200, admittedResponse.statusCode(), admittedResponse.body());
            revocation.get(20, TimeUnit.SECONDS);
            long events = fixture.queryForObject(
                    "select count(*) from lead_share_events where share_id=?",
                    Long.class, setup.shareId());

            HttpResponse<String> later = send("POST",
                    "/api/v1/operations/leads/" + setup.leadId()
                            + "/shares/" + setup.shareId() + "/events",
                    world.owner().token(),
                    "{\"eventId\":\"" + UUID.randomUUID()
                            + "\",\"type\":\"CLIPBOARD_COPIED\"}");
            assertEquals(403, later.statusCode(), later.body());
            assertEquals(events, fixture.queryForObject(
                    "select count(*) from lead_share_events where share_id=?",
                    Long.class, setup.shareId()));
        }

        World expiryWorld = world("d08-sole");
        long expiryListing = fixtureProperty(
                expiryWorld.cityId(), expiryWorld.localityId(), "D08 expiry home");
        Actor expiringOwner = staff("expiring-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, expiryWorld.teamId(),
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        Instant.now().plus(8, ChronoUnit.SECONDS)));
        ShareSetup expiring = ownerShareSetup(expiryWorld, expiryListing, "d08-expiry",
                expiringOwner);
        HttpResponse<String> expiredEvent = eventWhileListingLocked(
                expiring, expiringOwner, expiryListing, 8_200);
        assertEquals(403, expiredEvent.statusCode(), expiredEvent.body());

        World dualWorld = world("d08-dual");
        long dualListing = fixtureProperty(
                dualWorld.cityId(), dualWorld.localityId(), "D08 independent grant home");
        Actor dualOwner = staff("dual-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, dualWorld.teamId(),
                        Instant.now().minus(2, ChronoUnit.HOURS),
                        Instant.now().plus(8, ChronoUnit.SECONDS)),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, dualWorld.teamId(),
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        Instant.now().plus(1, ChronoUnit.HOURS)));
        ShareSetup independent = ownerShareSetup(
                dualWorld, dualListing, "d08-independent", dualOwner);
        HttpResponse<String> independentEvent = eventWhileListingLocked(
                independent, dualOwner, dualListing, 8_200);
        assertEquals(200, independentEvent.statusCode(), independentEvent.body());
    }

    @Test
    void listingLockWaitExpiryDeniesSharePrepareAndShortlistCreateOrUpdate() throws Exception {
        World shareWorld = world("listing-share-expiry");
        long shareListing = fixtureProperty(
                shareWorld.cityId(), shareWorld.localityId(), "Listing wait share home");
        ShareSetup shareSetup = ownerShareSetup(
                shareWorld, shareListing, "listing wait share");
        long shareExpiringGrant = insertExpiringGrantAndRevokeExisting(
                shareWorld.owner(), shareWorld.teamId(), 2_500);
        Snapshot shareBefore = sensitiveSnapshot(shareSetup.leadId());
        UUID shareOperation = UUID.randomUUID();
        String shareCommand = shareBody(
                shareOperation, leadVersion(shareSetup.leadId()),
                shareSetup.shortlist().path("id").asLong(),
                shareSetup.shortlist().path("version").asLong(),
                shareSetup.preference().path("revision").asLong());

        ListingWait shareWait = executeWhileListingLocked(
                shareListing, shareWorld.owner().token(),
                "/api/v1/operations/leads/" + shareSetup.leadId() + "/shares",
                shareCommand, "%listings%for update%", shareWorld.owner().userId(),
                shareExpiringGrant);
        assertEquals(403, shareWait.response().statusCode(), shareWait.response().body());
        assertTrue(shareWait.grantValidBeforeWait());
        assertTrue(shareWait.expiredWhileBlocked());
        assertEquals(shareWait.blockerPid(),
                shareWait.blockingPids().stream()
                        .filter(pid -> pid.equals(shareWait.blockerPid()))
                        .findFirst()
                        .orElseThrow());
        assertFalse(shareWait.blockingPids().contains(shareWait.unrelatedPid()));
        assertTrue(shareWait.waiterQuery().toLowerCase().contains("listings"));
        assertTrue(shareWait.waiterQuery().toLowerCase().contains("for update"));
        assertEquals(shareBefore, sensitiveSnapshot(shareSetup.leadId()));
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from lead_command_receipts "
                        + "where actor_user_id=? and operation_id=?",
                Long.class, shareWorld.owner().userId(), shareOperation));

        World createWorld = world("listing-create-expiry");
        long createListing = fixtureProperty(
                createWorld.cityId(), createWorld.localityId(), "Listing wait create home");
        long createLead = fixtureLead(
                createWorld.cityId(), createWorld.teamId(),
                createWorld.owner().userId(), "Listing wait create");
        long createExpiringGrant = insertExpiringGrantAndRevokeExisting(
                createWorld.owner(), createWorld.teamId(), 2_500);
        long createShortlistsBefore = fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?",
                Long.class, createLead);
        long createActivitiesBefore = fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?",
                Long.class, createLead);
        ListingWait createWait = executeWhileListingLocked(
                createListing, createWorld.owner().token(),
                "/api/v1/operations/leads/" + createLead + "/shortlists",
                "{\"expectedLeadVersion\":" + leadVersion(createLead)
                        + ",\"listingIds\":[" + createListing + "]}",
                "%listings%for update%", createWorld.owner().userId(),
                createExpiringGrant, "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, createWait.response().statusCode(), createWait.response().body());
        assertTrue(createWait.grantValidBeforeWait());
        assertTrue(createWait.expiredWhileBlocked());
        assertFalse(createWait.blockingPids().contains(createWait.unrelatedPid()));
        assertEquals(createShortlistsBefore, fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?",
                Long.class, createLead));
        assertEquals(createActivitiesBefore, fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?",
                Long.class, createLead));

        World updateWorld = world("listing-update-expiry");
        long firstListing = fixtureProperty(
                updateWorld.cityId(), updateWorld.localityId(), "Listing wait update one");
        long secondListing = fixtureProperty(
                updateWorld.cityId(), updateWorld.localityId(), "Listing wait update two");
        long updateLead = fixtureLead(
                updateWorld.cityId(), updateWorld.teamId(),
                updateWorld.owner().userId(), "Listing wait update");
        JsonNode initialShortlist = mapper.readTree(send("POST",
                "/api/v1/operations/leads/" + updateLead + "/shortlists",
                updateWorld.owner().token(),
                "{\"expectedLeadVersion\":" + leadVersion(updateLead)
                        + ",\"listingIds\":[" + firstListing + "," + secondListing + "]}",
                "Idempotency-Key", UUID.randomUUID().toString()).body());
        long updateExpiringGrant = insertExpiringGrantAndRevokeExisting(
                updateWorld.owner(), updateWorld.teamId(), 2_500);
        long updateShortlistId = initialShortlist.path("id").asLong();
        List<Long> priorOrder = fixture.queryForList(
                "select listing_id from lead_shortlist_items "
                        + "where shortlist_id=? order by position",
                Long.class, updateShortlistId);
        long updateActivitiesBefore = fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?",
                Long.class, updateLead);
        ListingWait updateWait = executeWhileListingLocked(
                secondListing, updateWorld.owner().token(),
                "/api/v1/operations/leads/" + updateLead
                        + "/shortlists/" + updateShortlistId,
                "{\"expectedLeadVersion\":" + leadVersion(updateLead)
                        + ",\"expectedShortlistVersion\":"
                        + initialShortlist.path("version").asLong()
                        + ",\"listingIds\":[" + secondListing + "," + firstListing + "]}",
                "%listings%for update%", updateWorld.owner().userId(),
                updateExpiringGrant, null, null, "PUT");
        assertEquals(403, updateWait.response().statusCode(), updateWait.response().body());
        assertTrue(updateWait.grantValidBeforeWait());
        assertTrue(updateWait.expiredWhileBlocked());
        assertEquals(priorOrder, fixture.queryForList(
                "select listing_id from lead_shortlist_items "
                        + "where shortlist_id=? order by position",
                Long.class, updateShortlistId));
        assertEquals(updateActivitiesBefore, fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?",
                Long.class, updateLead));
    }

    @Test
    void publicPropertyRevalidationRejectsShortlistCreateUpdateAndSharePreparationBeforeWrites()
            throws Exception {
        World createWorld = world("o4-shortlist-create");
        long createLead = fixtureLead(createWorld.cityId(), createWorld.teamId(),
                createWorld.owner().userId(), "O4 shortlist create");
        long createListing = fixtureProperty(
                createWorld.cityId(), createWorld.localityId(), "O4 create public home");
        makePropertyIneligible(createListing);
        UUID createOperation = UUID.randomUUID();
        String createBefore = o4State(createLead);
        HttpResponse<String> create = send("POST",
                "/api/v1/operations/leads/" + createLead + "/shortlists",
                createWorld.owner().token(),
                shortlistCommand(leadVersion(createLead), List.of(createListing)),
                "Idempotency-Key", createOperation.toString());
        assertEquals(409, create.statusCode(), create.body());
        assertTrue(create.body().contains(
                "Public property " + createListing + " is no longer available; refresh the shortlist"));
        assertEquals(createBefore, o4State(createLead));
        assertEquals("absent", receiptState(
                createOperation, "LEAD_SHORTLIST_CREATE"));

        World updateWorld = world("o4-shortlist-update");
        long updateLead = fixtureLead(updateWorld.cityId(), updateWorld.teamId(),
                updateWorld.owner().userId(), "O4 shortlist update");
        long firstListing = fixtureProperty(
                updateWorld.cityId(), updateWorld.localityId(), "O4 update public one");
        long secondListing = fixtureProperty(
                updateWorld.cityId(), updateWorld.localityId(), "O4 update public two");
        HttpResponse<String> initial = send("POST",
                "/api/v1/operations/leads/" + updateLead + "/shortlists",
                updateWorld.owner().token(),
                shortlistCommand(leadVersion(updateLead),
                        List.of(firstListing, secondListing)),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, initial.statusCode(), initial.body());
        JsonNode initialView = mapper.readTree(initial.body());
        makePropertyIneligible(secondListing);
        String updateBefore = o4State(updateLead);
        HttpResponse<String> update = send("PUT",
                "/api/v1/operations/leads/" + updateLead + "/shortlists/"
                        + initialView.path("id").asLong(),
                updateWorld.owner().token(),
                "{\"expectedLeadVersion\":" + leadVersion(updateLead)
                        + ",\"expectedShortlistVersion\":"
                        + initialView.path("version").asLong()
                        + ",\"listingIds\":[" + secondListing + "," + firstListing + "]}");
        assertEquals(409, update.statusCode(), update.body());
        assertTrue(update.body().contains(
                "Public property " + secondListing + " is no longer available; refresh the shortlist"));
        assertEquals(updateBefore, o4State(updateLead));

        World shareWorld = world("o4-share-prepare-ineligible");
        long shareLead = fixtureLead(shareWorld.cityId(), shareWorld.teamId(),
                shareWorld.owner().userId(), "O4 share prepare ineligible");
        long shareListing = fixtureProperty(
                shareWorld.cityId(), shareWorld.localityId(), "O4 prepare public home");
        HttpResponse<String> consent = send("POST",
                "/api/v1/operations/leads/" + shareLead + "/contact-preferences",
                shareWorld.owner().token(),
                contactBody(leadVersion(shareLead), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, consent.statusCode(), consent.body());
        JsonNode preference = mapper.readTree(consent.body());
        HttpResponse<String> shortlist = send("POST",
                "/api/v1/operations/leads/" + shareLead + "/shortlists",
                shareWorld.owner().token(),
                "{\"expectedLeadVersion\":" + preference.path("leadVersion").asLong()
                        + ",\"listingIds\":[" + shareListing + "]}",
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlist.statusCode(), shortlist.body());
        JsonNode shortlistView = mapper.readTree(shortlist.body());
        makePropertyIneligible(shareListing);
        UUID shareOperation = UUID.randomUUID();
        String shareBefore = o4State(shareLead);
        HttpResponse<String> prepare = send("POST",
                "/api/v1/operations/leads/" + shareLead + "/shares",
                shareWorld.owner().token(),
                shareBody(shareOperation, shortlistView.path("leadVersion").asLong(),
                        shortlistView.path("id").asLong(),
                        shortlistView.path("version").asLong(),
                        preference.path("revision").asLong()));
        assertEquals(409, prepare.statusCode(), prepare.body());
        assertTrue(prepare.body().contains(
                "Public property " + shareListing + " is no longer available; refresh the shortlist"));
        assertEquals(shareBefore, o4State(shareLead));
        assertEquals("absent", receiptState(shareOperation, "LEAD_SHARE_PREPARE"));
    }

    @Test
    void preparedShareRejectsChangedFactsAndReprepareUsesCurrentSnapshotOnce()
            throws Exception {
        World world = world("o4-stale-facts");
        long listingId = fixtureProperty(
                world.cityId(), world.localityId(), "O4 changing public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "O4 stale facts");
        LeadPublicPropertyService.EligibleProperty priorFacts =
                currentEligibleProperty(setup.shareId(), listingId);
        String priorShare = shareState(setup.shareId());

        assertEquals(1, fixture.update(
                "update rental_details set monthly_rent=23000.00 where id=?", listingId));
        installPropertyGuardBypassMutant(priorFacts);
        String beforeStaleActions = o4State(setup.leadId());
        for (String eventType : List.of("CLIPBOARD_COPIED", "HANDOFF_ATTEMPTED")) {
            HttpResponse<String> stale = send("POST",
                    "/api/v1/operations/leads/" + setup.leadId()
                            + "/shares/" + setup.shareId() + "/events",
                    world.owner().token(),
                    "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\""
                            + eventType + "\"}");
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains(
                    "Public property facts changed after this share was prepared; prepare a refreshed share"));
        }
        assertEquals(beforeStaleActions, o4State(setup.leadId()));
        assertEquals(priorShare, shareState(setup.shareId()));

        JsonNode staleHistory = historyItem(
                world.owner(), setup.leadId(), setup.shareId());
        assertFalse(staleHistory.path("currentFactsMatch").asBoolean());
        assertFalse(staleHistory.path("actionReady").asBoolean());
        assertTrue(staleHistory.path("previewText").asText().contains("Rent: INR 18,000 / month"));
        assertFalse(staleHistory.path("previewText").asText().contains("Rent: INR 23,000 / month"));

        UUID reprepareOperation = UUID.randomUUID();
        String reprepareBody = shareBody(reprepareOperation,
                leadVersion(setup.leadId()), setup.shortlist().path("id").asLong(),
                setup.shortlist().path("version").asLong(),
                setup.preference().path("revision").asLong());
        HttpResponse<String> reprepared = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), reprepareBody);
        assertEquals(201, reprepared.statusCode(), reprepared.body());
        UUID refreshedShareId =
                UUID.fromString(mapper.readTree(reprepared.body()).path("id").asText());
        assertFalse(refreshedShareId.equals(setup.shareId()));
        assertTrue(reprepared.body().contains("Rent: INR 23,000 / month"));
        String repeatedReceipt = receiptState(
                reprepareOperation, "LEAD_SHARE_PREPARE");
        assertTrue(repeatedReceipt.contains(refreshedShareId.toString()));
        String afterReprepare = o4State(setup.leadId());

        HttpResponse<String> replay = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), reprepareBody);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(refreshedShareId,
                UUID.fromString(mapper.readTree(replay.body()).path("id").asText()));
        assertEquals(afterReprepare, o4State(setup.leadId()));
        assertEquals(repeatedReceipt, receiptState(
                reprepareOperation, "LEAD_SHARE_PREPARE"));
        assertEquals(priorShare, shareState(setup.shareId()));

        HttpResponse<String> currentCopy = send("POST",
                "/api/v1/operations/leads/" + setup.leadId()
                        + "/shares/" + refreshedShareId + "/events",
                world.owner().token(),
                "{\"eventId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"CLIPBOARD_COPIED\"}");
        assertEquals(200, currentCopy.statusCode(), currentCopy.body());
        JsonNode history = mapper.readTree(send("GET",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares?page=0&size=10",
                world.owner().token(), null).body());
        assertEquals(2, history.path("totalElements").asInt());
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from lead_phone_delivery_outbox", Long.class));
    }

    @Test
    void concurrentPropertyUnpublishWinsListingLockAndForcesRuntimeRefusal()
            throws Exception {
        World world = world("o4-property-lock");
        long listingId = fixtureProperty(
                world.cityId(), world.localityId(), "O4 locked public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "O4 property lock");
        long initialListingVersion = fixture.queryForObject(
                "select version from listings where id=?", Long.class, listingId);
        String stateBefore = o4State(setup.leadId());

        try (Connection propertyUpdate = fixture.getDataSource().getConnection()) {
            propertyUpdate.setAutoCommit(false);
            long blockerPid = scalarOn(propertyUpdate, "select pg_backend_pid()");
            try (var lock = propertyUpdate.prepareStatement(
                    "select id from listings where id=? for update")) {
                lock.setLong(1, listingId);
                lock.executeQuery().close();
            }
            UUID eventId = UUID.randomUUID();
            CompletableFuture<HttpResponse<String>> handoff =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return send("POST",
                                    "/api/v1/operations/leads/" + setup.leadId()
                                            + "/shares/" + setup.shareId() + "/events",
                                    world.owner().token(),
                                    "{\"eventId\":\"" + eventId
                                            + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
                        } catch (Exception exception) {
                            throw new CompletionException(exception);
                        }
                    });
            ListingWaiter waiter = awaitCorrelatedListingWaiter(
                    blockerPid, "%listings%for update%", handoff);
            assertFalse(handoff.isDone());
            assertEquals(1, updateOn(propertyUpdate,
                    "update listings set workflow_status='ARCHIVED', version=version+1 "
                            + "where id=?", listingId));
            assertTrue(booleanOn(propertyUpdate,
                    "select workflow_status='ARCHIVED' and version=? from listings where id=?",
                    initialListingVersion + 1, listingId));
            assertFalse(handoff.isDone(),
                    "The HTTP action resumed before the property update committed");
            propertyUpdate.commit();

            HttpResponse<String> refused = handoff.get(20, TimeUnit.SECONDS);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains(
                    "Public property " + listingId
                            + " is no longer available; refresh the shortlist"));
            assertTrue(waiter.query().toLowerCase().contains("listings"));
            assertTrue(waiter.query().toLowerCase().contains("for update"));
            assertTrue(waiter.pid() > 0);
        }

        assertEquals("ARCHIVED", fixture.queryForObject(
                "select workflow_status from listings where id=?",
                String.class, listingId));
        assertEquals(initialListingVersion + 1, fixture.queryForObject(
                "select version from listings where id=?", Long.class, listingId));
        assertEquals(stateBefore, o4State(setup.leadId()));
    }

    @Test
    void populatedShortlistAndLocalityReplacementUseOnlyTheApprovedChildDeletes()
            throws Exception {
        World shortlistWorld = world("populated-shortlist");
        long firstListing = fixtureProperty(
                shortlistWorld.cityId(), shortlistWorld.localityId(), "Populated shortlist one");
        long secondListing = fixtureProperty(
                shortlistWorld.cityId(), shortlistWorld.localityId(), "Populated shortlist two");
        long leadId = fixtureLead(
                shortlistWorld.cityId(), shortlistWorld.teamId(),
                shortlistWorld.owner().userId(), "Populated shortlist");

        JsonNode created = mapper.readTree(send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                shortlistWorld.owner().token(),
                "{\"expectedLeadVersion\":" + leadVersion(leadId)
                        + ",\"listingIds\":[" + firstListing + "," + secondListing + "]}",
                "Idempotency-Key", UUID.randomUUID().toString()).body());
        long shortlistId = created.path("id").asLong();
        assertEquals(List.of(firstListing, secondListing), fixture.queryForList(
                "select listing_id from lead_shortlist_items "
                        + "where shortlist_id=? order by position",
                Long.class, shortlistId));

        HttpResponse<String> reordered = send("PUT",
                "/api/v1/operations/leads/" + leadId + "/shortlists/" + shortlistId,
                shortlistWorld.owner().token(),
                "{\"expectedLeadVersion\":" + created.path("leadVersion").asLong()
                        + ",\"expectedShortlistVersion\":"
                        + created.path("version").asLong()
                        + ",\"listingIds\":[" + secondListing + "," + firstListing + "]}");
        assertEquals(200, reordered.statusCode(), reordered.body());
        JsonNode reorderedView = mapper.readTree(reordered.body());
        assertEquals(List.of(secondListing, firstListing), fixture.queryForList(
                "select listing_id from lead_shortlist_items "
                        + "where shortlist_id=? order by position",
                Long.class, shortlistId));

        HttpResponse<String> cleared = send("PUT",
                "/api/v1/operations/leads/" + leadId + "/shortlists/" + shortlistId,
                shortlistWorld.owner().token(),
                "{\"expectedLeadVersion\":" + reorderedView.path("leadVersion").asLong()
                        + ",\"expectedShortlistVersion\":"
                        + reorderedView.path("version").asLong()
                        + ",\"listingIds\":[]}");
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from lead_shortlist_items where shortlist_id=?",
                Long.class, shortlistId));

        World localityWorld = world("populated-localities");
        String localitySuffix = UUID.randomUUID().toString().substring(0, 8);
        long secondLocality = insertReturningLong(
                "insert into localities(city,sector_name,supported_city_id) "
                        + "values (?,?,?) returning id",
                "DB A01 second locality " + localitySuffix,
                "Synthetic second " + localitySuffix,
                localityWorld.cityId());
        long localityLead = fixtureLead(
                localityWorld.cityId(), localityWorld.teamId(),
                localityWorld.owner().userId(), "Populated localities");
        HttpResponse<String> populated = send("PATCH",
                "/api/v1/operations/leads/" + localityLead,
                localityWorld.owner().token(),
                "{\"expectedVersion\":" + leadVersion(localityLead)
                        + ",\"changes\":{\"preferredLocalityIds\":["
                        + localityWorld.localityId() + "," + secondLocality + "]}}");
        assertEquals(200, populated.statusCode(), populated.body());
        assertEquals(List.of(localityWorld.localityId(), secondLocality),
                fixture.queryForList(
                        "select locality_id from lead_preferred_localities "
                                + "where lead_id=? order by locality_id",
                        Long.class, localityLead));

        long staleVersion = leadVersion(localityLead) - 1;
        List<Long> beforeStale = fixture.queryForList(
                "select locality_id from lead_preferred_localities "
                        + "where lead_id=? order by locality_id",
                Long.class, localityLead);
        HttpResponse<String> stale = send("PATCH",
                "/api/v1/operations/leads/" + localityLead,
                localityWorld.owner().token(),
                "{\"expectedVersion\":" + staleVersion
                        + ",\"changes\":{\"preferredLocalityIds\":["
                        + secondLocality + "]}}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(beforeStale, fixture.queryForList(
                "select locality_id from lead_preferred_localities "
                        + "where lead_id=? order by locality_id",
                Long.class, localityLead));

        HttpResponse<String> clearedLocalities = send("PATCH",
                "/api/v1/operations/leads/" + localityLead,
                localityWorld.owner().token(),
                "{\"expectedVersion\":" + leadVersion(localityLead)
                        + ",\"changes\":{\"preferredLocalityIds\":[]}}");
        assertEquals(200, clearedLocalities.statusCode(), clearedLocalities.body());
        assertEquals(0L, fixture.queryForObject(
                "select count(*) from lead_preferred_localities where lead_id=?",
                Long.class, localityLead));
    }

    @Test
    void shortlistReplayPreservesReadableReplayButRequiresEditabilityForNewWrites()
            throws Exception {
        World world = world("shortlist-readable-replay");
        long firstListing = fixtureProperty(
                world.cityId(), world.localityId(), "Readable replay first property");
        long secondListing = fixtureProperty(
                world.cityId(), world.localityId(), "Readable replay second property");
        Actor actor = staff("readable-replay-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, world.teamId(), null, null),
                new GrantSpec("OPS_SUPERVISE", "TEAM", null, world.teamId(), null, null));
        long leadId = fixtureLead(
                world.cityId(), world.teamId(), actor.userId(), "Readable replay Lead");
        UUID operationId = UUID.randomUUID();
        String original = shortlistCommand(leadVersion(leadId), List.of(firstListing));
        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), original, "Idempotency-Key", operationId.toString());
        assertEquals(201, created.statusCode(), created.body());
        long shortlistId = mapper.readTree(created.body()).path("id").asLong();
        String changed = shortlistCommand(leadVersion(leadId), List.of(secondListing));

        HttpResponse<String> ownerChanged = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), changed, "Idempotency-Key", operationId.toString());
        assertEquals(409, ownerChanged.statusCode(), ownerChanged.body());

        fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),"
                        + "revoked_by_user_id=?, revoke_reason_code='DB_A01_READ_ONLY_REPLAY' "
                        + "where id=? and revoked_at is null",
                actor.userId(), actor.grantIds().get(0));
        long shortlistsBefore = fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?", Long.class, leadId);
        long activitiesBefore = fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?", Long.class, leadId);
        long receiptsBefore = fixture.queryForObject(
                "select count(*) from lead_command_receipts "
                        + "where actor_user_id=? and action='LEAD_SHORTLIST_CREATE'",
                Long.class, actor.userId());

        HttpResponse<String> readOnlyChanged = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), changed, "Idempotency-Key", operationId.toString());
        assertEquals(409, readOnlyChanged.statusCode(), readOnlyChanged.body());
        HttpResponse<String> readOnlyExact = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), original, "Idempotency-Key", operationId.toString());
        assertEquals(201, readOnlyExact.statusCode(), readOnlyExact.body());
        assertEquals(shortlistId, mapper.readTree(readOnlyExact.body()).path("id").asLong());
        HttpResponse<String> readOnlyNewWrite = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), changed, "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(404, readOnlyNewWrite.statusCode(), readOnlyNewWrite.body());
        assertEquals(shortlistsBefore, fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?", Long.class, leadId));
        assertEquals(activitiesBefore, fixture.queryForObject(
                "select count(*) from lead_activities where lead_id=?", Long.class, leadId));
        assertEquals(receiptsBefore, fixture.queryForObject(
                "select count(*) from lead_command_receipts "
                        + "where actor_user_id=? and action='LEAD_SHORTLIST_CREATE'",
                Long.class, actor.userId()));

        fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),"
                        + "revoked_by_user_id=?, revoke_reason_code='DB_A01_REPLAY_SCOPE_LOSS' "
                        + "where id=? and revoked_at is null",
                actor.userId(), actor.grantIds().get(1));
        HttpResponse<String> concealedChanged = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), changed, "Idempotency-Key", operationId.toString());
        HttpResponse<String> concealedExact = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                actor.token(), original, "Idempotency-Key", operationId.toString());
        assertEquals(403, concealedChanged.statusCode(), concealedChanged.body());
        assertEquals(403, concealedExact.statusCode(), concealedExact.body());
        assertFalse(concealedChanged.body().contains("different shortlist request"));
        assertFalse(concealedExact.body().contains("\"listingIds\""));
    }

    @Test
    void shortlistPayloadConflictRequiresCityIntakeReadabilityBeforeDisclosure()
            throws Exception {
        World world = world("shortlist-intake-replay");
        long listing = fixtureProperty(
                world.cityId(), world.localityId(), "City intake replay property");
        long leadId = fixtureLead(
                world.cityId(), null, null, "City intake replay Lead");
        UUID operationId = UUID.randomUUID();
        String original = shortlistCommand(leadVersion(leadId), List.of(listing));
        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.intake().token(), original, "Idempotency-Key", operationId.toString());
        assertEquals(201, created.statusCode(), created.body());
        String changed = original.replace("[" + listing + "]", "[]");

        HttpResponse<String> intakeChanged = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.intake().token(), changed, "Idempotency-Key", operationId.toString());
        assertEquals(409, intakeChanged.statusCode(), intakeChanged.body());

        fixture.update("update lead_routing_queue set owner_user_id=?, operating_team_id=? "
                        + "where id=?",
                world.owner().userId(), world.teamId(), leadId);
        long before = fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?", Long.class, leadId);
        HttpResponse<String> concealedChanged = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.intake().token(), changed, "Idempotency-Key", operationId.toString());
        HttpResponse<String> concealedExact = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.intake().token(), original, "Idempotency-Key", operationId.toString());
        assertEquals(404, concealedChanged.statusCode(), concealedChanged.body());
        assertEquals(404, concealedExact.statusCode(), concealedExact.body());
        assertFalse(concealedChanged.body().contains("different shortlist request"));
        assertFalse(concealedExact.body().contains("\"listingIds\""));
        assertEquals(before, fixture.queryForObject(
                "select count(*) from lead_shortlists where lead_id=?", Long.class, leadId));
    }

    @Test
    void shortlistReceiptReplayConcealsEveryCurrentAuthorityLossClass()
            throws Exception {
        World expiryWorld = world("shortlist-expiry-replay");
        long expiryListing = fixtureProperty(
                expiryWorld.cityId(), expiryWorld.localityId(), "Expiry replay property");
        Actor expiringOwner = staff(
                "shortlist-expiring-owner-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, expiryWorld.teamId(),
                        Instant.now().minus(1, ChronoUnit.HOURS),
                        Instant.now().plus(8, ChronoUnit.SECONDS)));
        long expiryLead = fixtureLead(
                expiryWorld.cityId(), expiryWorld.teamId(), expiringOwner.userId(),
                "Shortlist expiring receipt");
        UUID expiryOperation = UUID.randomUUID();
        String expiryOriginal = shortlistCommand(
                leadVersion(expiryLead), List.of(expiryListing));
        assertEquals(201, send("POST",
                "/api/v1/operations/leads/" + expiryLead + "/shortlists",
                expiringOwner.token(), expiryOriginal,
                "Idempotency-Key", expiryOperation.toString()).statusCode());
        while (Boolean.FALSE.equals(fixture.queryForObject(
                "select clock_timestamp()>=expires_at from staff_access_grants where id=?",
                Boolean.class, expiringOwner.grantIds().get(0)))) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        HttpResponse<String> expiredChanged = send("POST",
                "/api/v1/operations/leads/" + expiryLead + "/shortlists",
                expiringOwner.token(), expiryOriginal.replace("[" + expiryListing + "]", "[]"),
                "Idempotency-Key", expiryOperation.toString());
        HttpResponse<String> expiredExact = send("POST",
                "/api/v1/operations/leads/" + expiryLead + "/shortlists",
                expiringOwner.token(), expiryOriginal,
                "Idempotency-Key", expiryOperation.toString());
        assertEquals(403, expiredChanged.statusCode(), expiredChanged.body());
        assertEquals(403, expiredExact.statusCode(), expiredExact.body());
        assertFalse(expiredChanged.body().contains("different shortlist request"));

        World profileWorld = world("shortlist-profile-replay");
        long profileListing = fixtureProperty(
                profileWorld.cityId(), profileWorld.localityId(), "Profile replay property");
        assertShortlistReceiptConcealedAfterAuthorityLoss(
                profileWorld, profileListing,
                "update employee_profiles set staff_active=false where user_id=?",
                profileWorld.owner().userId());

        World teamWorld = world("shortlist-team-replay");
        long teamListing = fixtureProperty(
                teamWorld.cityId(), teamWorld.localityId(), "Team replay property");
        assertShortlistReceiptConcealedAfterAuthorityLoss(
                teamWorld, teamListing,
                "update operating_teams set active=false where id=?",
                teamWorld.teamId());

        World cityWorld = world("shortlist-city-replay");
        long cityListing = fixtureProperty(
                cityWorld.cityId(), cityWorld.localityId(), "City replay property");
        assertShortlistReceiptConcealedAfterAuthorityLoss(
                cityWorld, cityListing,
                "update supported_cities set active=false where id=?",
                cityWorld.cityId());
    }

    @Test
    void crossLeadReceiptCannotDiscloseConflictAfterReassignment() throws Exception {
        World w = world("cross-receipt");
        long property = fixtureProperty(w.cityId(), w.localityId(), "Receipt binding property");
        long a = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "A receipt");
        long b = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "B readable");
        String body = shortlistCommand(leadVersion(a), List.of(property));
        UUID key = UUID.randomUUID();
        String pathA = "/api/v1/operations/leads/" + a + "/shortlists";
        String pathB = "/api/v1/operations/leads/" + b + "/shortlists";
        HttpResponse<String> created = send("POST", pathA, w.owner().token(), body,
                "Idempotency-Key", key.toString());
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(201, send("POST", pathA, w.owner().token(), body,
                "Idempotency-Key", key.toString()).statusCode());
        // Both readable: existing authorized conflict, never return A as B.
        assertEquals(409, send("POST", pathB, w.owner().token(), body,
                "Idempotency-Key", key.toString()).statusCode());
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?", w.intake().userId(), b);
        assertEquals(201, send("POST", pathA,w.owner().token(),body,"Idempotency-Key",key.toString()).statusCode());
        for (UUID requestKey : List.of(key,UUID.randomUUID())) {
            assertConcealed(send("POST",pathB,w.owner().token(),body,"Idempotency-Key",requestKey.toString()),404);
        }
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?", w.owner().userId(), b);
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?",
                w.intake().userId(), a);
        assertEquals(200, send("GET", "/api/v1/operations/leads/" + b,
                w.owner().token(), null).statusCode());
        Snapshot beforeA = sensitiveSnapshot(a), beforeB = sensitiveSnapshot(b);
        long lists = scalar("select count(*) from lead_shortlists");
        long receipts = scalar("select count(*) from lead_command_receipts");
        for (String payload : List.of(body, shortlistCommand(0, List.of()),
                shortlistCommand(0, List.of(property + 1000000)))) {
            for (String path : List.of(pathA, pathB)) {
                HttpResponse<String> denied = send("POST", path, w.owner().token(), payload,
                        "Idempotency-Key", key.toString());
                assertConcealed(denied, 404);
            }
        }
        assertEquals(beforeA, sensitiveSnapshot(a));
        assertEquals(beforeB, sensitiveSnapshot(b));
        assertEquals(lists, scalar("select count(*) from lead_shortlists"));
        assertEquals(receipts, scalar("select count(*) from lead_command_receipts"));
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?",w.intake().userId(),b);
        assertConcealed(send("POST",pathB,w.owner().token(),body,"Idempotency-Key",key.toString()),404);
    }

    @Test
    void crossLeadReceiptConcealsScopeLossWhileOtherCityRemainsReadable() throws Exception {
        for (String loss : List.of("revoke", "expiry", "team", "city", "transfer", "legacy")) {
            World aWorld = world("receipt-a-" + loss), bWorld = world("receipt-b-" + loss);
            Instant expiry = loss.equals("expiry") ? Instant.now().plusSeconds(3) : null;
            Actor actor = staff("receipt-multi-" + loss,
                    new GrantSpec("OPS_COORDINATE", "TEAM", null, aWorld.teamId(), null, expiry),
                    new GrantSpec("OPS_COORDINATE", "TEAM", null, bWorld.teamId(), null, null));
            long a = fixtureLead(aWorld.cityId(), aWorld.teamId(), actor.userId(), "Receipt A");
            long b = fixtureLead(bWorld.cityId(), bWorld.teamId(), actor.userId(), "Readable B");
            long listing = fixtureProperty(aWorld.cityId(), aWorld.localityId(), "A property");
            String body = shortlistCommand(leadVersion(a), List.of(listing));
            UUID key = UUID.randomUUID();
            assertEquals(201, send("POST", "/api/v1/operations/leads/" + a + "/shortlists",
                    actor.token(), body, "Idempotency-Key", key.toString()).statusCode());
            assertEquals(201,send("POST","/api/v1/operations/leads/"+a+"/shortlists",actor.token(),body,"Idempotency-Key",key.toString()).statusCode());
            assertEquals(409,send("POST","/api/v1/operations/leads/"+b+"/shortlists",actor.token(),body,"Idempotency-Key",key.toString()).statusCode());
            switch (loss) {
                case "revoke" -> fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='DB_A01_BINDING' where id=?", actor.userId(), actor.grantIds().get(0));
                case "expiry" -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!Boolean.TRUE.equals(fixture.queryForObject("select clock_timestamp()>=expires_at from staff_access_grants where id=?", Boolean.class, actor.grantIds().get(0)))) {
                        assertTrue(System.nanoTime() < deadline); TimeUnit.MILLISECONDS.sleep(25);
                    }
                }
                case "team" -> fixture.update("update operating_teams set active=false where id=?", aWorld.teamId());
                case "city" -> fixture.update("update supported_cities set active=false where id=?", aWorld.cityId());
                case "transfer" -> fixture.update("update lead_routing_queue set supported_city_id=?,operating_team_id=?,owner_user_id=? where id=?", bWorld.cityId(), bWorld.teamId(), bWorld.owner().userId(), a);
                case "legacy" -> fixture.update("update lead_routing_queue set legacy_provenance='LEGACY_UNVERIFIED' where id=?", a);
            }
            assertEquals(200, send("GET", "/api/v1/operations/leads/" + b, actor.token(), null).statusCode());
            Snapshot before = sensitiveSnapshot(a);
            for (String payload : List.of(body, shortlistCommand(0, List.of()))) {
                assertConcealed(send("POST", "/api/v1/operations/leads/" + b + "/shortlists",
                        actor.token(), payload, "Idempotency-Key", key.toString()), 404);
            }
            assertEquals(before, sensitiveSnapshot(a));
        }
    }

    @Test
    void receiptBindingRejectsCorruptAndMissingReferencesBeforeHashComparison() throws Exception {
        World w = world("receipt-corrupt");
        long a = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "Receipt A");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Receipt property");
        for (String ref : java.util.Arrays.asList(null, "", "garbage", "-1", "0", "9223372036854775808", "999999999")) {
            UUID key = UUID.randomUUID();
            fixture.update("insert into lead_command_receipts(id,actor_user_id,operation_id,action,payload_hash,result_reference,completed_at) values (?,?,?,'LEAD_SHORTLIST_CREATE',?,?,clock_timestamp())",
                    UUID.randomUUID(), w.owner().userId(), key, "0".repeat(64), ref);
            Snapshot before = sensitiveSnapshot(a);
            assertConcealed(send("POST", "/api/v1/operations/leads/" + a + "/shortlists",
                    w.owner().token(), shortlistCommand(0, List.of(listing)),
                    "Idempotency-Key", key.toString()), 404);
            assertEquals(before, sensitiveSnapshot(a));
        }
    }

    @Test
    void concurrentReceiptRequestsSerializeAndReauthorizeAfterOperationWait() throws Exception {
        World w = world("receipt-serial");
        long a = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "Serial A");
        long b = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "Serial B");
        long property = fixtureProperty(w.cityId(), w.localityId(), "Serial property");
        UUID key = UUID.randomUUID();
        String body = shortlistCommand(leadVersion(a), List.of(property));
        String path = "/api/v1/operations/leads/" + a + "/shortlists";
        var first = CompletableFuture.supplyAsync(() -> uncheckedShortlist(path, w.owner(), body, key));
        var second = CompletableFuture.supplyAsync(() -> uncheckedShortlist(path, w.owner(), body, key));
        var one = first.get(15, TimeUnit.SECONDS); var two = second.get(15, TimeUnit.SECONDS);
        assertEquals(201, one.statusCode(), one.body()); assertEquals(201, two.statusCode(), two.body());
        assertEquals(mapper.readTree(one.body()).path("id"), mapper.readTree(two.body()).path("id"));
        assertEquals(1, scalar("select count(*) from lead_shortlists where lead_id=?", a));
        String keyText = w.owner().userId() + ":LEAD_SHORTLIST_CREATE:" + key;
        try (Connection lock = DbA01LeadTestTarget.requireFromEnvironment().openRuntime()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, keyText); statement.execute();
            }
            var waiting = CompletableFuture.supplyAsync(() -> uncheckedShortlist(
                    "/api/v1/operations/leads/" + b + "/shortlists", w.owner(), body, key));
            awaitOperationWait(lock, waiting);
            fixture.update("update lead_routing_queue set owner_user_id=? where id=?", w.intake().userId(), a);
            lock.commit();
            assertConcealed(waiting.get(15, TimeUnit.SECONDS), 404);
        }
    }

    @Test
    void shortlistFailureRollbackAndActorActionUuidIsolation() throws Exception {
        World w = world("receipt-rollback");
        long a = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "Rollback A");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Rollback property");
        UUID key = UUID.randomUUID(); String body = shortlistCommand(leadVersion(a), List.of(listing));
        String path = "/api/v1/operations/leads/" + a + "/shortlists";
        fixture.update("insert into public.db_a01_activity_failure(lead_id,fail) values (?,true)", a);
        Snapshot before = sensitiveSnapshot(a);
        String exactBefore = shortlistState(a);
        long count = scalar("select count(*) from lead_command_receipts");
        assertEquals(500, send("POST", path, w.owner().token(), body, "Idempotency-Key", key.toString()).statusCode());
        assertEquals(before, sensitiveSnapshot(a));
        assertEquals(exactBefore, shortlistState(a));
        assertEquals(0, scalar("select count(*) from lead_shortlists where lead_id=?", a));
        assertEquals(0, scalar("select count(*) from lead_shortlist_items i join lead_shortlists s on s.id=i.shortlist_id where s.lead_id=?", a));
        assertEquals(count, scalar("select count(*) from lead_command_receipts"));
        fixture.update("update public.db_a01_activity_failure set fail=false where lead_id=?", a);
        // Same UUID under another actor/action cannot shadow this actor's create.
        fixture.update("insert into lead_command_receipts(id,actor_user_id,operation_id,action,payload_hash,result_reference,completed_at) values (?,?,?,'LEAD_SHARE_PREPARE',?,'bad',clock_timestamp()),(?,?,?,'LEAD_SHORTLIST_CREATE',?,'bad',clock_timestamp())",
                UUID.randomUUID(), w.owner().userId(), key, "0".repeat(64), UUID.randomUUID(), w.intake().userId(), key, "0".repeat(64));
        LostResponse saved = sendWithoutClientAcknowledgement("POST", path, w.owner().token(), body,
                "Idempotency-Key", key.toString());
        assertEquals(-1, saved.clientRead()); assertTrue(saved.statusLine().contains("201"));
        var replayed = send("POST", path, w.owner().token(), body, "Idempotency-Key", key.toString());
        assertEquals(201, replayed.statusCode(), replayed.body());
        long shortlistId = mapper.readTree(replayed.body()).path("id").asLong();
        assertEquals(200, send("GET",path+"/"+shortlistId,w.owner().token(),null).statusCode());
        assertEquals(1, scalar("select count(*) from lead_shortlists where lead_id=?", a));
    }

    @Test
    void matchingReadableGrantSurvivesRevocationAndCityReadOnlyReplayIsPreserved() throws Exception {
        World w=world("remaining-match"), other=world("remaining-other");
        Actor actor=staff("matching-reader",
                new GrantSpec("OPS_COORDINATE","TEAM",null,w.teamId(),null,null),
                new GrantSpec("OPS_SUPERVISE","TEAM",null,w.teamId(),null,null),
                new GrantSpec("OPS_COORDINATE","TEAM",null,other.teamId(),null,null));
        long a=fixtureLead(w.cityId(),w.teamId(),actor.userId(),"Matching A");
        long b=fixtureLead(other.cityId(),other.teamId(),actor.userId(),"Matching B");
        UUID key=UUID.randomUUID();String body=shortlistCommand(0,List.of());
        String path="/api/v1/operations/leads/"+a+"/shortlists";
        assertEquals(201,send("POST",path,actor.token(),body,"Idempotency-Key",key.toString()).statusCode());
        for (int index=0;index<2;index++) {
            fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='DB_A01_MATCHING' where id=?",actor.userId(),actor.grantIds().get(index));
            if(index==0) {
                assertEquals(201,send("POST",path,actor.token(),body,"Idempotency-Key",key.toString()).statusCode());
                assertEquals(409,send("POST",path,actor.token(),shortlistCommand(9,List.of()),"Idempotency-Key",key.toString()).statusCode());
                assertConcealed(send("POST",path,actor.token(),body,"Idempotency-Key",UUID.randomUUID().toString()),404);
            } else assertConcealed(send("POST","/api/v1/operations/leads/"+b+"/shortlists",actor.token(),body,"Idempotency-Key",key.toString()),404);
        }
        Actor city=staff("city-readonly",new GrantSpec("OPS_INTAKE","CITY",w.cityId(),null,null,null),
                new GrantSpec("CITY_TEAM_ADMIN","CITY",w.cityId(),null,null,null));
        long unassigned=fixtureLead(w.cityId(),null,null,"Readable city Lead");
        String cityPath="/api/v1/operations/leads/"+unassigned+"/shortlists"; UUID cityKey=UUID.randomUUID();
        assertEquals(201,send("POST",cityPath,city.token(),body,"Idempotency-Key",cityKey.toString()).statusCode());
        fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='DB_A01_CITY_READONLY' where id=?",city.userId(),city.grantIds().get(0));
        assertEquals(201,send("POST",cityPath,city.token(),body,"Idempotency-Key",cityKey.toString()).statusCode());
        assertEquals(409,send("POST",cityPath,city.token(),shortlistCommand(9,List.of()),"Idempotency-Key",cityKey.toString()).statusCode());
        assertConcealed(send("POST",cityPath,city.token(),body,"Idempotency-Key",UUID.randomUUID().toString()),404);
    }

    private String shortlistState(long lead) {
        return fixture.queryForObject("select jsonb_build_object('lead',(select to_jsonb(l) from lead_routing_queue l where id=?),'lists',(select jsonb_agg(to_jsonb(s) order by s.id) from lead_shortlists s where lead_id=?),'items',(select jsonb_agg(to_jsonb(i) order by i.id) from lead_shortlist_items i join lead_shortlists s on s.id=i.shortlist_id where s.lead_id=?),'activity',(select jsonb_agg(to_jsonb(a) order by a.id) from lead_activities a where lead_id=?),'audit',(select jsonb_agg(to_jsonb(a) order by a.id) from operational_audit_events a where target_type='LEAD' and target_id=?))::text",
                String.class, lead,lead,lead,lead,lead);
    }

    @Test
    void receiptGrantExpiryDuringCorrelatedOperationWaitConcealsStoredLead() throws Exception {
        World aWorld=world("op-expiry-a"), bWorld=world("op-expiry-b");
        Actor actor=staff("op-expiry",new GrantSpec("OPS_COORDINATE","TEAM",null,aWorld.teamId(),null,Instant.now().plusSeconds(4)),
                new GrantSpec("OPS_COORDINATE","TEAM",null,bWorld.teamId(),null,null));
        long a=fixtureLead(aWorld.cityId(),aWorld.teamId(),actor.userId(),"Expiry A");
        long b=fixtureLead(bWorld.cityId(),bWorld.teamId(),actor.userId(),"Expiry B");
        long listing=fixtureProperty(aWorld.cityId(),aWorld.localityId(),"Expiry home");
        UUID key=UUID.randomUUID(); String body=shortlistCommand(0,List.of(listing));
        assertEquals(201,send("POST","/api/v1/operations/leads/"+a+"/shortlists",actor.token(),body,"Idempotency-Key",key.toString()).statusCode());
        try (Connection lock=DbA01LeadTestTarget.requireFromEnvironment().openRuntime()) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1,actor.userId()+":LEAD_SHORTLIST_CREATE:"+key); statement.execute();
            }
            var waiting=CompletableFuture.supplyAsync(()->uncheckedShortlist("/api/v1/operations/leads/"+b+"/shortlists",actor,body,key));
            awaitOperationWait(lock,waiting);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(!Boolean.TRUE.equals(fixture.queryForObject("select clock_timestamp()>=expires_at from staff_access_grants where id=?",Boolean.class,actor.grantIds().get(0)))) {
                assertTrue(System.nanoTime()<deadline); Thread.sleep(25);
            }
            assertFalse(waiting.isDone());lock.commit();
            assertConcealed(waiting.get(10,TimeUnit.SECONDS),404);
            assertEquals(200,send("GET","/api/v1/operations/leads/"+b,actor.token(),null).statusCode());
        }
    }

    private void awaitOperationWait(Connection blocker,
            CompletableFuture<HttpResponse<String>> request) throws Exception {
        long pid = scalarOn(blocker, "select pg_backend_pid()");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (request.isDone()) throw new AssertionError("Request did not wait: " + request.get().body());
            Long waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and usename=current_user and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))", Long.class, pid);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Correlated runtime operation lock wait was not observed");
    }

    @Test
    void shortlistValidationAndConcurrentChangedPayloadKeepSingleDurableEffect() throws Exception {
        World w = world("receipt-input");
        long a = fixtureLead(w.cityId(), w.teamId(), w.owner().userId(), "Input A");
        long property = fixtureProperty(w.cityId(), w.localityId(), "Input property");
        String path = "/api/v1/operations/leads/" + a + "/shortlists";
        Snapshot before = sensitiveSnapshot(a);
        for (String body : List.of("{}", "{\"expectedLeadVersion\":0,\"listingIds\":[0]}",
                "{\"expectedLeadVersion\":0,\"listingIds\":[null]}",
                shortlistCommand(0, List.of(property, property)))) {
            var response = send("POST", path, w.owner().token(), body,
                    "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(before, sensitiveSnapshot(a));
        }
        assertEquals(400, send("POST", path, w.owner().token(), shortlistCommand(0,List.of()),
                "Idempotency-Key", "invalid").statusCode());
        assertEquals(409, send("POST", path, w.owner().token(), shortlistCommand(999,List.of(property)),
                "Idempotency-Key", UUID.randomUUID().toString()).statusCode());
        World foreign = world("receipt-foreign");
        long foreignProperty = fixtureProperty(foreign.cityId(), foreign.localityId(), "Foreign property");
        assertEquals(409, send("POST", path, w.owner().token(), shortlistCommand(0,List.of(foreignProperty)),
                "Idempotency-Key", UUID.randomUUID().toString()).statusCode());
        UUID key = UUID.randomUUID(); String original = shortlistCommand(0, List.of(property));
        var created = send("POST", path, w.owner().token(), original, "Idempotency-Key", key.toString());
        assertEquals(201, created.statusCode(), created.body());
        var exact = CompletableFuture.supplyAsync(() -> uncheckedShortlist(path,w.owner(),original,key));
        var changed = CompletableFuture.supplyAsync(() -> uncheckedShortlist(path,w.owner(),shortlistCommand(0,List.of()),key));
        assertEquals(201, exact.get(10,TimeUnit.SECONDS).statusCode());
        assertEquals(409, changed.get(10,TimeUnit.SECONDS).statusCode());
        assertEquals(1, scalar("select count(*) from lead_shortlists where lead_id=?",a));
        long id = mapper.readTree(created.body()).path("id").asLong();
        assertEquals(409, send("PUT", path+"/"+id,w.owner().token(),
                "{\"expectedLeadVersion\":"+leadVersion(a)+",\"expectedShortlistVersion\":999,\"listingIds\":[]}").statusCode());
    }

    @Test
    void adjacentContactAndShareReceiptBindingAudit() throws Exception {
        World w = world("adjacent-binding");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Adjacent property");
        ShareSetup setup = ownerShareSetup(w,listing,"adjacent");
        long b = fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Adjacent B");
        UUID contactKey = UUID.randomUUID();
        String consent = contactBody(leadVersion(setup.leadId()),setup.preference().path("revision").asLong(),
                "SAME","GRANTED");
        assertEquals(201,send("POST","/api/v1/operations/leads/"+setup.leadId()+"/contact-preferences",
                w.owner().token(),consent,"Idempotency-Key",contactKey.toString()).statusCode());
        UUID shareKey=UUID.randomUUID();
        String share=shareBody(shareKey,leadVersion(setup.leadId()),setup.shortlist().path("id").asLong(),
                setup.shortlist().path("version").asLong(),setup.preference().path("revision").asLong()+1);
        assertEquals(201,send("POST","/api/v1/operations/leads/"+setup.leadId()+"/shares",
                w.owner().token(),share).statusCode());
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?",w.intake().userId(),setup.leadId());
        var contact=send("POST","/api/v1/operations/leads/"+b+"/contact-preferences",w.owner().token(),consent,
                "Idempotency-Key",contactKey.toString());
        var prepared=send("POST","/api/v1/operations/leads/"+b+"/shares",w.owner().token(),share);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(404,contact.statusCode(),"Contact receipt A must be concealed: "+contact.body()),
                () -> assertEquals(404,prepared.statusCode(),"Share receipt A must be concealed: "+prepared.body()));
    }

    @Test
    void finalAuditCaptureSourceAndAmbiguousReceiptMustAuthorizeBeforeConflict() throws Exception {
        World w=world("final-receipt-audit");
        long a=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Capture A");
        long b=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Capture B");
        UUID key=UUID.randomUUID();
        for(String action:List.of("LEAD_CAPTURE","LEAD_SOURCE_ATTACH")) {
            fixture.update("insert into lead_command_receipts(id,actor_user_id,operation_id,action,payload_hash,result_reference,completed_at) values (?,?,?,?,?,?,clock_timestamp())",
                    UUID.randomUUID(),w.owner().userId(),key,action,"0".repeat(64),Long.toString(a));
        }
        String capture="{\"cityId\":"+w.cityId()+",\"customerName\":\"Synthetic audit customer\",\"contactPhone\":\"+919876543210\"}";
        String source="{\"expectedVersion\":0,\"eventId\":\""+UUID.randomUUID()+"\",\"decision\":\"ATTACH_ENQUIRY\"}";
        assertEquals(409,send("POST","/api/v1/operations/leads",w.owner().token(),capture,"Idempotency-Key",key.toString()).statusCode());
        assertEquals(409,send("GET","/api/v1/operations/lead-commands/"+key,w.owner().token(),null).statusCode());
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?",w.intake().userId(),a);
        assertEquals(200,send("GET","/api/v1/operations/leads/"+b,w.owner().token(),null).statusCode());
        fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action='LEAD_SOURCE_ATTACH'",Long.toString(b),w.owner().userId(),key);
        assertEquals(200,send("GET","/api/v1/operations/lead-commands/"+key+"?action=LEAD_SOURCE_ATTACH",w.owner().token(),null).statusCode());
        assertConcealed(send("GET","/api/v1/operations/lead-commands/"+key+"?action=LEAD_CAPTURE",w.owner().token(),null),404);
        // Capture/source result reference A must remain hidden while B is readable.
        fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action='LEAD_SOURCE_ATTACH'",Long.toString(a),w.owner().userId(),key);
        var captured=send("POST","/api/v1/operations/leads",w.owner().token(),capture,"Idempotency-Key",key.toString());
        var attached=send("POST","/api/v1/operations/leads/"+b+"/source-events",w.owner().token(),source,"Idempotency-Key",key.toString());
        fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action='LEAD_SOURCE_ATTACH'",Long.toString(b),w.owner().userId(),key);
        var ambiguous=send("GET","/api/v1/operations/lead-commands/"+key,w.owner().token(),null);
        org.junit.jupiter.api.Assertions.assertAll(()->assertConcealed(captured,404),()->assertConcealed(attached,404),()->assertConcealed(ambiguous,404));
    }

    @Test
    void finalAuditShareEventUuidCannotDiscloseUnreadableOtherShare() throws Exception {
        World w=world("final-event-audit");
        long listing=fixtureProperty(w.cityId(),w.localityId(),"Event audit property");
        ShareSetup a=ownerShareSetup(w,listing,"event-a"),b=ownerShareSetup(w,listing,"event-b");
        UUID event=UUID.randomUUID();String body="{\"eventId\":\""+event+"\",\"type\":\"CLIPBOARD_COPIED\"}";
        String aPath="/api/v1/operations/leads/"+a.leadId()+"/shares/"+a.shareId()+"/events";
        String bPath="/api/v1/operations/leads/"+b.leadId()+"/shares/"+b.shareId()+"/events";
        assertEquals(200,send("POST",aPath,w.owner().token(),body).statusCode());
        fixture.update("update lead_routing_queue set owner_user_id=? where id=?",w.intake().userId(),a.leadId());
        Snapshot before=sensitiveSnapshot(b.leadId());
        assertConcealed(send("POST",bPath,w.owner().token(),body),404);
        assertEquals(before,sensitiveSnapshot(b.leadId()));
    }

    @Test
    void firstDifferentPayloadAndCrossLeadRacesCommitExactlyOneResult() throws Exception {
        for(boolean crossLead:List.of(false,true)) {
            World w=world("first-race");
            long a=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Race A");
            long b=crossLead?fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Race B"):a;
            long property=fixtureProperty(w.cityId(),w.localityId(),"Race home");
            UUID key=UUID.randomUUID();
            var first=CompletableFuture.supplyAsync(()->uncheckedShortlist("/api/v1/operations/leads/"+a+"/shortlists",w.owner(),shortlistCommand(0,List.of(property)),key));
            var second=CompletableFuture.supplyAsync(()->uncheckedShortlist("/api/v1/operations/leads/"+b+"/shortlists",w.owner(),shortlistCommand(0,List.of()),key));
            List<Integer> codes=new ArrayList<>(List.of(first.get(10,TimeUnit.SECONDS).statusCode(),second.get(10,TimeUnit.SECONDS).statusCode()));
            codes.sort(Integer::compareTo);assertEquals(List.of(201,409),codes);
            assertEquals(1,scalar("select count(*) from lead_shortlists where lead_id in (?,?)",a,b));
            assertEquals(1,scalar("select count(*) from lead_command_receipts where actor_user_id=? and operation_id=? and action='LEAD_SHORTLIST_CREATE'",w.owner().userId(),key));
        }
    }

    @Test
    void malformedReceiptReferencesMatchOrphanHttpEnvelopesAcrossSelectors() throws Exception {
        World w=world("malformed-selectors");
        long request=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Readable request B");
        String capture="{\"cityId\":"+w.cityId()+",\"customerName\":\"Synthetic malformed probe\"}";
        String attach="{\"expectedVersion\":0,\"eventId\":\""+UUID.randomUUID()+"\",\"decision\":\"ATTACH_ENQUIRY\"}";
        List<String> invalid=java.util.Arrays.asList(null,""," ","\t\n","invalid-stored-reference","+","-","1+2","1.0","1e3","1x","１２","١٢","1\u00a0"," 1","1 ","0","-1","-9223372036854775808","9223372036854775808","9".repeat(160),"null","NaN","Infinity","+1");
        for(String action:List.of("LEAD_CAPTURE","LEAD_SOURCE_ATTACH","LEAD_SHORTLIST_CREATE","LEAD_SHARE_PREPARE","LEAD_CONTACT_PREFERENCE_RECORD")) {
            UUID key=UUID.randomUUID();String path="/api/v1/operations/lead-commands/"+key;
            String orphan=action.equals("LEAD_SHARE_PREPARE")?UUID.randomUUID().toString():action.equals("LEAD_CONTACT_PREFERENCE_RECORD")?"preference:999999999:revision:1:leadVersion:0":"9223372036854775807";
            seedMalformedReceipt(w.owner(),key,action,orphan);
            var missing=send("GET",path+"?action="+action,w.owner().token(),null);
            assertMalformedReceiptHttp(missing,404);
            JsonNode expected=normalizedReceiptError(missing);
            for(int index=0;index<invalid.size();index++) {
                fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?",invalid.get(index),w.owner().userId(),key,action);
                String before=malformedBusinessState();
                var explicit=send("GET",path+"?action="+action,w.owner().token(),null);
                assertMalformedReceiptHttp(explicit,404);assertEquals(expected,normalizedReceiptError(explicit));
                var actionless=send("GET",path,w.owner().token(),null);
                assertMalformedReceiptHttp(actionless,404);
                if(action.equals("LEAD_CAPTURE")||action.equals("LEAD_SOURCE_ATTACH")) {
                    String endpoint=action.equals("LEAD_CAPTURE")?"/api/v1/operations/leads":"/api/v1/operations/leads/"+request+"/source-events";
                    var replay=send("POST",endpoint,w.owner().token(),action.equals("LEAD_CAPTURE")?capture:attach,"Idempotency-Key",key.toString());
                    assertMalformedReceiptHttp(replay,404);
                }
                assertEquals(before,malformedBusinessState());
                System.out.println("MALFORMED_HTTP action="+action+" case="+index+" actor="+w.owner().userId()+" requestLead="+request+" operation="+key+" body="+explicit.body()+" cache="+explicit.headers().firstValue("Cache-Control").orElse("missing"));
            }
        }
    }

    @Test
    void malformedActionlessMixedCandidatesNeverDiscloseAmbiguity() throws Exception {
        World w=world("malformed-ambiguous");
        long lead=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"Readable candidate");
        for(boolean validFirst:List.of(true,false)) {
            UUID key=UUID.randomUUID();
            seedMalformedReceipt(w.owner(),key,"LEAD_CAPTURE",Long.toString(lead));
            seedMalformedReceipt(w.owner(),key,"LEAD_SOURCE_ATTACH",validFirst?"":"invalid");
            fixture.update("update lead_command_receipts set completed_at=clock_timestamp()+interval '10 seconds' where actor_user_id=? and operation_id=? and action=?",w.owner().userId(),key,validFirst?"LEAD_CAPTURE":"LEAD_SOURCE_ATTACH");
            var response=send("GET","/api/v1/operations/lead-commands/"+key,w.owner().token(),null);
            assertMalformedReceiptHttp(response,404);
            fixture.update("update lead_command_receipts set result_reference='' where actor_user_id=? and operation_id=?",w.owner().userId(),key);
            assertMalformedReceiptHttp(send("GET","/api/v1/operations/lead-commands/"+key,w.owner().token(),null),404);
        }
    }


    @Test
    void crossServicePersistedAliasesConcealExactChangedAndBothSelectors() throws Exception {
        World w = world("cross-parser");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Parser synthetic home");
        ShareSetup setup = ownerShareSetup(w, listing, "Parser synthetic Lead");
        String numeric = setup.shortlist().path("id").asText();
        // All rows are new synthetic child fixtures; no preexisting result is changed.
        String hex = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 65536));
        UUID shareId = UUID.fromString(hex + "-1-1-1-" + Long.toHexString(System.nanoTime() & 0xffffffffffL));
        fixture.update("insert into lead_shares(id,lead_id,shortlist_id,shortlist_version,contact_preference_revision,mode,public_links_json,preview_text,masked_destination,recorded_at,created_by_user_id) select ?,lead_id,shortlist_id,shortlist_version,contact_preference_revision,mode,public_links_json,preview_text,masked_destination,recorded_at,created_by_user_id from lead_shares where id=?", shareId, setup.shareId());
        var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
        for (boolean share : List.of(false, true)) {
            String action = share ? "LEAD_SHARE_PREPARE" : "LEAD_SHORTLIST_CREATE";
            String original = share ? setup.shareId().toString() : numeric;
            String canonical = share ? shareId.toString() : numeric;
            UUID key = fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action=? and result_reference=?", UUID.class, w.owner().userId(), action, original);
            String path = "/api/v1/operations/leads/" + setup.leadId() + (share ? "/shares" : "/shortlists");
            String exact = share ? shareBody(key, setup.shortlist().path("leadVersion").asLong(), Long.parseLong(numeric), setup.shortlist().path("version").asLong(), setup.preference().path("revision").asLong())
                    : shortlistCommand(setup.preference().path("leadVersion").asLong(), List.of(listing));
            String changed = exact.replace("\"expectedLeadVersion\":" + (share ? setup.shortlist().path("leadVersion").asLong() : setup.preference().path("leadVersion").asLong()), "\"expectedLeadVersion\":9999999");
            List<String> invalid;
            if (share) {
                String[] groups = canonical.split("-");
                String shortForm = java.util.Arrays.stream(groups).map(g -> g.replaceFirst("^0+(?!$)", "")).collect(java.util.stream.Collectors.joining("-"));
                invalid = java.util.Arrays.asList(null, "", " ", shortForm, "0" + groups[0] + shortForm.substring(shortForm.indexOf('-')),
                        "0" + canonical,
                        "1-1-1-1", "1-1-1-1-1-1", canonical.replace("-", ""), "{" + canonical + "}", "urn:uuid:" + canonical,
                        canonical + "\n", " " + canonical, canonical + "-", canonical.replace('a', 'g') + "g", "１-1-1-1-1", "١-1-1-1-1",
                        canonical.chars().mapToObj(c -> String.valueOf((char)(c >= '0' && c <= '9' ? '０' + c - '0' : c))).collect(java.util.stream.Collectors.joining()), "bad");
            } else {
                invalid = java.util.Arrays.asList(null, "", " ", "\t", "+" + numeric,
                        numeric.chars().mapToObj(c -> String.valueOf((char)('０' + c - '0'))).collect(java.util.stream.Collectors.joining()),
                        numeric.chars().mapToObj(c -> String.valueOf((char)('٠' + c - '0'))).collect(java.util.stream.Collectors.joining()),
                        numeric.chars().mapToObj(c -> String.valueOf((char)('०' + c - '0'))).collect(java.util.stream.Collectors.joining()),
                        numeric.substring(0, numeric.length() - 1) + (char)('０' + numeric.charAt(numeric.length() - 1) - '0'),
                        numeric.substring(0, numeric.length() - 1) + (char)('٠' + numeric.charAt(numeric.length() - 1) - '0'),
                        "0".repeat(100) + numeric, "0".repeat(20) + numeric, "-" + numeric, "0", "00000000000000000000",
                        "9223372036854775808", numeric + "\n", numeric + "\r\n", numeric + ".0", numeric + "e0", numeric + "_", numeric + "\u00a0", "bad");
            }
            for (int index = 0; index < invalid.size(); index++) {
                String reference = invalid.get(index);
                fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?", reference, w.owner().userId(), key, action);
                String before = parserBusinessState();
                for (String payload : List.of(exact, changed)) {
                    HttpResponse<String> response = send("POST", path, w.owner().token(), payload, "Idempotency-Key", key.toString());
                    parserReceiptEvidence(action, "leaf changed=" + payload.equals(changed), reference, response);
                    checks.add(() -> assertMalformedReceiptHttp(response, 404));
                }
                for (String suffix : List.of("?action=" + action, "")) {
                    HttpResponse<String> response = send("GET", "/api/v1/operations/lead-commands/" + key + suffix, w.owner().token(), null);
                    parserReceiptEvidence(action, "selector" + suffix, reference, response);
                    checks.add(() -> assertMalformedReceiptHttp(response, 404));
                }
                assertEquals(before, parserBusinessState(), "Malformed replay must have no durable delta");
            }
            // Accepted grammar is unchanged, including <=19-digit leading zeros and UUID case.
            for (String reference : share ? List.of(canonical, canonical.toUpperCase(java.util.Locale.ROOT)) : List.of(numeric, "0" + numeric, "0".repeat(19 - numeric.length()) + numeric)) {
                fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?", reference, w.owner().userId(), key, action);
                String before = parserBusinessState();
                var replay = send("POST", path, w.owner().token(), exact, "Idempotency-Key", key.toString());
                assertEquals(201, replay.statusCode(), replay.body());
                assertEquals(canonical, mapper.readTree(replay.body()).path("id").asText());
                assertEquals(409, send("POST", path, w.owner().token(), changed, "Idempotency-Key", key.toString()).statusCode());
                for (String suffix : List.of("?action=" + action, "")) {
                    var receipt = send("GET", "/api/v1/operations/lead-commands/" + key + suffix, w.owner().token(), null);
                    assertEquals(200, receipt.statusCode(), receipt.body());
                    assertEquals(reference, mapper.readTree(receipt.body()).path("resultReference").asText());
                }
                assertEquals(before, parserBusinessState());
            }
            fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?", share ? UUID.randomUUID().toString() : "9223372036854775807", w.owner().userId(), key, action);
            for (String payload : List.of(exact, changed)) assertMalformedReceiptHttp(send("POST", path, w.owner().token(), payload, "Idempotency-Key", key.toString()), 404);
            for (String suffix : List.of("?action=" + action, "")) assertMalformedReceiptHttp(send("GET", "/api/v1/operations/lead-commands/" + key + suffix, w.owner().token(), null), 404);
        }
        org.junit.jupiter.api.Assertions.assertAll("Persisted cross-service grammar", checks);
    }


    @Test
    void parserLeafResourceEncodingAndAuditFailuresRemain500AndRollback() throws Exception {
        World w = world("parser-faults");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Fault synthetic home");
        ShareSetup setup = ownerShareSetup(w, listing, "Fault synthetic Lead");
        for (boolean share : List.of(false, true)) {
            String action = share ? "LEAD_SHARE_PREPARE" : "LEAD_SHORTLIST_CREATE";
            String reference = share ? setup.shareId().toString() : setup.shortlist().path("id").asText();
            UUID key = fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action=? and result_reference=?", UUID.class, w.owner().userId(), action, reference);
            String path = "/api/v1/operations/leads/" + setup.leadId() + (share ? "/shares" : "/shortlists");
            String exact = share ? shareBody(key, setup.shortlist().path("leadVersion").asLong(), setup.shortlist().path("id").asLong(), 0, 1)
                    : shortlistCommand(setup.preference().path("leadVersion").asLong(), List.of(listing));
            String before = parserBusinessState();
            try {
                var fault = new org.springframework.dao.DataAccessResourceFailureException("Synthetic parser repository unavailable");
                if (share) org.mockito.Mockito.doThrow(fault).when(parserShareFault).findById(setup.shareId());
                else org.mockito.Mockito.doThrow(fault).when(parserShortlistFault).findById(setup.shortlist().path("id").asLong());
                assertParser500(send("POST", path, w.owner().token(), exact, "Idempotency-Key", key.toString()));
                assertEquals(before, parserBusinessState());
            } finally {
                org.mockito.Mockito.reset(parserShareFault, parserShortlistFault);
            }
            // New-write audit failure must roll back root versions, children, activity and receipt.
            UUID fresh = UUID.randomUUID();
            String newBody = share ? shareBody(fresh, leadVersion(setup.leadId()), setup.shortlist().path("id").asLong(), 0, 1)
                    : shortlistCommand(leadVersion(setup.leadId()), List.of(listing));
            try {
                org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic parser audit unavailable"))
                        .when(malformedAuditFault).recordUserEvent(org.mockito.ArgumentMatchers.argThat(actor -> actor != null && actor.getId().equals(w.owner().userId())),
                                org.mockito.ArgumentMatchers.eq(share ? "LEAD_SHARE_PREPARED" : "LEAD_SHORTLIST_CREATED"),
                                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
                assertParser500(send("POST", path, w.owner().token(), newBody, "Idempotency-Key", fresh.toString()));
                assertEquals(before, parserBusinessState());
            } finally { org.mockito.Mockito.reset(malformedAuditFault); }
            var retry = send("POST", path, w.owner().token(), newBody, "Idempotency-Key", fresh.toString());
            assertEquals(201, retry.statusCode(), retry.body());
            String after = parserBusinessState();
            assertEquals(201, send("POST", path, w.owner().token(), newBody, "Idempotency-Key", fresh.toString()).statusCode());
            assertEquals(after, parserBusinessState());
            assertEquals(1, scalar("select count(*) from lead_command_receipts where actor_user_id=? and operation_id=? and action=?", w.owner().userId(), fresh, action));
        }
        // Malformed child encoding is an invariant error after valid reference/authority, never404.
        UUID key = fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action='LEAD_SHARE_PREPARE' and result_reference=?", UUID.class, w.owner().userId(), setup.shareId().toString());
        String original = fixture.queryForObject("select public_links_json from lead_shares where id=?", String.class, setup.shareId());
        fixture.update("update lead_shares set public_links_json='[broken' where id=?", setup.shareId());
        String before = parserBusinessState();
        try {
            assertParser500(send("POST", "/api/v1/operations/leads/" + setup.leadId() + "/shares", w.owner().token(),
                    shareBody(key, setup.shortlist().path("leadVersion").asLong(), setup.shortlist().path("id").asLong(), 0, 1)));
            assertEquals(before, parserBusinessState());
        } finally { fixture.update("update lead_shares set public_links_json=? where id=?", original, setup.shareId()); }
    }

    private void assertParser500(HttpResponse<String> response) throws Exception {
        assertEquals(500, response.statusCode(), response.body());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("We could not complete this request right now. Please try again shortly.", body.path("message").asText());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse("missing"));
        assertFalse(response.body().contains("Synthetic"));
        assertFalse(response.body().contains("org.springframework"));
        System.out.println("PARSER_SAFE_500 " + response.body());
    }

    @Test
    void shareConcurrentUuidUsesAndLostResponsesCommitOneResult() throws Exception {
        for (boolean changed : List.of(false, true)) {
            World w = world("parser-share-race");
            long listing = fixtureProperty(w.cityId(), w.localityId(), "Race synthetic home");
            ShareSetup setup = ownerShareSetup(w, listing, "Race synthetic Lead");
            UUID key = UUID.randomUUID();
            String body = shareBody(key, leadVersion(setup.leadId()), setup.shortlist().path("id").asLong(), 0, 1);
            String second = changed ? body.replace("MANUAL_BUSINESS_HANDOFF", "COPY_LINKS") : body;
            String path = "/api/v1/operations/leads/" + setup.leadId() + "/shares";
            long shares = scalar("select count(*) from lead_shares"), activities = scalar("select count(*) from lead_activities"), audits = scalar("select count(*) from operational_audit_events");
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.function.Function<String, CompletableFuture<HttpResponse<String>>> request = payload -> CompletableFuture.supplyAsync(() -> {
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); return send("POST", path, w.owner().token(), payload); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
            var one = request.apply(body); var two = request.apply(second); start.countDown();
            var a = one.get(10, TimeUnit.SECONDS); var b = two.get(10, TimeUnit.SECONDS);
            var codes = new ArrayList<>(List.of(a.statusCode(), b.statusCode())); codes.sort(Integer::compareTo);
            assertEquals(changed ? List.of(201, 409) : List.of(201, 201), codes, a.body() + b.body());
            if (!changed) assertEquals(mapper.readTree(a.body()).path("id"), mapper.readTree(b.body()).path("id"));
            assertEquals(shares + 1, scalar("select count(*) from lead_shares"));
            assertEquals(activities + 1, scalar("select count(*) from lead_activities"));
            assertEquals(audits + 1, scalar("select count(*) from operational_audit_events"));
            assertEquals(1, scalar("select count(*) from lead_command_receipts where actor_user_id=? and operation_id=?", w.owner().userId(), key));
            String winner = a.statusCode() == 201 ? body : second;
            String after = parserBusinessState();
            assertEquals(201, send("POST", path, w.owner().token(), winner).statusCode());
            assertEquals(200, send("GET", "/api/v1/operations/lead-commands/" + key, w.owner().token(), null).statusCode());
            fixture.update("update lead_routing_queue set owner_user_id=? where id=?", w.intake().userId(), setup.leadId());
            assertConcealed(send("POST", path, w.owner().token(), winner), 404);
            assertEquals(after, parserBusinessState());
        }
    }

    @Test
    void malformedLeafReplaysAfterPidCorrelatedOperationWaitAndRevocationStayConcealed() throws Exception {
        for (boolean share : List.of(false, true)) {
            World w = world("parser-wait"), other = world("parser-surviving-scope");
            Actor actor = staff("parser-wait-actor", new GrantSpec("OPS_COORDINATE", "TEAM", null, w.teamId(), null, null),
                    new GrantSpec("OPS_COORDINATE", "TEAM", null, other.teamId(), null, null));
            long listing = fixtureProperty(w.cityId(), w.localityId(), "Wait synthetic home");
            ShareSetup setup = ownerShareSetup(w, listing, "Wait synthetic Lead", actor);
            String action = share ? "LEAD_SHARE_PREPARE" : "LEAD_SHORTLIST_CREATE";
            String canonical = share ? setup.shareId().toString() : setup.shortlist().path("id").asText();
            UUID key = fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action=? and result_reference=?", UUID.class, actor.userId(), action, canonical);
            fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?", share ? "1-1-1-1-1" : "+" + canonical, actor.userId(), key, action);
            String path = "/api/v1/operations/leads/" + setup.leadId() + (share ? "/shares" : "/shortlists");
            String body = share ? shareBody(key, setup.shortlist().path("leadVersion").asLong(), setup.shortlist().path("id").asLong(), 0, 1)
                    : shortlistCommand(setup.preference().path("leadVersion").asLong(), List.of(listing));
            String before = parserBusinessState();
            try (Connection lock = DbA01LeadTestTarget.requireFromEnvironment().openRuntime()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, actor.userId() + ":" + action + ":" + key); statement.execute();
                }
                var waiting = CompletableFuture.supplyAsync(() -> uncheckedShortlist(path, actor, body, key));
                awaitOperationWait(lock, waiting);
                fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='PARSER_WAIT' where id=?", actor.userId(), actor.grantIds().get(0));
                assertFalse(waiting.isDone());
                System.out.println("PARSER_WAIT " + action + " blocker PID=" + scalarOn(lock, "select pg_backend_pid()"));
                lock.commit(); assertMalformedReceiptHttp(waiting.get(10, TimeUnit.SECONDS), 404);
            }
            assertEquals(before, parserBusinessState());
        }
    }


    @Test
    void deletedCanonicalStoredResultsConcealBeforeReplayConflictAndSelectors() throws Exception {
        World w = world("parser-deleted-result");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Deleted synthetic home");
        ShareSetup setup = ownerShareSetup(w, listing, "Deleted synthetic Lead");
        for (boolean share : List.of(true, false)) {
            String action = share ? "LEAD_SHARE_PREPARE" : "LEAD_SHORTLIST_CREATE";
            String reference = share ? setup.shareId().toString() : setup.shortlist().path("id").asText();
            UUID key = fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action=? and result_reference=?", UUID.class, w.owner().userId(), action, reference);
            if (share) {
                assertEquals(1, fixture.update("delete from lead_shares where id=?", setup.shareId()));
            } else {
                fixture.update("delete from lead_shortlist_items where shortlist_id=?", setup.shortlist().path("id").asLong());
                assertEquals(1, fixture.update("delete from lead_shortlists where id=?", setup.shortlist().path("id").asLong()));
            }
            String path = "/api/v1/operations/leads/" + setup.leadId() + (share ? "/shares" : "/shortlists");
            String exact = share ? shareBody(key, setup.shortlist().path("leadVersion").asLong(), setup.shortlist().path("id").asLong(), 0, 1)
                    : shortlistCommand(setup.preference().path("leadVersion").asLong(), List.of(listing));
            String before = parserBusinessState();
            for (String payload : List.of(exact, exact.replace("expectedLeadVersion\":", "expectedLeadVersion\":99"))) {
                assertMalformedReceiptHttp(send("POST", path, w.owner().token(), payload, "Idempotency-Key", key.toString()), 404);
            }
            for (String suffix : List.of("?action=" + action, "")) {
                assertMalformedReceiptHttp(send("GET", "/api/v1/operations/lead-commands/" + key + suffix, w.owner().token(), null), 404);
            }
            assertEquals(before, parserBusinessState());
        }
    }


    @Test
    void mixedLeafReceiptCandidatesConcealMalformedBeforeAmbiguityInEitherOrder() throws Exception {
        World w = world("parser-mixed-candidates");
        long listing = fixtureProperty(w.cityId(), w.localityId(), "Mixed synthetic home");
        ShareSetup setup = ownerShareSetup(w, listing, "Mixed synthetic Lead");
        String shortlist = setup.shortlist().path("id").asText(), share = setup.shareId().toString();
        for (boolean badShare : List.of(false, true)) for (boolean invalidFirst : List.of(false, true)) {
            UUID key = UUID.randomUUID();
            String badAction = badShare ? "LEAD_SHARE_PREPARE" : "LEAD_SHORTLIST_CREATE";
            String validAction = badShare ? "LEAD_SHORTLIST_CREATE" : "LEAD_SHARE_PREPARE";
            seedMalformedReceipt(w.owner(), key, "LEAD_SHARE_PREPARE", badShare ? "1-1-1-1-1" : share);
            seedMalformedReceipt(w.owner(), key, "LEAD_SHORTLIST_CREATE", badShare ? shortlist : "+" + shortlist);
            fixture.update("update lead_command_receipts set completed_at=clock_timestamp()+interval '10 seconds' where actor_user_id=? and operation_id=? and action=?", w.owner().userId(), key, invalidFirst ? badAction : validAction);
            String before = parserBusinessState();
            assertMalformedReceiptHttp(send("GET", "/api/v1/operations/lead-commands/" + key, w.owner().token(), null), 404);
            assertMalformedReceiptHttp(send("GET", "/api/v1/operations/lead-commands/" + key + "?action=" + badAction, w.owner().token(), null), 404);
            var valid = send("GET", "/api/v1/operations/lead-commands/" + key + "?action=" + validAction, w.owner().token(), null);
            assertEquals(200, valid.statusCode(), valid.body());
            assertEquals("RECORDED", mapper.readTree(valid.body()).path("state").asText());
            assertEquals(before, parserBusinessState());
            fixture.update("update lead_command_receipts set result_reference=? where actor_user_id=? and operation_id=? and action=?", badShare ? share : shortlist, w.owner().userId(), key, badAction);
            assertEquals(409, send("GET", "/api/v1/operations/lead-commands/" + key, w.owner().token(), null).statusCode());
        }
    }

    private void parserReceiptEvidence(String action, String route, String reference,
                                      HttpResponse<String> response) throws Exception {
        System.out.println("PARSER_HTTP " + mapper.writeValueAsString(Map.of("action", action,
                "route", route, "reference", reference == null ? "<NULL>" : reference,
                "status", response.statusCode(), "body", mapper.readTree(response.body()),
                "cacheControl", response.headers().firstValue("Cache-Control").orElse("missing"))));
    }

    private String parserBusinessState() {
        return malformedBusinessState() + fixture.queryForObject("select jsonb_build_array(" +
                "(select count(*) from lead_shortlists),(select count(*) from lead_shortlist_items)," +
                "(select count(*) from lead_contact_preferences),(select count(*) from lead_shares)," +
                "(select count(*) from lead_share_events),(select coalesce(sum(version),0) from lead_routing_queue)," +
                "(select coalesce(sum(version),0) from lead_shortlists))::text", String.class);
    }

    private void seedMalformedReceipt(Actor actor,UUID key,String action,String reference) {
        fixture.update("insert into lead_command_receipts(id,actor_user_id,operation_id,action,payload_hash,result_reference,completed_at) values (?,?,?,?,?,?,clock_timestamp())",UUID.randomUUID(),actor.userId(),key,action,"0".repeat(64),reference);
    }

    private JsonNode normalizedReceiptError(HttpResponse<String> response) throws Exception {
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(response.body());
        json.remove("timestamp");return json;
    }

    private void assertMalformedReceiptHttp(HttpResponse<String> response,int expected) throws Exception {
        assertEquals(expected,response.statusCode(),response.body());
        JsonNode body=mapper.readTree(response.body());assertEquals("Lead not found",body.path("message").asText());
        assertEquals("Resource Not Found",body.path("error").asText());
        java.util.Set<String> keys=new java.util.HashSet<>();body.fieldNames().forEachRemaining(keys::add);
        assertEquals(java.util.Set.of("timestamp","status","error","message","path"),keys);
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse("missing"));
        assertFalse(response.body().contains("Stored Lead receipt"));assertFalse(body.has("resultReference"));
        assertFalse(response.body().contains("invalid-stored-reference"));
    }

    private String malformedBusinessState() {
        return fixture.queryForObject("select jsonb_build_array((select count(*) from lead_routing_queue),(select count(*) from lead_source_events),(select count(*) from lead_activities),(select count(*) from lead_command_receipts),(select count(*) from operational_audit_events),(select count(*) from visit_notification_outbox),(select count(*) from visit_sessions),(select count(*) from property_visit_requests),(select count(*) from visit_start_challenges),(select count(*) from tenant_visit_entitlement_accounts),(select count(*) from visit_entitlement_ledger),(select count(*) from visit_request_routing_tasks))::text",String.class);
    }

    @Test
    void legitimateCaptureSourceReplayAndSelectorControlsRetainProvenance() throws Exception {
        World w=world("malformed-positive");UUID captureKey=UUID.randomUUID();
        String phone="+9195"+String.format("%08d",Math.abs(UUID.randomUUID().hashCode()%100000000));
        String body="{\"cityId\":"+w.cityId()+",\"customerName\":\"Synthetic receipt control\",\"contactPhone\":\""+phone+"\",\"sourceChannel\":\"PHONE\"}";
        var first=send("POST","/api/v1/operations/leads",w.intake().token(),body,"Idempotency-Key",captureKey.toString());
        assertEquals(201,first.statusCode(),first.body());long id=mapper.readTree(first.body()).path("summary").path("id").asLong();assertTrue(id>0);
        String after=malformedBusinessState();
        for(int i=0;i<2;i++) {
            var exact=send("POST","/api/v1/operations/leads",w.intake().token(),body,"Idempotency-Key",captureKey.toString());
            assertEquals(201,exact.statusCode(),exact.body());assertEquals(id,mapper.readTree(exact.body()).path("summary").path("id").asLong());
        }
        assertEquals(409,send("POST","/api/v1/operations/leads",w.intake().token(),body.replace("Synthetic receipt control","Changed receipt control"),"Idempotency-Key",captureKey.toString()).statusCode());
        assertEquals(after,malformedBusinessState());
        assertEquals(1,scalar("select count(*) from lead_source_events where provider='MANUAL' and provider_account_id=? and external_event_id=? and lead_id=? and fingerprint_version='MANUAL_CANONICAL_V1'",Long.toString(w.intake().userId()),captureKey.toString(),id));
        UUID event=UUID.randomUUID();fixture.update("insert into lead_source_events(id,provider,provider_account_id,external_event_id,event_kind,payload_hash,processing_state,available_at,received_at,updated_at) values (?,'META','DB_A01_SYNTHETIC',?,'META_LEADGEN',?,'AWAITING_REVIEW',clock_timestamp(),clock_timestamp(),clock_timestamp())",event,event.toString(),"a".repeat(64));
        UUID attachKey=UUID.randomUUID();String path="/api/v1/operations/leads/"+id+"/source-events";
        String command="{\"expectedVersion\":"+leadVersion(id)+",\"eventId\":\""+event+"\",\"decision\":\"ATTACH_ENQUIRY\"}";
        var attached=send("POST",path,w.intake().token(),command,"Idempotency-Key",attachKey.toString());
        assertEquals(200,attached.statusCode(),attached.body());String committed=malformedBusinessState();
        assertEquals(200,send("POST",path,w.intake().token(),command,"Idempotency-Key",attachKey.toString()).statusCode());
        assertEquals(409,send("POST",path,w.intake().token(),command.replace("ATTACH_ENQUIRY","KEEP_DISTINCT").replace("}",",\"reasonCode\":\"DB_A01_TEST\"}"),"Idempotency-Key",attachKey.toString()).statusCode());
        assertEquals(committed,malformedBusinessState());
        assertEquals(1,scalar("select count(*) from lead_source_events where id=? and lead_id=? and resolution_lead_id=? and processing_state='PROCESSED' and fingerprint_version='LEGACY_ENVELOPE_V1'",event,id,id));
        for(UUID key:List.of(captureKey,attachKey)) {
            var receipt=send("GET","/api/v1/operations/lead-commands/"+key,w.intake().token(),null);
            assertEquals(200,receipt.statusCode(),receipt.body());assertEquals("RECORDED",mapper.readTree(receipt.body()).path("state").asText());
        }
        UUID unknown=UUID.randomUUID();seedMalformedReceipt(w.owner(),unknown,"LEAD_CAPTURE",null);
        var otherActor=send("GET","/api/v1/operations/lead-commands/"+unknown,w.intake().token(),null);
        assertEquals("NOT_RECORDED",mapper.readTree(otherActor.body()).path("state").asText());
        var wrongAction=send("GET","/api/v1/operations/lead-commands/"+captureKey+"?action=LEAD_SOURCE_ATTACH",w.intake().token(),null);
        assertEquals("NOT_RECORDED",mapper.readTree(wrongAction.body()).path("state").asText());
        UUID mixed=UUID.randomUUID();seedMalformedReceipt(w.intake(),mixed,"LEAD_CAPTURE",Long.toString(id));seedMalformedReceipt(w.intake(),mixed,"LEAD_SOURCE_ATTACH",Long.toString(id));
        assertEquals(409,send("GET","/api/v1/operations/lead-commands/"+mixed,w.intake().token(),null).statusCode());
        // Oversize/NUL fixtures are rejected by existing PostgreSQL, never by schema relaxation.
        for(String bad:List.of("9".repeat(161),"invalid\u0000reference")) {
            try { seedMalformedReceipt(w.intake(),UUID.randomUUID(),"LEAD_CAPTURE",bad);throw new AssertionError("Expected PostgreSQL text bound rejection"); }
            catch(org.springframework.dao.DataAccessException rejection) {
                assertTrue(rejection.getMostSpecificCause() instanceof java.sql.SQLException);
                String state=((java.sql.SQLException)rejection.getMostSpecificCause()).getSQLState();
                assertEquals(bad.length()>160?"22001":"22021",state);
                System.out.println("PERSISTENCE_LIMIT rejected receipt fixture SQLSTATE="+state);
            }
        }
    }

    @Test
    void auditInfrastructureFailureRemains500WithTransactionalRollbackAndLostResponseRetry() throws Exception {
        World w=world("malformed-audit-fault");UUID key=UUID.randomUUID();
        String body="{\"cityId\":"+w.cityId()+",\"customerName\":\"Synthetic audit failure\",\"contactPhone\":\"+9194"+String.format("%08d",Math.abs(UUID.randomUUID().hashCode()%100000000))+"\"}";
        String before=malformedBusinessState();
        org.mockito.Mockito.doThrow(new IllegalStateException("SYNTHETIC_AUDIT_UNAVAILABLE"))
                .when(malformedAuditFault).recordUserEvent(org.mockito.ArgumentMatchers.argThat(actor->actor!=null&&actor.getId().equals(w.intake().userId())),org.mockito.ArgumentMatchers.eq("LEAD_CAPTURED"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        try {
            var failed=send("POST","/api/v1/operations/leads",w.intake().token(),body,"Idempotency-Key",key.toString());
            assertEquals(500,failed.statusCode(),failed.body());assertEquals("no-store",failed.headers().firstValue("Cache-Control").orElse("missing"));
            assertFalse(failed.body().contains("SYNTHETIC_AUDIT_UNAVAILABLE"));assertFalse(failed.body().contains("SQLException"));
            assertEquals(before,malformedBusinessState());
            org.mockito.Mockito.verify(malformedAuditFault).recordUserEvent(org.mockito.ArgumentMatchers.argThat(actor->actor!=null&&actor.getId().equals(w.intake().userId())),org.mockito.ArgumentMatchers.eq("LEAD_CAPTURED"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap());
        } finally {org.mockito.Mockito.reset(malformedAuditFault);}
        LostResponse lost=sendWithoutClientAcknowledgement("POST","/api/v1/operations/leads",w.intake().token(),body,"Idempotency-Key",key.toString());
        assertEquals(-1,lost.clientRead());assertTrue(lost.statusLine().contains("201"));
        var replay=send("POST","/api/v1/operations/leads",w.intake().token(),body,"Idempotency-Key",key.toString());assertEquals(201,replay.statusCode(),replay.body());
        assertEquals(1,scalar("select count(*) from lead_command_receipts where actor_user_id=? and operation_id=?",w.intake().userId(),key));
        assertEquals(1,scalar("select count(*) from lead_source_events where provider='MANUAL' and provider_account_id=? and external_event_id=?",Long.toString(w.intake().userId()),key.toString()));
    }

    @Test
    void validStoredLeadSelectorsTrackCurrentReadableAuthorityAndAllLossClasses() throws Exception {
        for(String loss:List.of("reassign","revoke","expiry","profile","city","team","transfer")) {
            World w=world("m07-"+loss),other=world("m07-other-"+loss);
            Actor actor=staff("m07-"+loss,new GrantSpec("OPS_COORDINATE","TEAM",null,w.teamId(),null,loss.equals("expiry")?Instant.now().plusSeconds(3):null),new GrantSpec("OPS_COORDINATE","TEAM",null,other.teamId(),null,null));
            long a=fixtureLead(w.cityId(),w.teamId(),actor.userId(),"M07 stored A");
            long b=fixtureLead(other.cityId(),other.teamId(),actor.userId(),"M07 requested B");
            UUID key=UUID.randomUUID();seedMalformedReceipt(actor,key,"LEAD_CAPTURE",Long.toString(a));
            String path="/api/v1/operations/lead-commands/"+key;
            assertEquals(200,send("GET",path,actor.token(),null).statusCode());
            assertEquals(200,send("GET",path+"?action=LEAD_CAPTURE",actor.token(),null).statusCode());
            switch(loss) {
                case "reassign" -> fixture.update("update lead_routing_queue set owner_user_id=? where id=?",w.owner().userId(),a);
                case "revoke" -> fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='M07_RECEIPT' where id=?",actor.userId(),actor.grantIds().get(0));
                case "expiry" -> {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(!Boolean.TRUE.equals(fixture.queryForObject("select clock_timestamp()>=expires_at from staff_access_grants where id=?",Boolean.class,actor.grantIds().get(0)))) {assertTrue(System.nanoTime()<deadline);Thread.sleep(25);}
                }
                case "profile" -> fixture.update("update employee_profiles set staff_active=false where user_id=?",actor.userId());
                case "city" -> fixture.update("update supported_cities set active=false where id=?",w.cityId());
                case "team" -> fixture.update("update operating_teams set active=false where id=?",w.teamId());
                case "transfer" -> fixture.update("update lead_routing_queue set supported_city_id=?,operating_team_id=?,owner_user_id=? where id=?",other.cityId(),other.teamId(),other.owner().userId(),a);
            }
            String before=malformedBusinessState();
            for(String selector:List.of(path,path+"?action=LEAD_CAPTURE")) {
                var denied=send("GET",selector,actor.token(),null);
                if(loss.equals("profile")) {assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("resultReference"));}
                else assertMalformedReceiptHttp(denied,404);
            }
            assertEquals(before,malformedBusinessState());
            if(!loss.equals("profile")) assertEquals(200,send("GET","/api/v1/operations/leads/"+b,actor.token(),null).statusCode());
        }
        World w=world("m07-readers");long owned=fixtureLead(w.cityId(),w.teamId(),w.owner().userId(),"M07 supervisor stored");
        Actor supervisor=staff("m07-supervisor",new GrantSpec("OPS_SUPERVISE","TEAM",null,w.teamId(),null,null));
        long intake=fixtureLead(w.cityId(),null,null,"M07 intake stored");
        for(Actor actor:List.of(supervisor,w.intake())) {
            UUID key=UUID.randomUUID();long id=actor.userId()==supervisor.userId()?owned:intake;
            seedMalformedReceipt(actor,key,"LEAD_CAPTURE",Long.toString(id));
            var readable=send("GET","/api/v1/operations/lead-commands/"+key,actor.token(),null);assertEquals(200,readable.statusCode(),readable.body());
            var changed=send("POST","/api/v1/operations/leads",actor.token(),"{\"customerName\":\"M07 changed\"}","Idempotency-Key",key.toString());assertEquals(409,changed.statusCode(),changed.body());
        }
    }

    @Test
    void malformedCaptureAfterObservedOperationWaitAndRevocationRemainsConcealed() throws Exception {
        World w=world("m09-wait"),other=world("m09-other");
        Actor actor=staff("m09-owner",new GrantSpec("OPS_COORDINATE","TEAM",null,w.teamId(),null,null),new GrantSpec("OPS_COORDINATE","TEAM",null,other.teamId(),null,null));
        long b=fixtureLead(other.cityId(),other.teamId(),actor.userId(),"M09 readable B");
        UUID key=UUID.randomUUID();seedMalformedReceipt(actor,key,"LEAD_CAPTURE",null);
        String before=malformedBusinessState();
        try(Connection lock=DbA01LeadTestTarget.requireFromEnvironment().openRuntime()) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1,actor.userId()+":LEAD_CAPTURE:"+key);statement.execute();
            }
            var waiting=CompletableFuture.supplyAsync(()->uncheckedShortlist("/api/v1/operations/leads",actor,"{\"customerName\":\"M09 pending\"}",key));
            awaitOperationWait(lock,waiting);
            fixture.update("update staff_access_grants set revoked_at=clock_timestamp(),revoked_by_user_id=?,revoke_reason_code='M09_RECEIPT' where id=?",actor.userId(),actor.grantIds().get(0));
            assertFalse(waiting.isDone());
            System.out.println("M09 operation wait observed before revocation commit/release, blocker PID="+scalarOn(lock,"select pg_backend_pid()"));
            lock.commit();assertMalformedReceiptHttp(waiting.get(10,TimeUnit.SECONDS),404);
        }
        assertEquals(200,send("GET","/api/v1/operations/leads/"+b,actor.token(),null).statusCode());
        assertEquals(before,malformedBusinessState());
    }

    @Test
    void validShareAndShortlistReceiptReadersBindPersistedResults() throws Exception {
        World w=world("m13-result-readers");long listing=fixtureProperty(w.cityId(),w.localityId(),"M13 current property");
        ShareSetup setup=ownerShareSetup(w,listing,"m13-readers");
        String before=malformedBusinessState();
        for(String action:List.of("LEAD_SHORTLIST_CREATE","LEAD_SHARE_PREPARE")) {
            String reference=action.equals("LEAD_SHORTLIST_CREATE")?setup.shortlist().path("id").asText():setup.shareId().toString();
            UUID key=fixture.queryForObject("select operation_id from lead_command_receipts where actor_user_id=? and action=? and result_reference=?",UUID.class,w.owner().userId(),action,reference);
            for(String suffix:List.of("","?action="+action)) {
                var response=send("GET","/api/v1/operations/lead-commands/"+key+suffix,w.owner().token(),null);
                assertEquals(200,response.statusCode(),response.body());JsonNode receipt=mapper.readTree(response.body());
                assertEquals("RECORDED",receipt.path("state").asText());assertEquals(reference,receipt.path("resultReference").asText());
                assertEquals(action,receipt.path("action").asText());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse("missing"));
            }
        }
        assertEquals(before,malformedBusinessState());
    }

    private HttpResponse<String> uncheckedShortlist(String path, Actor actor, String body, UUID key) {
        try { return send("POST", path, actor.token(), body, "Idempotency-Key", key.toString()); }
        catch (Exception error) { throw new CompletionException(error); }
    }

    private void assertConcealed(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode json = mapper.readTree(response.body());
        assertEquals("Lead not found", json.path("message").asText(), response.body());
        assertFalse(json.has("id")); assertFalse(json.has("listingIds"));
        assertFalse(response.body().contains("different shortlist request"));
    }

    @Test
    void refinedRuntimeAclRetainsColumnLocksButDeniesAuthorityAndRootDeletes()
            throws Exception {
        DbA01LeadTestTarget.Target target = DbA01LeadTestTarget.requireFromEnvironment();
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select rolinherit from pg_roles where rolname=current_user", Boolean.class));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from pg_auth_members "
                        + "where roleid=current_user::regrole or member=current_user::regrole",
                Long.class));

        List<String> authorityUpdateColumns = jdbc.queryForList(
                "select table_name||'.'||column_name "
                        + "from information_schema.column_privileges "
                        + "where table_schema='public' and grantee=current_user "
                        + "and privilege_type='UPDATE' "
                        + "and table_name in "
                        + "('users','employee_profiles','staff_access_grants',"
                        + "'supported_cities','operating_teams','listings') "
                        + "order by 1",
                String.class);
        assertEquals(List.of(
                "employee_profiles.base_salary",
                "listings.created_at",
                "operating_teams.created_at",
                "staff_access_grants.grant_reason_code",
                "supported_cities.created_at",
                "users.created_at"), authorityUpdateColumns);

        List<String> deletableRelations = jdbc.queryForList(
                "select relname from (values "
                        + "('lead_routing_queue'),('lead_preferred_localities'),"
                        + "('lead_shortlists'),('lead_shortlist_items'),"
                        + "('users'),('staff_access_grants'),('listings'),"
                        + "('visit_notification_outbox')) v(relname) "
                        + "where has_table_privilege(current_user,'public.'||relname,'DELETE') "
                        + "order by relname",
                String.class);
        assertEquals(List.of("lead_preferred_localities", "lead_shortlist_items"),
                deletableRelations);
        for (String sequence : List.of(
                "lead_routing_queue_id_seq", "lead_preferred_localities_id_seq",
                "lead_contact_preferences_id_seq", "lead_shortlists_id_seq",
                "lead_shortlist_items_id_seq", "lead_activities_id_seq",
                "lead_lookup_events_id_seq", "operational_audit_events_id_seq")) {
            assertTrue(jdbc.queryForObject(
                    "select has_sequence_privilege(current_user,?, 'USAGE')",
                    Boolean.class, "public." + sequence));
            assertFalse(jdbc.queryForObject(
                    "select has_sequence_privilege(current_user,?, 'SELECT')",
                    Boolean.class, "public." + sequence));
        }

        World world = world("acl-negative");
        long leadId = fixtureLead(
                world.cityId(), world.teamId(), world.owner().userId(), "ACL negative");
        assertRuntimeDenied("update users set role='ROLE_ADMIN' where id=?",
                world.owner().userId());
        assertRuntimeDenied("update staff_access_grants set capability='STAFF_ADMIN' where id=?",
                world.owner().grantIds().get(0));
        assertRuntimeDenied("delete from lead_routing_queue where id=?", leadId);
        assertRuntimeDenied("delete from users where id=?", world.owner().userId());
        assertRuntimeDenied("delete from staff_access_grants where id=?",
                world.owner().grantIds().get(0));
        assertRuntimeDenied("delete from listings where false");
        assertRuntimeDenied("delete from visit_notification_outbox where false");
        assertRuntimeDenied(
                "select last_value from public.lead_routing_queue_id_seq");

        long shortlistId = insertReturningLong(
                "insert into lead_shortlists(lead_id,version,created_at,updated_at) "
                        + "values (?,0,now(),now()) returning id", leadId);
        long shortlistItemId = insertReturningLong(
                "insert into lead_shortlist_items(shortlist_id,listing_id,position) "
                        + "values (?,?,0) returning id",
                shortlistId, fixtureProperty(world.cityId(), world.localityId(),
                        "ACL delete control"));
        long preferredId = insertReturningLong(
                "insert into lead_preferred_localities(lead_id,locality_id) "
                        + "values (?,?) returning id",
                leadId, world.localityId());
        try (Connection runtime = target.openRuntime()) {
            runtime.setAutoCommit(false);
            try (var deleteItem = runtime.prepareStatement(
                    "delete from lead_shortlist_items where id=?")) {
                deleteItem.setLong(1, shortlistItemId);
                assertEquals(1, deleteItem.executeUpdate());
            }
            try (var deletePreferred = runtime.prepareStatement(
                    "delete from lead_preferred_localities where id=?")) {
                deletePreferred.setLong(1, preferredId);
                assertEquals(1, deletePreferred.executeUpdate());
            }
            runtime.rollback();
        }
        assertEquals(1L, fixture.queryForObject(
                "select count(*) from lead_shortlist_items where id=?",
                Long.class, shortlistItemId));
        assertEquals(1L, fixture.queryForObject(
                "select count(*) from lead_preferred_localities where id=?",
                Long.class, preferredId));
    }

    @Test
    void childPersistenceFailureRollsBackAndSafeRetryCommitsOnce() throws Exception {
        World world = world("d11");
        long listingId = fixtureProperty(world.cityId(), world.localityId(), "D11 public home");
        ShareSetup setup = ownerShareSetup(world, listingId, "d11");
        fixture.update("insert into public.db_a01_activity_failure(lead_id,fail) "
                + "values (?,true) on conflict (lead_id) do update set fail=true", setup.leadId());
        Snapshot before = sensitiveSnapshot(setup.leadId());
        long receiptsBefore = scalar(
                "select count(*) from lead_command_receipts where action='LEAD_SHARE_PREPARE' "
                        + "and actor_user_id=?", world.owner().userId());
        UUID operationId = UUID.randomUUID();
        String body = shareBody(operationId, leadVersion(setup.leadId()),
                setup.shortlist().path("id").asLong(), setup.shortlist().path("version").asLong(),
                setup.preference().path("revision").asLong());

        HttpResponse<String> failed = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), body);
        assertEquals(500, failed.statusCode(), failed.body());
        assertEquals(before, sensitiveSnapshot(setup.leadId()));
        assertEquals(receiptsBefore, scalar(
                "select count(*) from lead_command_receipts where action='LEAD_SHARE_PREPARE' "
                        + "and actor_user_id=?", world.owner().userId()));

        fixture.update("update public.db_a01_activity_failure set fail=false where lead_id=?",
                setup.leadId());
        HttpResponse<String> retried = send("POST",
                "/api/v1/operations/leads/" + setup.leadId() + "/shares",
                world.owner().token(), body);
        assertEquals(201, retried.statusCode(), retried.body());
        assertEquals(before.shares() + 1, fixture.queryForObject(
                "select count(*) from lead_shares where lead_id=?",
                Long.class, setup.leadId()));
        assertEquals(before.shareEvents(), fixture.queryForObject(
                "select count(*) from lead_share_events event join lead_shares share "
                        + "on share.id=event.share_id where share.lead_id=?",
                Long.class, setup.leadId()));
        assertEquals(receiptsBefore + 1, scalar(
                "select count(*) from lead_command_receipts where action='LEAD_SHARE_PREPARE' "
                        + "and actor_user_id=?", world.owner().userId()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PATHOME_DB_A01_LEAD_BROWSER_TEST", matches = "true")
    void genuineConnectedChromiumPreservesOwnerIntakeAndSessionBoundaries() throws Exception {
        World world = world("browser");
        for (int index = 1; index <= 7; index++) {
            fixtureProperty(world.cityId(), world.localityId(),
                    "G10 genuine public home supporting result " + index);
        }
        long expectedProperty = fixtureProperty(
                world.cityId(), world.localityId(), "G10 genuine public home");
        Actor peer = staff("browser-peer-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_COORDINATE", "TEAM", null, world.teamId(), null, null));
        Actor supervisor = staff("browser-supervisor-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("OPS_SUPERVISE", "TEAM", null, world.teamId(), null, null));
        Actor admin = staff("browser-admin-" + UUID.randomUUID().toString().substring(0, 8),
                new GrantSpec("STAFF_ADMIN", "GLOBAL", null, null, null, null));
        long intakeLead = fixtureLead(
                world.cityId(), null, null, "G10 Browser Customer");

        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path repository = workingDirectory.getFileName().toString().equals("backend")
                ? workingDirectory.getParent() : workingDirectory;
        Path script = repository.resolve(
                "frontend-web/browser-verification/operations-leads/run.mjs");
        String evidenceValue = System.getenv("PATHOME_DB_A01_LEAD_BROWSER_EVIDENCE_DIR");
        Path evidence = DbA01BrowserProcess.requireFreshEvidencePath(repository, evidenceValue);
        assertTrue(script.toFile().isFile(), "Lead browser runner is missing");
        String grantRevokeToken = UUID.randomUUID().toString();
        HttpServer grantRevokeControl = startBrowserGrantRevokeControl(
                world.owner().grantIds().get(0), admin.userId(), grantRevokeToken);

        Map<String, String> fixtureEnvironment = new LinkedHashMap<>();
        fixtureEnvironment.put("PATHOME_BROWSER_BACKEND_PORT", String.valueOf(port));
        fixtureEnvironment.put("PATHOME_BROWSER_OWNER_TOKEN", world.owner().token());
        fixtureEnvironment.put("PATHOME_BROWSER_OWNER_ID",
                String.valueOf(world.owner().userId()));
        fixtureEnvironment.put("PATHOME_BROWSER_OWNER_GRANT_ID",
                String.valueOf(world.owner().grantIds().get(0)));
        fixtureEnvironment.put("PATHOME_BROWSER_GRANT_REVOKE_URL",
                "http://127.0.0.1:" + grantRevokeControl.getAddress().getPort()
                        + "/revoke-owner-grant");
        fixtureEnvironment.put("PATHOME_BROWSER_GRANT_REVOKE_TOKEN", grantRevokeToken);
        fixtureEnvironment.put("PATHOME_BROWSER_PEER_TOKEN", peer.token());
        fixtureEnvironment.put("PATHOME_BROWSER_PEER_ID", String.valueOf(peer.userId()));
        fixtureEnvironment.put("PATHOME_BROWSER_SUPERVISOR_TOKEN", supervisor.token());
        fixtureEnvironment.put("PATHOME_BROWSER_SUPERVISOR_ID",
                String.valueOf(supervisor.userId()));
        fixtureEnvironment.put("PATHOME_BROWSER_ADMIN_TOKEN", admin.token());
        fixtureEnvironment.put("PATHOME_BROWSER_ADMIN_ID", String.valueOf(admin.userId()));
        fixtureEnvironment.put("PATHOME_BROWSER_INTAKE_TOKEN", world.intake().token());
        fixtureEnvironment.put("PATHOME_BROWSER_INTAKE_ID",
                String.valueOf(world.intake().userId()));
        fixtureEnvironment.put("PATHOME_BROWSER_INTAKE_LEAD_ID", String.valueOf(intakeLead));
        fixtureEnvironment.put("PATHOME_BROWSER_EXPECTED_PROPERTY_ID",
                String.valueOf(expectedProperty));
        fixtureEnvironment.put("PATHOME_BROWSER_EVIDENCE_DIR", evidence.toString());
        fixtureEnvironment.put("PATHOME_BROWSER_STRICT_GENUINE", "true");

        DbA01BrowserProcess.Result result = DbA01BrowserLauncher.launch(
                repository, script, fixtureEnvironment, () -> grantRevokeControl.stop(0),
                Duration.ofSeconds(180));
        assertFalse(result.timedOut(), "Connected Lead browser batch exceeded 180 seconds; receipt: " + result.log());
        assertEquals(0, result.exitCode(), "Connected Lead browser batch failed; external receipt: " + result.log());
    }

    private HttpServer startBrowserGrantRevokeControl(
            long grantId, long adminId, String expectedToken) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/revoke-owner-grant", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())
                        || !expectedToken.equals(
                        exchange.getRequestHeaders().getFirst("X-Pathome-Test-Control"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                int updated = fixture.update(
                        "update staff_access_grants "
                                + "set revoked_at=clock_timestamp(),revoked_by_user_id=?,"
                                + "revoke_reason_code='DB_A01_BROWSER_GRANT_LOSS' "
                                + "where id=? and revoked_at is null",
                        adminId, grantId);
                byte[] body = ("{\"updated\":" + updated + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(
                        "Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(updated == 1 ? 200 : 409, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception exception) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private ShareSetup ownerShareSetup(World world, long listingId, String label) throws Exception {
        return ownerShareSetup(world, listingId, label, world.owner());
    }

    private ShareSetup ownerShareSetup(World world, long listingId, String label, Actor owner)
            throws Exception {
        long leadId = fixtureLead(world.cityId(), world.teamId(), owner.userId(), label);
        HttpResponse<String> consent = send("POST",
                "/api/v1/operations/leads/" + leadId + "/contact-preferences",
                owner.token(), contactBody(leadVersion(leadId), 0L, "SAME", "GRANTED"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, consent.statusCode(), consent.body());
        JsonNode preference = mapper.readTree(consent.body());
        HttpResponse<String> shortlist = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                owner.token(),
                "{\"expectedLeadVersion\":" + preference.path("leadVersion").asLong()
                        + ",\"listingIds\":[" + listingId + "]}",
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlist.statusCode(), shortlist.body());
        JsonNode shortlistView = mapper.readTree(shortlist.body());
        HttpResponse<String> prepared = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shares",
                owner.token(),
                shareBody(UUID.randomUUID(), shortlistView.path("leadVersion").asLong(),
                        shortlistView.path("id").asLong(), shortlistView.path("version").asLong(),
                        preference.path("revision").asLong()));
        assertEquals(201, prepared.statusCode(), prepared.body());
        UUID shareId = UUID.fromString(mapper.readTree(prepared.body()).path("id").asText());
        return new ShareSetup(leadId, preference, shortlistView, shareId);
    }

    private LeadPublicPropertyService.EligibleProperty currentEligibleProperty(
            UUID shareId, long listingId) throws Exception {
        Map<String, Object> property = fixture.queryForMap(
                "select l.bhk_count,l.property_type,l.total_area_sq_ft,l.version,"
                        + "r.monthly_rent,loc.sector_name,sc.display_name "
                        + "from listings l join rental_details r on r.id=l.id "
                        + "join localities loc on loc.id=l.canonical_locality_id "
                        + "join supported_cities sc on sc.id=loc.supported_city_id "
                        + "where l.id=?",
                listingId);
        String bhk = (String) property.get("bhk_count");
        String locality = (String) property.get("sector_name");
        String city = (String) property.get("display_name");
        String priorPreview = mapper.readTree(shareState(shareId))
                .path("preview_text").asText();
        String[] lines = priorPreview.split("\\R");
        assertTrue(lines.length >= 3, "Prepared preview lacks title and location lines");
        String title = lines[1];
        String location = lines[2];
        Number area = (Number) property.get("total_area_sq_ft");
        return new LeadPublicPropertyService.EligibleProperty(
                listingId,
                title,
                location,
                locality,
                city,
                bhk,
                (java.math.BigDecimal) property.get("monthly_rent"),
                area == null ? null : area.doubleValue(),
                ((Number) property.get("version")).longValue());
    }

    private void installPropertyGuardBypassMutant(
            LeadPublicPropertyService.EligibleProperty priorFacts) {
        if (!Boolean.getBoolean("pathome.o4.property-guard-bypass-mutant")) return;
        org.mockito.Mockito.doReturn(List.of(priorFacts))
                .when(propertyGuardFault)
                .requireCurrentPublicPropertiesForUpdate(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList());
    }

    private void makePropertyIneligible(long listingId) {
        assertEquals(1, fixture.update(
                "update listings set workflow_status='ARCHIVED', version=version+1 "
                        + "where id=?", listingId));
    }

    private String o4State(long leadId) {
        return fixture.queryForObject(
                "select jsonb_build_object("
                        + "'lead',(select to_jsonb(l) from lead_routing_queue l where l.id=?),"
                        + "'shortlists',coalesce((select jsonb_agg(to_jsonb(s) order by s.id) "
                        + "from lead_shortlists s where s.lead_id=?),'[]'::jsonb),"
                        + "'items',coalesce((select jsonb_agg(to_jsonb(i) order by i.id) "
                        + "from lead_shortlist_items i join lead_shortlists s on s.id=i.shortlist_id "
                        + "where s.lead_id=?),'[]'::jsonb),"
                        + "'shares',coalesce((select jsonb_agg(to_jsonb(s) order by s.id) "
                        + "from lead_shares s where s.lead_id=?),'[]'::jsonb),"
                        + "'shareEvents',coalesce((select jsonb_agg(to_jsonb(e) order by e.id) "
                        + "from lead_share_events e join lead_shares s on s.id=e.share_id "
                        + "where s.lead_id=?),'[]'::jsonb),"
                        + "'activities',coalesce((select jsonb_agg(to_jsonb(a) order by a.id) "
                        + "from lead_activities a where a.lead_id=?),'[]'::jsonb),"
                        + "'audit',coalesce((select jsonb_agg(to_jsonb(a) order by a.id) "
                        + "from operational_audit_events a "
                        + "where a.target_type='LEAD' and a.target_id=?),'[]'::jsonb),"
                        + "'receiptCount',(select count(*) from lead_command_receipts),"
                        + "'phoneOutboxCount',(select count(*) from lead_phone_delivery_outbox),"
                        + "'visitNotificationOutboxCount',"
                        + "(select count(*) from visit_notification_outbox),"
                        + "'systemNotificationCount',(select count(*) from system_notifications),"
                        + "'propertyVisitRequestCount',"
                        + "(select count(*) from property_visit_requests),"
                        + "'visitSessionCount',(select count(*) from visit_sessions),"
                        + "'tenantEntitlementAccountCount',"
                        + "(select count(*) from tenant_visit_entitlement_accounts),"
                        + "'entitlementLedgerCount',"
                        + "(select count(*) from visit_entitlement_ledger),"
                        + "'visitRoutingTaskCount',"
                        + "(select count(*) from visit_request_routing_tasks),"
                        + "'tenantVisitLogCount',(select count(*) from tenant_visit_logs),"
                        + "'visitStartChallengeCount',"
                        + "(select count(*) from visit_start_challenges)"
                        + ")::text",
                String.class, leadId, leadId, leadId, leadId, leadId, leadId, leadId);
    }

    private String shareState(UUID shareId) {
        return fixture.queryForObject(
                "select coalesce((select to_jsonb(s)::text from lead_shares s where s.id=?),"
                        + "'absent')", String.class, shareId);
    }

    private String receiptState(UUID operationId, String action) {
        return fixture.queryForObject(
                "select coalesce((select to_jsonb(r)::text from lead_command_receipts r "
                        + "where r.operation_id=? and r.action=?),'absent')",
                String.class, operationId, action);
    }

    private JsonNode historyItem(Actor actor, long leadId, UUID shareId) throws Exception {
        JsonNode page = mapper.readTree(send("GET",
                "/api/v1/operations/leads/" + leadId + "/shares?page=0&size=10",
                actor.token(), null).body());
        for (JsonNode item : page.path("items")) {
            if (shareId.toString().equals(item.path("id").asText())) return item;
        }
        throw new AssertionError("Prepared share is absent from current history: " + shareId);
    }

    private HttpResponse<String> eventWhileListingLocked(
            ShareSetup setup, Actor owner, long listingId, long waitMillis) throws Exception {
        try (Connection lock = fixture.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, listingId);
                statement.executeQuery().close();
            }
            CompletableFuture<HttpResponse<String>> event = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST",
                            "/api/v1/operations/leads/" + setup.leadId()
                                    + "/shares/" + setup.shareId() + "/events",
                            owner.token(),
                            "{\"eventId\":\"" + UUID.randomUUID()
                                    + "\",\"type\":\"CLIPBOARD_COPIED\"}");
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            Thread.sleep(250);
            if (event.isDone()) {
                HttpResponse<String> response = event.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Event completed before the held listing lock released: "
                        + response.statusCode() + " " + response.body());
            }
            Thread.sleep(waitMillis);
            lock.rollback();
            return event.get(20, TimeUnit.SECONDS);
        }
    }

    private void awaitDatabaseWait(String queryPattern) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Long blocked = fixture.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() "
                            + "and usename=? and pid<>pg_backend_pid() "
                            + "and wait_event_type='Lock' and query ilike ?",
                    Long.class, DbA01LeadTestTarget.RUNTIME_ROLE, queryPattern);
            if (blocked != null && blocked > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected PostgreSQL lock wait was not observed: " + queryPattern);
    }

    private void awaitGateCounter(long before,
                                  CompletableFuture<HttpResponse<String>> event)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (event.isDone()) {
                HttpResponse<String> response = event.get(1, TimeUnit.SECONDS);
                throw new AssertionError(
                        "Event completed before reaching the lock gate: "
                                + response.statusCode() + " " + response.body());
            }
            Long current = fixture.queryForObject(
                    "select last_value from public.db_a01_event_gate_seq", Long.class);
            if (current != null && current > before) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Event insert did not reach the gate: "
                + fixture.queryForList(
                "select wait_event_type,wait_event,left(query,240) as query "
                        + "from pg_stat_activity where datname=current_database() "
                        + "and usename=? and pid<>pg_backend_pid() and state='active'",
                DbA01LeadTestTarget.RUNTIME_ROLE));
    }

    private void awaitRevocationWait(CompletableFuture<Void> revocation) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (revocation.isDone()) {
                revocation.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Revocation completed before the admitted event released its guard");
            }
            Long blocked = fixture.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() "
                            + "and usename=? and pid<>pg_backend_pid() "
                            + "and wait_event_type='Lock' and query ilike '%staff_access_grants%'",
                    Long.class, DbA01LeadTestTarget.MIGRATOR_ROLE);
            if (blocked != null && blocked > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Revocation waiter was not observed: "
                + fixture.queryForList(
                "select wait_event_type,wait_event,left(query,240) as query "
                        + "from pg_stat_activity where datname=current_database() "
                        + "and pid<>pg_backend_pid() and state='active'"));
    }

    private void awaitRuntimeDatabaseWait(
            CompletableFuture<HttpResponse<String>> event) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (event.isDone()) {
                HttpResponse<String> response = event.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Event completed without a lock wait: "
                        + response.statusCode() + " " + response.body());
            }
            Long blocked = fixture.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() "
                            + "and usename=? and pid<>pg_backend_pid() "
                            + "and wait_event_type='Lock'",
                    Long.class, DbA01LeadTestTarget.RUNTIME_ROLE);
            if (blocked != null && blocked > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Runtime lock wait was not observed: "
                + fixture.queryForList(
                "select wait_event_type,wait_event,left(query,240) as query "
                        + "from pg_stat_activity where datname=current_database() "
                        + "and usename=? and pid<>pg_backend_pid() and state='active'",
                DbA01LeadTestTarget.RUNTIME_ROLE));
    }

    private static String shareBody(UUID operationId, long leadVersion, long shortlistId,
                                    long shortlistVersion, long preferenceRevision) {
        return "{\"operationId\":\"" + operationId + "\",\"expectedLeadVersion\":" + leadVersion
                + ",\"shortlistId\":" + shortlistId
                + ",\"expectedShortlistVersion\":" + shortlistVersion
                + ",\"contactPreferenceRevision\":" + preferenceRevision
                + ",\"mode\":\"MANUAL_BUSINESS_HANDOFF\"}";
    }

    private static String shortlistCommand(long expectedLeadVersion, List<Long> listingIds) {
        return "{\"expectedLeadVersion\":" + expectedLeadVersion
                + ",\"listingIds\":[" + listingIds.stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private void assertShortlistReceiptConcealedAfterAuthorityLoss(
            World world, long listingId, String authorityLossSql, Object... arguments)
            throws Exception {
        long leadId = fixtureLead(
                world.cityId(), world.teamId(), world.owner().userId(),
                "Shortlist authority loss " + UUID.randomUUID());
        UUID operationId = UUID.randomUUID();
        String original = shortlistCommand(leadVersion(leadId), List.of(listingId));
        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.owner().token(), original,
                "Idempotency-Key", operationId.toString());
        assertEquals(201, created.statusCode(), created.body());
        String changed = original.replace("[" + listingId + "]", "[]");
        assertEquals(1, fixture.update(authorityLossSql, arguments));

        HttpResponse<String> changedReplay = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.owner().token(), changed,
                "Idempotency-Key", operationId.toString());
        HttpResponse<String> exactReplay = send("POST",
                "/api/v1/operations/leads/" + leadId + "/shortlists",
                world.owner().token(), original,
                "Idempotency-Key", operationId.toString());
        assertEquals(403, changedReplay.statusCode(), changedReplay.body());
        assertEquals(403, exactReplay.statusCode(), exactReplay.body());
        assertFalse(changedReplay.body().contains("different shortlist request"));
        assertFalse(exactReplay.body().contains("\"listingIds\""));
    }

    private World world(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long cityId = insertReturningLong(
                "insert into supported_cities(code,display_name,active) values (?,?,true) returning id",
                "db-a01-" + label + "-" + suffix,
                "Bhopal Municipal Central Residential Corridor");
        long teamId = insertReturningLong(
                "insert into operating_teams(city_id,code,display_name,active) "
                        + "values (?,?,?,true) returning id",
                cityId, "db-a01-team-" + label + "-" + suffix,
                "North Bhopal Residential Coordination Team");
        long localityId = insertReturningLong(
                "insert into localities(city,sector_name,supported_city_id) values (?,?,?) returning id",
                "DB A01 " + label, "Synthetic " + suffix, cityId);
        Actor owner = staff("owner-" + label + "-" + suffix,
                new GrantSpec("OPS_COORDINATE", "TEAM", null, teamId, null, null));
        Actor intake = staff("intake-" + label + "-" + suffix,
                new GrantSpec("OPS_INTAKE", "CITY", cityId, null, null, null));
        return new World(cityId, teamId, localityId, owner, intake);
    }

    private Actor staff(String label, GrantSpec... grants) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "db-a01-" + label + "-" + suffix + "@example.test";
        long userId = insertReturningLong(
                "insert into users(email,full_name,phone_number,role) values (?,?,?,'ROLE_TENANT') returning id",
                email, label, "+9197" + Math.abs(UUID.randomUUID().hashCode() % 100_000_000L));
        fixture.update("insert into employee_profiles(user_id,role_type,closed_deals_count,staff_active,"
                        + "staff_activated_at,version) values (?, 'OPERATIONS', 0, true, now(), 0)",
                userId);
        List<Long> grantIds = new ArrayList<>();
        for (GrantSpec grant : grants) {
            grantIds.add(insertReturningLong(
                    "insert into staff_access_grants(user_id,capability,scope_type,city_id,"
                            + "team_id,effective_at,expires_at,grant_reason_code,provisioning_source) "
                            + "values (?,?,?,?,?,?,?, 'DB_A01_TEST', 'ADMIN_API') returning id",
                    userId, grant.capability(), grant.scopeType(), grant.cityId(),
                    grant.teamId(), Timestamp.from(grant.startsAt() == null
                            ? Instant.now().minusSeconds(3600) : grant.startsAt()),
                    grant.expiresAt() == null ? null : Timestamp.from(grant.expiresAt())));
        }
        return new Actor(userId, email,
                jwt.generateToken(userId, email, "ROLE_TENANT"), List.copyOf(grantIds));
    }

    private long fixtureLead(long cityId, Long teamId, Long ownerId, String name) {
        return insertReturningLong(
                "insert into lead_routing_queue(tenant_name,phone_number,contact_phone_normalized,"
                        + "status,intent,supported_city_id,operating_team_id,owner_user_id,"
                        + "legacy_provenance,property_type,bhk,currency) "
                        + "values (?, '+919876543210', '+919876543210', 'NEW', 'RENT', ?,?,?,"
                        + "'VERIFIED_MANUAL','Flat','2 BHK','INR') returning id",
                name, cityId, teamId, ownerId);
    }

    private long fixtureProperty(long cityId, long localityId, String title) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long listingId = insertReturningLong(
                "insert into listings(title,description,address,city,sector,listing_type,status,"
                        + "property_type,bhk_count,workflow_status,canonical_locality_id,"
                        + "location_resolution,version,published_at) "
                        + "values (?,?,?,(select display_name from supported_cities where id=?),"
                        + "?,'RENT','ACTIVE','FLAT','2 BHK','PUBLISHED',?,"
                        + "'CANONICAL',0,now()) returning id",
                title + " " + suffix, "DB-A01 synthetic public home", "Synthetic address",
                cityId, "Synthetic sector", localityId);
        fixture.update("insert into rental_details(id,monthly_rent,security_deposit,bachelor_allowed) "
                        + "values (?,18000.00,36000.00,true)", listingId);
        return listingId;
    }

    private long insertReturningLong(String sql, Object... args) {
        Long value = fixture.queryForObject(sql, Long.class, args);
        return value == null ? -1L : value;
    }

    private long leadVersion(long leadId) {
        Long version = fixture.queryForObject(
                "select version from lead_routing_queue where id = ?", Long.class, leadId);
        return version == null ? -1L : version;
    }

    private Snapshot sensitiveSnapshot(long leadId) {
        return new Snapshot(
                scalar("select count(*) from lead_contact_preferences where lead_id = ?", leadId),
                scalar("select count(*) from lead_shares where lead_id = ?", leadId),
                scalar("select count(*) from lead_share_events event join lead_shares share "
                        + "on share.id=event.share_id where share.lead_id = ?", leadId),
                scalar("select count(*) from lead_activities where lead_id = ?", leadId),
                scalar("select count(*) from operational_audit_events where target_type='LEAD' "
                        + "and target_id = ?", leadId),
                scalar("select count(*) from visit_notification_outbox"),
                scalar("select count(*) from visit_sessions"),
                scalar("select count(*) from property_visit_requests"),
                scalar("select count(*) from tenant_visit_entitlement_accounts"),
                scalar("select count(*) from visit_entitlement_ledger"),
                scalar("select count(*) from visit_request_routing_tasks"),
                scalar("select count(*) from tenant_visit_logs"),
                scalar("select count(*) from visit_start_challenges"));
    }

    private long scalar(String sql, Object... args) {
        Long value = fixture.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static void assertRuntimeDenied(String sql, Object... args) throws Exception {
        DbA01LeadTestTarget.Target target = DbA01LeadTestTarget.requireFromEnvironment();
        try (Connection runtime = target.openRuntime()) {
            runtime.setAutoCommit(false);
            try (var statement = runtime.prepareStatement(sql)) {
                for (int index = 0; index < args.length; index++) {
                    statement.setObject(index + 1, args[index]);
                }
                statement.execute();
                throw new AssertionError("Expected runtime permission denial for: " + sql);
            } catch (java.sql.SQLException exception) {
                assertEquals("42501", exception.getSQLState(),
                        "Expected PostgreSQL permission denial: " + sql);
            } finally {
                runtime.rollback();
            }
        }
    }

    private long insertExpiringGrantAndRevokeExisting(
            Actor actor, long teamId, long lifetimeMillis) {
        long grantId = insertReturningLong(
                "insert into staff_access_grants(user_id,capability,scope_type,team_id,"
                        + "effective_at,expires_at,grant_reason_code,provisioning_source) "
                        + "values (?,'OPS_COORDINATE','TEAM',?,"
                        + "clock_timestamp()-interval '1 second',"
                        + "clock_timestamp()+(?::double precision * interval '1 millisecond'),"
                        + "'DB_A01_LOCK_WAIT_EXPIRY','ADMIN_API') returning id",
                actor.userId(), teamId, lifetimeMillis);
        for (Long existingGrantId : actor.grantIds()) {
            fixture.update("update staff_access_grants set revoked_at=clock_timestamp(), "
                            + "revoked_by_user_id=?, revoke_reason_code='DB_A01_SUPERSEDED' "
                            + "where id=? and revoked_at is null",
                    actor.userId(), existingGrantId);
        }
        return grantId;
    }

    private ListingWait executeWhileListingLocked(
            long listingId, String token, String path, String body, String queryPattern,
            long actorId, long expiringGrantId) throws Exception {
        return executeWhileListingLocked(listingId, token, path, body, queryPattern,
                actorId, expiringGrantId, null, null, "POST");
    }

    private ListingWait executeWhileListingLocked(
            long listingId, String token, String path, String body, String queryPattern,
            long actorId, long expiringGrantId, String header, String value) throws Exception {
        return executeWhileListingLocked(listingId, token, path, body, queryPattern,
                actorId, expiringGrantId, header, value, "POST");
    }

    private ListingWait executeWhileListingLocked(
            long listingId, String token, String path, String body, String queryPattern,
            long actorId, long expiringGrantId, String header, String value, String method)
            throws Exception {
        try (Connection lock = fixture.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            long blockerPid = scalarOn(lock, "select pg_backend_pid()");
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, listingId);
                statement.executeQuery().close();
            }
            long unrelatedPid;
            try (Connection unrelated = fixture.getDataSource().getConnection()) {
                unrelatedPid = scalarOn(unrelated, "select pg_backend_pid()");
            }
            Instant databaseTimeBefore = instantOn(lock,
                    "select clock_timestamp()");
            boolean grantValidBeforeWait;
            try (Connection observer = fixture.getDataSource().getConnection()) {
                grantValidBeforeWait = booleanOn(observer,
                        "select exists("
                                + "select 1 from staff_access_grants g "
                                + "join employee_profiles p on p.user_id=g.user_id and p.staff_active "
                                + "where g.id=? and g.user_id=? and g.revoked_at is null "
                                + "and g.effective_at<=clock_timestamp() "
                                + "and (g.expires_at is null or clock_timestamp()<g.expires_at))",
                        expiringGrantId, actorId);
            }
            CompletableFuture<HttpResponse<String>> request = CompletableFuture.supplyAsync(() -> {
                try {
                    return send(method, path, token, body, header, value);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                ListingWaiter waiter = awaitCorrelatedListingWaiter(
                        blockerPid, queryPattern, request);
                awaitGrantExpiry(expiringGrantId, request);
                assertFalse(request.isDone(),
                        "Request completed before the expired grant could be re-evaluated");
                Instant databaseTimeAtExpiry = instantOn(lock,
                        "select clock_timestamp()");
                boolean expiredWhileBlocked = booleanOn(lock,
                        "select clock_timestamp()>=expires_at from staff_access_grants where id=?",
                        expiringGrantId);
                List<Long> blockingPids = fixture.queryForList(
                        "select unnest(pg_blocking_pids(?::integer))",
                        Long.class, waiter.pid());
                lock.rollback();
                HttpResponse<String> response = request.get(20, TimeUnit.SECONDS);
                Instant databaseTimeAfterRelease = instantOn(lock,
                        "select clock_timestamp()");
                return new ListingWait(
                        response, blockerPid, waiter.pid(), unrelatedPid,
                        blockerPid, blockingPids, waiter.query(),
                        databaseTimeBefore, databaseTimeAtExpiry, databaseTimeAfterRelease,
                        grantValidBeforeWait, expiredWhileBlocked);
            } finally {
                if (!lock.getAutoCommit()) {
                    lock.rollback();
                }
            }
        }
    }

    private ListingWaiter awaitCorrelatedListingWaiter(
            long blockerPid, String queryPattern,
            CompletableFuture<HttpResponse<String>> request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (request.isDone()) {
                HttpResponse<String> response = request.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Request completed before the listing wait: "
                        + response.statusCode() + " " + response.body());
            }
            List<Map<String, Object>> rows = fixture.queryForList(
                    "select pid,wait_event_type,wait_event,query "
                            + "from pg_stat_activity "
                            + "where datname=current_database() and usename=? "
                            + "and pid<>pg_backend_pid() "
                            + "and ?=any(pg_blocking_pids(pid)) "
                            + "order by pid",
                    DbA01LeadTestTarget.RUNTIME_ROLE, blockerPid);
            if (!rows.isEmpty()) {
                if (rows.size() != 1) {
                    throw new AssertionError(
                            "Expected one correlated listing waiter, found " + rows);
                }
                Map<String, Object> row = rows.get(0);
                Object waitEventType = row.get("wait_event_type");
                if (waitEventType != null && !"Lock".equals(waitEventType)) {
                    throw new AssertionError("Waiter is not blocked on a PostgreSQL lock: " + row);
                }
                long waiterPid = ((Number) row.get("pid")).longValue();
                boolean listingRelationLock;
                try (Connection observer = fixture.getDataSource().getConnection()) {
                    listingRelationLock = booleanOn(
                            observer,
                            "select exists("
                                    + "select 1 from pg_locks "
                                    + "where pid=? and database="
                                    + "(select oid from pg_database where datname=current_database()) "
                                    + "and relation='public.listings'::regclass "
                                    + "and granted and mode='RowShareLock')",
                            waiterPid);
                }
                if (!listingRelationLock) {
                    throw new AssertionError(
                            "Blocked runtime PID " + waiterPid
                                    + " did not hold the reviewed listings RowShareLock");
                }
                String reviewedQuery =
                        "select id from listings where id in (?) order by id for update";
                if (!queryPattern.equals("%listings%for update%")) {
                    throw new AssertionError("Unexpected reviewed waiter SQL pattern: "
                            + queryPattern);
                }
                return new ListingWaiter(
                        waiterPid,
                        reviewedQuery + " [source-correlated; query text hidden from migrator]");
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No runtime waiter was blocked by listing PID " + blockerPid
                + " for query pattern " + queryPattern + "; activity="
                + fixture.queryForList(
                "select pid,state,wait_event_type,wait_event,"
                        + "pg_blocking_pids(pid) as blockers,"
                        + "left(query,160) as query "
                        + "from pg_stat_activity "
                        + "where datname=current_database() and usename=? "
                        + "and pid<>pg_backend_pid() order by pid",
                DbA01LeadTestTarget.RUNTIME_ROLE));
    }

    private void awaitGrantExpiry(
            long grantId, CompletableFuture<HttpResponse<String>> request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (request.isDone()) {
                HttpResponse<String> response = request.get(1, TimeUnit.SECONDS);
                throw new AssertionError(
                        "Request completed before grant expiry: "
                                + response.statusCode() + " " + response.body());
            }
            Boolean expired = fixture.queryForObject(
                    "select clock_timestamp()>=expires_at "
                            + "from staff_access_grants where id=?",
                    Boolean.class, grantId);
            if (Boolean.TRUE.equals(expired)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Grant did not expire during the observed listing wait");
    }

    private static long scalarOn(Connection connection, String sql, Object... args)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Expected one scalar row");
                return rows.getLong(1);
            }
        }
    }

    private static int updateOn(Connection connection, String sql, Object... args)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static Instant instantOn(Connection connection, String sql, Object... args)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Expected one timestamp row");
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private static boolean booleanOn(Connection connection, String sql, Object... args)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Expected one boolean row");
                return rows.getBoolean(1);
            }
        }
    }

    private static String contactBody(long leadVersion, long revision, String choice,
                                      String consentStatus) {
        String extra = switch (choice) {
            case "DIFFERENT" -> ",\"countryCallingCode\":\"+91\",\"number\":\"9876501234\"";
            default -> "";
        };
        return "{\"expectedLeadVersion\":" + leadVersion
                + ",\"expectedPreferenceRevision\":" + revision
                + ",\"channel\":\"WHATSAPP\",\"destinationChoice\":\"" + choice + "\""
                + extra
                + ",\"consentStatus\":\"" + consentStatus + "\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        return send(method, path, token, body, null, null);
    }

    private HttpResponse<String> send(String method, String path, String token, String body,
                                      String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", "http://localhost:5173");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (header != null) request.header(header, value);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private LostResponse sendWithoutClientAcknowledgement(
            String method, String path, String token, String body,
            String header, String value) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket proxy = new ServerSocket(0, 1, loopback)) {
            proxy.setSoTimeout(20_000);
            CompletableFuture<ServerReply> captured = CompletableFuture.supplyAsync(() -> {
                try (Socket client = proxy.accept()) {
                    byte[] forwarded = readHttpRequest(client.getInputStream());
                    try (Socket upstream = new Socket()) {
                        upstream.connect(new InetSocketAddress(loopback, port), 5_000);
                        upstream.setSoTimeout(20_000);
                        OutputStream output = upstream.getOutputStream();
                        output.write(forwarded);
                        output.flush();
                        upstream.shutdownOutput();
                        String response = new String(readToEnd(upstream.getInputStream()),
                                StandardCharsets.UTF_8);
                        int firstLineEnd = response.indexOf("\r\n");
                        return new ServerReply(firstLineEnd < 0
                                ? response : response.substring(0, firstLineEnd), response);
                    }
                    // The accepted client socket is closed without forwarding any response byte.
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(loopback, proxy.getLocalPort()), 5_000);
                client.setSoTimeout(30_000);
                OutputStream output = client.getOutputStream();
                output.write(httpRequest(method, path, token, body, header, value));
                output.flush();
                client.shutdownOutput();
                int clientRead = client.getInputStream().read();
                ServerReply reply = captured.get(30, TimeUnit.SECONDS);
                return new LostResponse(clientRead, reply.statusLine(), reply.response());
            }
        }
    }

    private byte[] httpRequest(String method, String path, String token, String body,
                               String header, String value) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = method + " " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Origin: http://localhost:5173\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Content-Type: application/json\r\n"
                + "Accept: application/json\r\n"
                + (header == null ? "" : header + ": " + value + "\r\n")
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        byte[] request = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, request, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, request, headerBytes.length, bodyBytes.length);
        return request;
    }

    private static byte[] readHttpRequest(InputStream input) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("Client closed before request headers completed");
            }
            request.write(next);
            matched = switch (matched) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                default -> next == '\n' ? 4 : 0;
            };
        }
        String headers = request.toString(StandardCharsets.UTF_8);
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(
                        line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Client closed before declared body completed");
        }
        request.write(body);
        return request.toByteArray();
    }

    private static byte[] readToEnd(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            response.write(buffer, 0, read);
        }
        return response.toByteArray();
    }

    private record GrantSpec(String capability, String scopeType, Long cityId, Long teamId,
                             Instant startsAt, Instant expiresAt) {}

    private record Actor(long userId, String email, String token, List<Long> grantIds) {}

    private record World(long cityId, long teamId, long localityId, Actor owner, Actor intake) {}

    private record ShareSetup(long leadId, JsonNode preference, JsonNode shortlist, UUID shareId) {}

    private record LostResponse(int clientRead, String statusLine, String serverResponse) {}

    private record ServerReply(String statusLine, String response) {}

    private record ListingWaiter(long pid, String query) {}

    private record ListingWait(
            HttpResponse<String> response,
            long blockerPid,
            long waiterPid,
            long unrelatedPid,
            long observedBlockerPid,
            List<Long> blockingPids,
            String waiterQuery,
            Instant databaseTimeBefore,
            Instant databaseTimeAtExpiry,
            Instant databaseTimeAfterRelease,
            boolean grantValidBeforeWait,
            boolean expiredWhileBlocked) {}

    private record Snapshot(long preferences, long shares, long shareEvents, long activities,
                            long audits, long outbox, long visitSessions, long visitRequests,
                            long tenantEntitlementAccounts, long entitlementLedger,
                            long visitRoutingTasks, long tenantVisitLogs,
                            long visitStartChallenges) {}
}
