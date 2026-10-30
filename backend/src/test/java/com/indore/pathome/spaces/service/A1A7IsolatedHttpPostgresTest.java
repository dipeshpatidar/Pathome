package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.A1A7HarnessContextInitializer;
import com.indore.pathome.spaces.support.A1A7IsolatedTarget;
import com.indore.pathome.spaces.support.A1A7RuntimeConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.default_schema=${PATHOME_A1_A7_SCHEMA}",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-a1-a7-http-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=pathome-a1-a7-visit-otp-secret-32-bytes",
        "pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "pathome.crm.contact-encryption-key-id=a1a7-primary",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "pathome.crm.phone-verification-simulator-enabled=true",
        "pathome.crm.secure-cookies=false",
        "pathome.crm.phone-delivery-poll-delay-ms=3600000",
        "cloudinary.api-key=a1-a7-test-key",
        "cloudinary.api-secret=a1-a7-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000",
        "search.learning.autonomous.enabled=false"
})
@Import(A1A7RuntimeConfiguration.class)
@ContextConfiguration(initializers = A1A7HarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class A1A7IsolatedHttpPostgresTest {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String PRIVATE_MOBILE = "+919812345678";
    private static final String PRIVATE_MOBILE_DISPLAY = "+91 9812345678";
    private static final String PRIVATE_EMAIL = "private.lessor.a1a7@example.test";
    private static final String CONSENT_VERSION = "a1a7-enquiry-ownership-v1";

    @LocalServerPort private int port;
    @Autowired private JwtUtils jwt;
    @Autowired private ObjectMapper mapper;
    @Autowired private LeadPhoneDeliveryWorker phoneWorker;
    @Autowired private LeadPhoneDeliveryClaimService phoneClaims;
    @Autowired private LeadPhoneDeliveryCompletionService phoneCompletions;
    @Autowired private LeadDataProtection protection;
    @Autowired private LeadAssistanceSecretService secrets;
    @Autowired private LeadPhoneDeliveryOutboxRepository deliveryOutbox;
    @Autowired private LeadPhoneChallengeRepository phoneChallenges;
    @Autowired private LeadRoutingQueueRepository leadRepository;
    @Autowired private LeadSourceEventRepository sourceEventRepository;
    @Autowired private DatabaseClock databaseClock;
    @Autowired private LeadPhoneDeliveryProvider phoneProvider;
    @Autowired private StaffAccessService staffAccess;
    @Autowired private OperationalSecurityGuards securityGuards;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired @Qualifier("a1a7RunnerJdbc") private JdbcTemplate runner;
    @Autowired @Qualifier("a1a7MigratorJdbc") private JdbcTemplate migrator;

    @Test
    void rahulJourneyPersistsLeadProofPublicShareAvailabilityAndOwnRequestThenRoutesAndClaims()
            throws Exception {
        World world = world("happy");
        Lead lead = capture(world, world.ravi(), "Rahul Synthetic", "+919700001001");
        assertEquals(0L, lead.version());
        assertEquals("NEW", text("select status from lead_routing_queue where id=?", lead.id()));
        assertEquals(world.ravi().userId(),
                longValue("select owner_user_id from lead_routing_queue where id=?", lead.id()));
        assertEquals("PROCESSED",
                text("select processing_state from lead_source_events where id=?", lead.sourceId()));

        JsonNode duplicate = json(send("POST", "/api/v1/operations/leads/duplicate-candidates",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "cityId", world.cityId(), "contactPhone", "+919700001001")), Map.of()));
        assertEquals(1, duplicate.path("candidates").size());
        assertTrue(duplicate.path("candidates").get(0).path("maskedContact").asText()
                .endsWith("1001"));
        assertFalse(duplicate.toString().contains("+919700001001"));

        JsonNode unverifiedShareSetup = shareSetup(world, lead, world.listingId());
        long shortlistId = unverifiedShareSetup.path("shortlistId").asLong();
        long shortlistVersion = unverifiedShareSetup.path("shortlistVersion").asLong();
        long preferenceRevision = unverifiedShareSetup.path("preferenceRevision").asLong();
        JsonNode share = unverifiedShareSetup.path("share");
        assertEquals(201, unverifiedShareSetup.path("shareStatus").asInt());
        assertTrue(share.path("previewText").asText()
                .contains("Your shortlisted home from Pathome"));
        assertFalse(share.toString().contains(PRIVATE_MOBILE));
        assertFalse(share.toString().contains(PRIVATE_EMAIL));
        assertFalse(share.toString().contains("Rahul Synthetic"));
        assertNoPii(publicProperty(world.listingId()));

        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertEquals("ACCEPTED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", challenge.id()));
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "and payload_key_version='PURGED' from lead_phone_delivery_outbox "
                + "where challenge_id=?", challenge.id()));
        assertTrue(material.code().matches("[0-9]{6}"));
        assertNoSecret(challenge.issueBody(), material.code());

        HttpResponse<String> wrong = confirmOperator(world.ravi(), lead, challenge,
                flip(material.code()), UUID.randomUUID());
        assertEquals(400, wrong.statusCode(), wrong.body());
        assertEquals(1L, longValue("select failed_attempts from lead_phone_challenges where id=?",
                challenge.id()));

        PhoneChallenge afterWrong = new PhoneChallenge(challenge.id(),
                longValue("select version from lead_phone_challenges where id=?", challenge.id()),
                challenge.generation(), challenge.issueBody());
        JsonNode verified = json(confirmOperator(world.ravi(), lead, afterWrong,
                material.code(), UUID.randomUUID()));
        assertEquals("VERIFIED", verified.path("state").asText());
        assertEquals("OE_ENTERED", verified.path("entryMethod").asText());
        assertEquals(0L, longValue("select count(*) from users where id=? and mobile_verified_at is not null",
                world.rahul().userId()));
        assertEquals(0L, longValue("select count(*) from visit_start_challenges"));
        assertEquals(0L, longValue("select count(*) from visit_sessions"));

        Lead refreshed = lead(lead.id());
        JsonNode availabilityCreated = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries",
                world.ravi().token(),
                mapper.writeValueAsString(Map.of(
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "sourceEventId", lead.sourceId().toString(),
                        "expectedLeadVersion", refreshed.version(),
                        "expectedShortlistVersion", shortlistVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "genuineEnquiryAttested", true)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertEquals("PENDING", availabilityCreated.path("outcome").asText());
        assertEquals(201, status("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries",
                world.ravi().token(),
                mapper.writeValueAsString(Map.of(
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "sourceEventId", lead.sourceId().toString(),
                        "expectedLeadVersion", refreshed.version(),
                        "expectedShortlistVersion", shortlistVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "genuineEnquiryAttested", true)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertEquals("PENDING",
                text("select outcome from lead_lessor_availability_enquiries where id=?::uuid",
                        availabilityCreated.path("enquiryId").asText()));

        JsonNode contact = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries/"
                        + availabilityCreated.path("enquiryId").asText() + "/lessor-contact",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", refreshed.version(),
                        "expectedShortlistVersion", shortlistVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "expectedEnquiryVersion", availabilityCreated.path("version").asLong(),
                        "expectedContactPhoneRevision", refreshed.phoneRevision(),
                        "genuineEnquiryAttested", true)), Map.of()));
        assertEquals("CONTACT_AVAILABLE", contact.path("status").asText());
        assertEquals(PRIVATE_MOBILE_DISPLAY, contact.path("mobile").asText());
        assertEquals(1L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));

        JsonNode response = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries/"
                        + availabilityCreated.path("enquiryId").asText() + "/responses",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", refreshed.version(),
                        "expectedEnquiryVersion", availabilityCreated.path("version").asLong(),
                        "outcome", "LESSOR_REPORTED_AVAILABLE",
                        "note", "Synthetic lessor said tomorrow may be possible; no appointment booked.")),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertEquals("LESSOR_REPORTED_AVAILABLE", response.path("outcome").asText());
        assertEquals(0L, longValue("select count(*) from property_visit_requests "
                + "where listing_id=?", world.listingId()));
        assertEquals(0L, longValue("select count(*) from visit_sessions"));
        assertEquals(0L, longValue("select count(*) from tenant_visit_entitlement_accounts"));

        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", shortlistVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", preferenceRevision)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertTrue(proposal.path("handoffUrl").asText()
                .startsWith("https://pathome.test/property/" + world.listingId() + "#visit="));
        String proposalToken = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);

        Continuation rahulContext = exchange(proposalToken);
        JsonNode ownRequest = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, rahulContext, true),
                Map.of("Cookie", "pathome_visit_context=" + rahulContext.token())));
        assertTrue(ownRequest.path("created").asBoolean());
        assertEquals("RECEIVED", ownRequest.path("status").asText());
        long requestId = ownRequest.path("requestId").asLong();
        assertEquals(world.rahul().userId(),
                longValue("select tenant_id from property_visit_requests where id=?", requestId));
        assertEquals("RECEIVED", text("select status from property_visit_requests where id=?", requestId));
        assertEquals(1L, longValue("select count(*) from visit_request_routing_tasks where request_id=?",
                requestId));
        JsonNode tenantHistory = json(send("GET",
                "/api/v1/tenant/visit-requests?page=0", world.rahul().token(),
                null, Map.of()));
        boolean currentRequestVisible = false;
        for (JsonNode item : tenantHistory.path("requests")) {
            if (item.path("requestId").asLong() == requestId
                    && item.path("propertyId").asLong() == world.listingId()
                    && "RECEIVED".equals(item.path("status").asText())) {
                currentRequestVisible = true;
            }
        }
        assertTrue(currentRequestVisible,
                "the exact canonical Request must appear in the Tenant's own history");
        JsonNode claimableBeforeRoute = json(send("GET",
                "/api/v1/operations/claimable-work?page=0&size=20&targetType=REQUEST",
                world.ravi().token(), null, Map.of()));
        for (JsonNode item : claimableBeforeRoute.path("items")) {
            assertFalse("REQUEST".equals(item.path("targetType").asText())
                            && item.path("targetId").asLong() == requestId,
                    "unrouted work must not be exposed as OE-claimable");
        }

        JsonNode routed = json(send("GET", "/api/v1/operations/intake-routing/visit-requests/"
                + requestId + "/candidates?cityId=" + world.cityId() + "&page=0&size=20",
                world.admin().token(), null, Map.of()));
        long requestVersion = routed.path("request").path("version").asLong();
        long cityVersion = routed.path("city").path("version").asLong();
        JsonNode team = routed.path("teams").path("items").get(0);
        assertEquals(204, status("POST", "/api/v1/operations/intake-routing/visit-requests/"
                + requestId + "/route", world.admin().token(), mapper.writeValueAsString(Map.of(
                        "expectedRequestVersion", requestVersion,
                        "cityId", world.cityId(),
                        "expectedCityVersion", cityVersion,
                        "teamId", team.path("id").asLong(),
                        "expectedTeamVersion", team.path("version").asLong(),
                        "reasonCode", "INITIAL_ROUTING")), Map.of()));
        requestVersion = longValue("select version from property_visit_requests where id=?", requestId);
        JsonNode claimableAfterRoute = json(send("GET",
                "/api/v1/operations/claimable-work?page=0&size=20&targetType=REQUEST",
                world.ravi().token(), null, Map.of()));
        boolean visibleAfterRoute = false;
        for (JsonNode item : claimableAfterRoute.path("items")) {
            if ("REQUEST".equals(item.path("targetType").asText())
                    && item.path("targetId").asLong() == requestId) {
                visibleAfterRoute = true;
            }
        }
        assertTrue(visibleAfterRoute,
                "the routed Request must become visible to its authorized Team coordinator");
        assertEquals(204, status("POST", "/api/v1/operations/visit-requests/" + requestId + "/claim",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedRequestVersion", requestVersion,
                        "reasonCode", "CLAIM_WORK")), Map.of()));
        assertEquals(world.ravi().userId(),
                longValue("select coordinator_user_id from property_visit_requests where id=?", requestId));

        JsonNode afterRequest = json(send("GET",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals/"
                        + proposal.path("proposalId").asText(), world.ravi().token(), null, Map.of()));
        JsonNode claim = afterRequest.path("associationClaims").get(0);
        assertEquals("PENDING", claim.path("state").asText());
        assertEquals(200, status("POST", "/api/v1/operations/leads/" + lead.id()
                        + "/visit-proposals/" + proposal.path("proposalId").asText()
                        + "/association-claims/" + claim.path("claimId").asText() + "/decision",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "decision", "CONFIRM",
                        "expectedClaimVersion", claim.path("version").asLong(),
                        "reasonCode", "CUSTOMER_CONSENT_VERIFIED")),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertEquals(world.rahul().userId(),
                longValue("select linked_user_id from lead_routing_queue where id=?", lead.id()));

        assertNoStore(send("GET", "/api/v1/operations/leads/" + lead.id(),
                world.ravi().token(), null, Map.of()));
        assertEquals(404, status("GET", "/api/v1/operations/leads/" + lead.id(),
                world.peer().token(), null, Map.of()));
    }

    @Test
    void unreceivedOtpRetainsLeadAllowsPublicShareButDeniesLeadLessorRevealAndTenantCanRequestDirectly()
            throws Exception {
        World world = world("fallback");
        Lead lead = capture(world, world.ravi(), "Rahul Fallback", "+919700001002");
        JsonNode shareSetup = shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        runner.update("update lead_phone_delivery_outbox set state='FAILED', "
                + "last_error_code='SIMULATED_NOT_RECEIVED' where challenge_id=?", challenge.id());

        assertEquals("NEW", text("select status from lead_routing_queue where id=?", lead.id()));
        assertNotNull(text("select owner_user_id from lead_routing_queue where id=?", lead.id()));
        assertEquals(1L, longValue("select count(*) from lead_shares where lead_id=?", lead.id()));
        assertTrue(shareSetup.path("share").path("publicLinks").toString()
                .contains("/property/" + world.listingId()));
        assertNoPii(publicProperty(world.listingId()));

        Lead current = lead(lead.id());
        UUID enquiryId = createAvailability(world, lead, shareSetup, true);
        assertEquals(409, status("POST", "/api/v1/operations/leads/" + lead.id()
                        + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", current.version(),
                        "expectedShortlistVersion", shareSetup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "expectedEnquiryVersion", 0,
                        "expectedContactPhoneRevision", current.phoneRevision(),
                        "genuineEnquiryAttested", true)), Map.of()));
        assertEquals(0L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));

        JsonNode request = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, null, false), Map.of()));
        assertTrue(request.path("created").asBoolean());
        assertEquals("RECEIVED", request.path("status").asText());
        assertEquals("PENDING",
                text("select state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals(0L, longValue("select count(*) from visit_sessions"));
        assertEquals(0L, longValue("select count(*) from tenant_visit_entitlement_accounts"));
    }

    @Test
    void expiredExhaustedAndReplayedCodesFailClosedWhileLeadAndMediaRemainTruthful()
            throws Exception {
        World world = world("otp-negative");
        Lead lead = capture(world, world.ravi(), "Rahul OTP Negatives", "+919700001003");
        PhoneChallenge first = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(first.id());
        phoneWorker.dispatchDue();
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", first.id()));
        for (int attempt = 1; attempt <= 5; attempt++) {
            PhoneChallenge current = new PhoneChallenge(first.id(),
                    longValue("select version from lead_phone_challenges where id=?", first.id()),
                    first.generation(), first.issueBody());
            HttpResponse<String> wrong = confirmOperator(world.ravi(), lead, current,
                    flip(material.code()), UUID.randomUUID());
            assertEquals(attempt == 5 ? 429 : 400, wrong.statusCode(), wrong.body());
        }
        assertEquals("EXHAUSTED", text("select state from lead_phone_challenges where id=?", first.id()));
        assertEquals(5, intValue("select failed_attempts from lead_phone_challenges where id=?",
                first.id()));
        PhoneChallenge exhausted = new PhoneChallenge(first.id(),
                longValue("select version from lead_phone_challenges where id=?", first.id()),
                first.generation(), first.issueBody());
        assertEquals(429, confirmOperator(world.ravi(), lead, exhausted, material.code(),
                UUID.randomUUID()).statusCode());
        assertEquals("NEW", text("select status from lead_routing_queue where id=?", lead.id()));

        Lead expiredLead = capture(world, world.ravi(), "Rahul Expired", "+919700001004");
        PhoneChallenge expired = issuePhone(world, expiredLead);
        runner.update("update lead_phone_challenges set created_at=clock_timestamp()-interval '10 minutes',"
                + "expires_at=clock_timestamp()-interval '5 minutes' where id=?", expired.id());
        assertEquals(410, confirmOperator(world.ravi(), expiredLead, expired, "000000",
                UUID.randomUUID()).statusCode());
        assertEquals("NEW", text("select status from lead_routing_queue where id=?", expiredLead.id()));
        assertEquals(0L, longValue("select count(*) from visit_start_challenges"));
        assertEquals(0L, longValue("select count(*) from users where id=? "
                + "and mobile_verified_at is not null", world.rahul().userId()));
    }

    @Test
    void nonOwnerSupervisorAdminGeForeignTeamAndRevokedGrantCannotRevealLeadContact()
            throws Exception {
        World world = world("authority");
        Lead lead = capture(world, world.ravi(), "Rahul Authority", "+919700001005");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", challenge.id()));
        HttpResponse<String> verified = confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID());
        assertEquals(200, verified.statusCode(), verified.body());
        assertTrue(verified.body().contains("\"state\":\"VERIFIED\""));
        UUID enquiryId = createAvailability(world, lead, setup, true);
        String revealBody = revealBody(world, lead, setup, enquiryId);
        for (Actor denied : List.of(world.peer(), world.supervisor(), world.admin(), world.ge(),
                world.foreignRavi())) {
            HttpResponse<String> response = send("POST", "/api/v1/operations/leads/" + lead.id()
                    + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                    denied.token(), revealBody, Map.of());
            assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                    denied.label() + " received " + response.statusCode());
            assertFalse(response.body().contains(PRIVATE_MOBILE));
        }
        runner.update("update staff_access_grants set revoked_at=clock_timestamp(), "
                        + "revoked_by_user_id=?, revoke_reason_code='A1A7_ISOLATED_REVOKE' "
                        + "where id=? and revoked_at is null",
                world.admin().userId(), world.ravi().grantId());
        HttpResponse<String> revoked = send("POST", "/api/v1/operations/leads/" + lead.id()
                + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                world.ravi().token(), revealBody, Map.of());
        assertEquals(403, revoked.statusCode(), revoked.body());
        assertFalse(revoked.body().contains(PRIVATE_MOBILE));
        assertEquals(0L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));
    }

    @Test
    void foreignAssistanceTargetsAreConcealedLikeAbsentLeadsBeforeStaleVersionConflicts()
            throws Exception {
        World world = world("concealment");
        Lead foreignLead = capture(world, world.ravi(), "Rahul Concealment",
                "+919700001099");
        JsonNode setup = shareSetup(world, foreignLead, world.listingId());
        long absentLeadId = 999_999_999L;
        String phoneBody = mapper.writeValueAsString(Map.of(
                "expectedLeadVersion", -1L,
                "sourceEvent", Map.of("id", foreignLead.sourceId().toString()),
                "expectedContactPhoneRevision", lead(foreignLead.id()).phoneRevision(),
                "noticeAgreed", true));
        String availabilityBody = mapper.writeValueAsString(Map.of(
                "shortlistId", setup.path("shortlistId").asLong(),
                "listingId", world.listingId(),
                "sourceEventId", foreignLead.sourceId().toString(),
                "expectedLeadVersion", -1L,
                "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                "expectedListingVersion", world.listingVersion(),
                "genuineEnquiryAttested", true));
        String proposalBody = mapper.writeValueAsString(Map.of(
                "expectedLeadVersion", -1L,
                "shortlistId", setup.path("shortlistId").asLong(),
                "listingId", world.listingId(),
                "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                "expectedListingVersion", world.listingVersion(),
                "contactPreferenceRevision", setup.path("preferenceRevision").asLong()));
        List<String> suffixes = List.of("phone-verifications", "availability-enquiries",
                "visit-proposals");
        List<String> bodies = List.of(phoneBody, availabilityBody, proposalBody);
        for (int index = 0; index < suffixes.size(); index++) {
            String suffix = suffixes.get(index);
            HttpResponse<String> foreign = send("POST",
                    "/api/v1/operations/leads/" + foreignLead.id() + "/" + suffix,
                    world.foreignRavi().token(), bodies.get(index),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            HttpResponse<String> absent = send("POST",
                    "/api/v1/operations/leads/" + absentLeadId + "/" + suffix,
                    world.foreignRavi().token(), bodies.get(index),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(404, foreign.statusCode(), suffix + ": " + foreign.body());
            assertEquals(404, absent.statusCode(), suffix + ": " + absent.body());
            JsonNode foreignError = json(foreign);
            JsonNode absentError = json(absent);
            assertEquals(absentError.path("error").asText(),
                    foreignError.path("error").asText(), suffix);
            assertEquals(absentError.path("message").asText(),
                    foreignError.path("message").asText(), suffix);
            assertFalse(foreign.body().contains("Rahul Concealment"));
            assertFalse(foreign.body().contains(PRIVATE_MOBILE));
            assertNoStore(foreign);
        }
    }

    @Test
    void highVolumeRevealsAreAuditedWithoutCountOnlyDenial()
            throws Exception {
        World world = world("anomaly");
        Lead lead = capture(world, world.ravi(), "Rahul Volume", "+919700001006");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", challenge.id()));
        assertEquals(200, confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID()).statusCode());
        UUID enquiryId = createAvailability(world, lead, setup, true);
        String body = revealBody(world, lead, setup, enquiryId);
        for (int index = 0; index < 80; index++) {
            HttpResponse<String> revealed = send("POST", "/api/v1/operations/leads/" + lead.id()
                    + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                    world.ravi().token(), body, Map.of());
            assertEquals(200, revealed.statusCode(), "reveal " + index + ": " + revealed.body());
            assertEquals("CONTACT_AVAILABLE",
                    json(revealed).path("status").asText());
        }
        assertEquals(80L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));
        JsonNode anomalies = json(send("GET", "/api/v1/operations/lead-contact-anomalies?page=0&size=20",
                world.admin().token(), null, Map.of()));
        assertTrue(anomalies.isArray() && anomalies.size() >= 1);
        assertFalse(anomalies.toString().contains(PRIVATE_MOBILE));
    }

    @Test
    void expiredPossibleDispatchBecomesUnknownWithoutResendAndLaterWorkProceeds()
            throws Exception {
        World world = world("dispatch-crash");
        Lead firstLead = capture(world, world.ravi(), "Rahul Dispatch Crash",
                "+919700001011");
        PhoneChallenge first = issuePhone(world, firstLead);
        Lead secondLead = capture(world, world.ravi(), "Rahul Dispatch Recovery",
                "+919700001012");
        PhoneChallenge second = issuePhone(world, secondLead);
        DeliveryMaterial secondMaterial = deliveryMaterial(second.id());
        runner.update("update lead_phone_delivery_outbox set state='DISPATCHING',attempts=1,"
                        + "lease_token=gen_random_uuid(),lease_until=clock_timestamp()-"
                        + "interval '1 second' where challenge_id=?", first.id());

        phoneWorker.dispatchDue();

        assertEquals("UNKNOWN", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", first.id()));
        assertEquals(1, intValue("select attempts from lead_phone_delivery_outbox "
                + "where challenge_id=?", first.id()));
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", first.id()));
        assertEquals("UNKNOWN", text("select delivery_state from lead_phone_challenges "
                + "where id=?", first.id()));
        assertEquals("ACCEPTED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", second.id()));
        UUID secondOutbox = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                second.id()));
        phoneCompletions.complete(secondOutbox, LeadPhoneDeliveryState.DELIVERED,
                "stale-provider-id", null, UUID.randomUUID());
        assertEquals("ACCEPTED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", second.id()));
        assertEquals(200, confirmOperator(world.ravi(), secondLead, second,
                secondMaterial.code(), UUID.randomUUID()).statusCode());
    }

    @Test
    void staleDiscoveryAfterCompetingAcceptedCompletionCannotSuppressConfirmableCode()
            throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-stale-accepted");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Accepted", "+919700004001");
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        UUID outboxId = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch resumeStaleWorker = new CountDownLatch(1);
        LeadPhoneDeliveryOutboxRepository delayedOutbox = pauseAfterDiscovery(
                deliveryOutbox, discovered, resumeStaleWorker);
        LeadPhoneDeliveryClaimService staleClaims = claimService(delayedOutbox);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicReference<LeadPhoneDeliveryWorker.DispatchTicket> staleTicket = new AtomicReference<>();
        CompletableFuture<Void> staleWorker = CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(ignored ->
                        staleTicket.set(staleClaims.claimNext())));
        try {
            assertTrue(discovered.await(10, TimeUnit.SECONDS),
                    "stale worker must pause after non-authoritative discovery");
            phoneWorker.dispatchDue();
            assertEquals("ACCEPTED", text(
                    "select state from lead_phone_delivery_outbox where id=?", outboxId));
            assertEquals("PENDING", text(
                    "select state from lead_phone_challenges where id=?", challenge.id()));
        } finally {
            resumeStaleWorker.countDown();
        }
        staleWorker.get(20, TimeUnit.SECONDS);

        assertNull(staleTicket.get());
        assertEquals("ACCEPTED", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals("ACCEPTED", text(
                "select delivery_state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals("PENDING", text(
                "select state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals(1, intValue("select attempts from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertTrue(booleanValue("select provider_message_id like 'simulated:%' "
                + "from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals(200, confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID()).statusCode());
    }

    @Test
    void staleDiscoveryCannotStealAnActiveCommittedDispatchLease() throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-active-lease");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Active Lease",
                "+919700004002");
        PhoneChallenge challenge = issuePhone(world, lead);
        UUID outboxId = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch resumeStaleWorker = new CountDownLatch(1);
        LeadPhoneDeliveryOutboxRepository delayedOutbox = pauseAfterDiscovery(
                deliveryOutbox, discovered, resumeStaleWorker);
        LeadPhoneDeliveryClaimService staleClaims = claimService(delayedOutbox);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicReference<LeadPhoneDeliveryWorker.DispatchTicket> staleTicket = new AtomicReference<>();
        CompletableFuture<Void> staleWorker = CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(ignored ->
                        staleTicket.set(staleClaims.claimNext())));
        LeadPhoneDeliveryWorker.DispatchTicket competing;
        try {
            assertTrue(discovered.await(10, TimeUnit.SECONDS),
                    "stale worker must pause after non-authoritative discovery");
            competing = phoneClaims.claimNext();
            assertNotNull(competing);
            assertEquals("DISPATCHING", text(
                    "select state from lead_phone_delivery_outbox where id=?", outboxId));
        } finally {
            resumeStaleWorker.countDown();
        }
        staleWorker.get(20, TimeUnit.SECONDS);

        assertNull(staleTicket.get());
        assertEquals("DISPATCHING", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals(competing.attemptId().toString(), text(
                "select lease_token::text from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertEquals(1, intValue("select attempts from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertEquals("PENDING", text(
                "select state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals("QUEUED", text(
                "select delivery_state from lead_phone_challenges where id=?", challenge.id()));
        phoneCompletions.complete(outboxId, LeadPhoneDeliveryState.ACCEPTED,
                "simulated-competing", null, competing.attemptId());
        assertEquals("ACCEPTED", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
    }

    @Test
    void staleDiscoveryCannotEraseVerifiedCustomerProofAfterCompetingCompletion()
            throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-stale-verified");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Verified",
                "+919700004005");
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        UUID outboxId = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch resumeStaleWorker = new CountDownLatch(1);
        LeadPhoneDeliveryClaimService staleClaims = claimService(
                pauseAfterDiscovery(deliveryOutbox, discovered, resumeStaleWorker));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicReference<LeadPhoneDeliveryWorker.DispatchTicket> staleTicket = new AtomicReference<>();
        CompletableFuture<Void> staleWorker = CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(ignored ->
                        staleTicket.set(staleClaims.claimNext())));
        try {
            assertTrue(discovered.await(10, TimeUnit.SECONDS),
                    "stale worker must pause after non-authoritative discovery");
            phoneWorker.dispatchDue();
            assertEquals(200, confirmOperator(world.ravi(), lead, challenge,
                    material.code(), UUID.randomUUID()).statusCode());
        } finally {
            resumeStaleWorker.countDown();
        }
        staleWorker.get(20, TimeUnit.SECONDS);

        assertNull(staleTicket.get());
        assertEquals("ACCEPTED", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals("ACCEPTED", text(
                "select delivery_state from lead_phone_challenges where id=?",
                challenge.id()));
        assertEquals("VERIFIED", text(
                "select state from lead_phone_challenges where id=?", challenge.id()));
        assertNotNull(text("select verified_at::text from lead_phone_challenges where id=?",
                challenge.id()));
    }

    @Test
    void claimFailpointRollsBackChallengeAndDeliveryEnvelopeWithoutPartialEffect()
            throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-claim-failpoint");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Claim Failure",
                "+919700004003");
        PhoneChallenge challenge = issuePhone(world, lead);
        String schema = System.getenv(A1A7IsolatedTarget.SCHEMA_FLAG);
        installFailpoint(schema, "n1_claim_failure", "lead_phone_delivery_outbox");
        try {
            assertThrows(RuntimeException.class, phoneClaims::claimNext);
        } finally {
            removeFailpoint(schema, "n1_claim_failure", "lead_phone_delivery_outbox");
        }

        assertEquals("QUEUED", text(
                "select state from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        assertEquals(0, intValue(
                "select attempts from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        assertFalse(booleanValue(
                "select payload_ciphertext='' from lead_phone_delivery_outbox "
                        + "where challenge_id=?", challenge.id()));
        assertEquals("PENDING", text(
                "select state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals("QUEUED", text(
                "select delivery_state from lead_phone_challenges where id=?",
                challenge.id()));
        assertNotNull(phoneClaims.claimNext());
    }

    @Test
    void completionFailpointRollsBackAndSameFencedAttemptRetriesOnce() throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-completion-failpoint");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Completion Failure",
                "+919700004004");
        PhoneChallenge challenge = issuePhone(world, lead);
        LeadPhoneDeliveryWorker.DispatchTicket ticket = phoneClaims.claimNext();
        assertNotNull(ticket);
        UUID outboxId = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        String schema = System.getenv(A1A7IsolatedTarget.SCHEMA_FLAG);
        installFailpoint(schema, "n1_completion_failure", "lead_phone_challenges");
        try {
            assertThrows(RuntimeException.class, () -> phoneCompletions.complete(
                    outboxId, LeadPhoneDeliveryState.ACCEPTED, "simulated-once",
                    null, ticket.attemptId()));
        } finally {
            removeFailpoint(schema, "n1_completion_failure", "lead_phone_challenges");
        }

        assertEquals("DISPATCHING", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals(ticket.attemptId().toString(), text(
                "select lease_token::text from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertFalse(booleanValue(
                "select payload_ciphertext='' from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertEquals("QUEUED", text(
                "select delivery_state from lead_phone_challenges where id=?",
                challenge.id()));

        phoneCompletions.complete(outboxId, LeadPhoneDeliveryState.ACCEPTED,
                "simulated-once", null, ticket.attemptId());
        assertEquals("ACCEPTED", text(
                "select state from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals("ACCEPTED", text(
                "select delivery_state from lead_phone_challenges where id=?",
                challenge.id()));
        assertEquals(1, intValue("select attempts from lead_phone_delivery_outbox where id=?",
                outboxId));
        assertTrue(booleanValue(
                "select payload_ciphertext='' and payload_iv='' "
                        + "from lead_phone_delivery_outbox where id=?", outboxId));
    }

    @Test
    void staleDiscoveryAfterCandidateRemovalReturnsNoTicketWithoutPartialWrite()
            throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("n1-missing-candidate");
        Lead lead = capture(world, world.ravi(), "Rahul N1 Missing Candidate",
                "+919700004006");
        PhoneChallenge challenge = issuePhone(world, lead);
        UUID outboxId = UUID.fromString(text(
                "select id::text from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch resumeStaleWorker = new CountDownLatch(1);
        LeadPhoneDeliveryClaimService staleClaims = claimService(
                pauseAfterDiscovery(deliveryOutbox, discovered, resumeStaleWorker));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicReference<LeadPhoneDeliveryWorker.DispatchTicket> staleTicket = new AtomicReference<>();
        CompletableFuture<Void> staleWorker = CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(ignored ->
                        staleTicket.set(staleClaims.claimNext())));
        try {
            assertTrue(discovered.await(10, TimeUnit.SECONDS),
                    "stale worker must pause after non-authoritative discovery");
            runner.update("delete from lead_phone_delivery_outbox where id=?", outboxId);
        } finally {
            resumeStaleWorker.countDown();
        }
        staleWorker.get(20, TimeUnit.SECONDS);

        assertNull(staleTicket.get());
        assertEquals(0L, longValue(
                "select count(*) from lead_phone_delivery_outbox where id=?", outboxId));
        assertEquals("PENDING", text(
                "select state from lead_phone_challenges where id=?", challenge.id()));
        assertEquals(0L, longValue(
                "select count(*) from lead_phone_delivery_outbox where challenge_id=?",
                challenge.id()));
    }

    @Test
    void phoneRevisionRoundTripAndPostLockExpiryCannotReviveOrDispatchStaleProof()
            throws Exception {
        World world = world("otp-revision");
        Lead lead = capture(world, world.ravi(), "Rahul Revision", "+919700001015");
        PhoneChallenge stale = issuePhone(world, lead);
        runner.update("update lead_routing_queue set phone_number=?,contact_phone_normalized=?,"
                        + "contact_phone_revision=contact_phone_revision+1 where id=?",
                "+919700001016", "+919700001016", lead.id());
        runner.update("update lead_routing_queue set phone_number=?,contact_phone_normalized=?,"
                        + "contact_phone_revision=contact_phone_revision+1 where id=?",
                "+919700001015", "+919700001015", lead.id());
        phoneWorker.dispatchDue();
        assertEquals("SUPPRESSED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", stale.id()));
        assertEquals("CANCELLED", text("select state from lead_phone_challenges where id=?",
                stale.id()));
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", stale.id()));

        PhoneChallenge current = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(current.id());
        CompletableFuture<Void> dispatch;
        long blockerPid;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from lead_routing_queue where id=? for update")) {
                statement.setLong(1, lead.id());
                statement.executeQuery().close();
            }
            blockerPid = longValueOn(lock, "select pg_backend_pid()");
            dispatch = CompletableFuture.runAsync(phoneWorker::dispatchDue);
            String witness = awaitRuntimeWait(blockerPid, 10_000);
            runner.update("update lead_phone_challenges set created_at=clock_timestamp()"
                            + "-interval '10 minutes',expires_at=clock_timestamp()-"
                            + "interval '5 minutes' where id=?", current.id());
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/otp-post-lock-expiry-witness.txt"), witness);
            lock.commit();
        }
        dispatch.get(20, TimeUnit.SECONDS);
        assertEquals("SUPPRESSED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", current.id()));
        assertEquals("CANCELLED", text("select state from lead_phone_challenges where id=?",
                current.id()));
        assertTrue(material.code().matches("[0-9]{6}"));
    }

    @Test
    void dispatchExpiryAcrossEveryFinalSecurityGuardIsSuppressed() throws Exception {
        List<GuardBlocker> guards = List.of(
                new GuardBlocker("city", "select id from supported_cities where id=? for update",
                        world -> world.cityId()),
                new GuardBlocker("team", "select id from operating_teams where id=? for update",
                        world -> world.ravi().grantId() == null ? world.cityId()
                                : longValue("select team_id from staff_access_grants where id=?",
                                world.ravi().grantId())),
                new GuardBlocker("profile",
                        "select user_id from employee_profiles where user_id=? for update",
                        world -> world.ravi().userId()),
                new GuardBlocker("grant",
                        "select id from staff_access_grants where id=? for update",
                        world -> world.ravi().grantId()));
        for (GuardBlocker guard : guards) {
            clearQueuedPhoneDeliveries();
            World world = world("guard-dispatch-" + guard.name());
            Lead lead = capture(world, world.ravi(), "Rahul Guard " + guard.name(),
                    "+919700002" + String.format("%03d", guards.indexOf(guard)));
            PhoneChallenge challenge = issuePhone(world, lead);
            runner.update("update lead_phone_challenges set created_at=clock_timestamp()"
                            + "-interval '5 minutes',expires_at=clock_timestamp()+"
                            + "interval '2 seconds' where id=?", challenge.id());

            CompletableFuture<Void> dispatch;
            long blockerPid;
            try (Connection lock = runner.getDataSource().getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement(guard.sql())) {
                    statement.setObject(1, guard.value().applyAsLong(world));
                    statement.executeQuery().close();
                }
                blockerPid = longValueOn(lock, "select pg_backend_pid()");
                dispatch = CompletableFuture.runAsync(phoneWorker::dispatchDue);
                String witness = awaitRuntimeWait(blockerPid, 10_000);
                String waitObservedAt = textValueOn(lock,
                        "select clock_timestamp()::text");
                try (var statement = lock.createStatement()) {
                    statement.execute("select pg_sleep(4)");
                }
                String releaseAt = textValueOn(lock, "select clock_timestamp()::text");
                Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                        .resolve("tests/f05-dispatch-" + guard.name() + "-guard-wait.txt"),
                        "guard=" + guard.name() + "\nwait_observed_at=" + waitObservedAt
                                + "\nrelease_at=" + releaseAt + "\n" + witness);
                lock.commit();
            }
            dispatch.get(20, TimeUnit.SECONDS);
            assertEquals("SUPPRESSED", text("select state from lead_phone_delivery_outbox "
                    + "where challenge_id=?", challenge.id()), guard.name());
            assertEquals("CANCELLED", text("select state from lead_phone_challenges where id=?",
                    challenge.id()), guard.name());
            assertEquals(0, intValue("select attempts from lead_phone_delivery_outbox "
                    + "where challenge_id=?", challenge.id()), guard.name());
            assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                    + "from lead_phone_delivery_outbox where challenge_id=?", challenge.id()),
                    guard.name());
        }
    }

    @Test
    void closedLeadQueuedDeliveryIsSuppressedWithoutBlockingLaterEligibleWork()
            throws Exception {
        clearQueuedPhoneDeliveries();
        World world = world("closed-dispatch");
        Lead closedLead = capture(world, world.ravi(), "Rahul Closed Dispatch",
                "+919700002100");
        PhoneChallenge closedChallenge = issuePhone(world, closedLead);
        Lead eligibleLead = capture(world, world.ravi(), "Rahul Eligible Dispatch",
                "+919700002101");
        PhoneChallenge eligibleChallenge = issuePhone(world, eligibleLead);
        runner.update("update lead_routing_queue set status='CLOSED' where id=?",
                closedLead.id());

        phoneWorker.dispatchDue();

        assertEquals("SUPPRESSED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", closedChallenge.id()));
        assertEquals("CANCELLED", text("select state from lead_phone_challenges where id=?",
                closedChallenge.id()));
        assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where challenge_id=?", closedChallenge.id()));
        assertEquals("ACCEPTED", text("select state from lead_phone_delivery_outbox "
                + "where challenge_id=?", eligibleChallenge.id()));
    }

    @Test
    void customerConfirmationExpiryAcrossEveryFinalSecurityGuardFailsClosed()
            throws Exception {
        List<GuardBlocker> guards = List.of(
                new GuardBlocker("city", "select id from supported_cities where id=? for update",
                        world -> world.cityId()),
                new GuardBlocker("team", "select id from operating_teams where id=? for update",
                        world -> longValue("select team_id from staff_access_grants where id=?",
                                world.ravi().grantId())),
                new GuardBlocker("profile",
                        "select user_id from employee_profiles where user_id=? for update",
                        world -> world.ravi().userId()),
                new GuardBlocker("grant",
                        "select id from staff_access_grants where id=? for update",
                        world -> world.ravi().grantId()));
        for (GuardBlocker guard : guards) {
            World world = world("guard-confirm-" + guard.name());
            Lead lead = capture(world, world.ravi(), "Rahul Confirm " + guard.name(),
                    "+919700003" + String.format("%03d", guards.indexOf(guard)));
            PhoneChallenge challenge = issuePhone(world, lead);
            DeliveryMaterial material = deliveryMaterial(challenge.id());
            String token = material.confirmationUrl()
                    .substring(material.confirmationUrl().indexOf("token=") + 6);
            runner.update("update lead_phone_challenges set created_at=clock_timestamp()"
                            + "-interval '5 minutes',expires_at=clock_timestamp()+"
                            + "interval '2 seconds' where id=?", challenge.id());

            CompletableFuture<HttpResponse<String>> confirmation;
            long blockerPid;
            try (Connection lock = runner.getDataSource().getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement(guard.sql())) {
                    statement.setObject(1, guard.value().applyAsLong(world));
                    statement.executeQuery().close();
                }
                blockerPid = longValueOn(lock, "select pg_backend_pid()");
                confirmation = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send("POST", "/api/v1/lead-phone-verifications/confirm",
                                null, mapper.writeValueAsString(Map.of(
                                        "token", token, "code", material.code())), Map.of());
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
                String witness = awaitRuntimeWait(blockerPid, 10_000);
                String waitObservedAt = textValueOn(lock,
                        "select clock_timestamp()::text");
                try (var statement = lock.createStatement()) {
                    statement.execute("select pg_sleep(4)");
                }
                String releaseAt = textValueOn(lock, "select clock_timestamp()::text");
                Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                        .resolve("tests/f05-confirm-" + guard.name() + "-guard-wait.txt"),
                        "guard=" + guard.name() + "\nwait_observed_at=" + waitObservedAt
                                + "\nrelease_at=" + releaseAt + "\n" + witness);
                lock.commit();
            }
            HttpResponse<String> response = confirmation.get(20, TimeUnit.SECONDS);
            assertEquals(410, response.statusCode(), guard.name() + ": " + response.body());
            assertEquals("EXPIRED", text("select state from lead_phone_challenges where id=?",
                    challenge.id()), guard.name());
            assertEquals(null, text("select verified_at::text from lead_phone_challenges "
                    + "where id=?", challenge.id()), guard.name());
            assertTrue(booleanValue("select payload_ciphertext='' and payload_iv='' "
                    + "from lead_phone_delivery_outbox where challenge_id=?", challenge.id()),
                    guard.name());
        }
    }

    @Test
    void concurrentOwnRequestsProduceOneRequestAndOneEnrollment() throws Exception {
        World world = world("request-race");
        ObjectNode firstBody = (ObjectNode) mapper.readTree(ownRequestBody(world, null, false));
        String requestBody = mapper.writeValueAsString(firstBody);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, world.listingId());
                statement.executeQuery().close();
            }
            long blockerPid = longValueOn(lock, "select pg_backend_pid()");
            first = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return send("POST", "/api/v1/tenant/property-visit-commands",
                            world.rahul().token(), requestBody, Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            second = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return send("POST", "/api/v1/tenant/property-visit-commands",
                            world.rahul().token(), requestBody, Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            start.countDown();
            String witness = awaitRuntimeWait(blockerPid, 10_000);
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/request-race-lock-wait.txt"), witness);
            lock.rollback();
        }
        JsonNode firstResult = json(first.get(20, TimeUnit.SECONDS));
        JsonNode secondResult = json(second.get(20, TimeUnit.SECONDS));
        assertEquals(1, (firstResult.path("created").asBoolean() ? 1 : 0)
                + (secondResult.path("created").asBoolean() ? 1 : 0));
        assertEquals(firstResult.path("requestId").asLong(),
                secondResult.path("requestId").asLong());
        assertEquals(1L, longValue("select count(*) from property_visit_requests "
                + "where tenant_id=? and listing_id=?",
                world.rahul().userId(), world.listingId()));
        assertEquals(1L, longValue("select count(*) from visit_request_routing_tasks "
                + "where request_id=?", firstResult.path("requestId").asLong()));
    }

    @Test
    void changedPropertyBetweenReviewAndCanonicalRequestFailsBeforeAnySideEffect()
            throws Exception {
        World world = world("dc05-changed-property");
        String requestBody = ownRequestBody(world, null, false);
        runner.update("update listings set workflow_status='PAUSED', version=version+1, "
                + "updated_at=clock_timestamp() where id=?", world.listingId());

        HttpResponse<String> changed = send("POST",
                "/api/v1/tenant/property-visit-commands", world.rahul().token(),
                requestBody, Map.of());

        assertEquals(409, changed.statusCode(), changed.body());
        assertEquals(0L, longValue("select count(*) from property_visit_requests "
                + "where tenant_id=? and listing_id=?", world.rahul().userId(),
                world.listingId()));
        assertEquals(0L, longValue("select count(*) from visit_request_routing_tasks t "
                + "join property_visit_requests r on r.id=t.request_id "
                + "where r.tenant_id=? and r.listing_id=?", world.rahul().userId(),
                world.listingId()));
        assertEquals(0L, longValue("select count(*) from visit_sessions where tenant_id=?",
                world.rahul().userId()));
        assertEquals(0L, longValue("select count(*) from tenant_visit_entitlement_accounts "
                + "where user_id=?", world.rahul().userId()));
    }

    @Test
    void requestAndRoutingFailureRollsBackThenSameCommandRetriesOnce() throws Exception {
        World world = world("dc13-request-rollback");
        String schema = System.getenv(A1A7IsolatedTarget.SCHEMA_FLAG);
        assertTrue(schema != null && schema.matches("^[a-z][a-z0-9_]{0,62}$"));
        ObjectNode command = (ObjectNode) mapper.readTree(ownRequestBody(world, null, false));
        UUID operationId = UUID.fromString(command.path("operationId").asText());
        String body = mapper.writeValueAsString(command);
        psqlExecute("create function " + schema + ".dc13_fail_routing_request() "
                + "returns trigger language plpgsql as $$ begin "
                + "raise exception 'DC13_SYNTHETIC_ROUTING_FAILURE'; end $$");
        psqlExecute("create trigger dc13_fail_routing_request before insert on "
                + schema + ".visit_request_routing_tasks for each row execute function "
                + schema + ".dc13_fail_routing_request()");
        try {
            HttpResponse<String> failed = send("POST",
                    "/api/v1/tenant/property-visit-commands", world.rahul().token(),
                    body, Map.of());
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(0L, longValue("select count(*) from property_visit_requests "
                    + "where tenant_id=? and listing_id=?", world.rahul().userId(),
                    world.listingId()));
            assertEquals(0L, longValue("select count(*) from lead_assistance_command_receipts "
                    + "where actor_user_id=? and operation_id=?",
                    world.rahul().userId(), operationId));
            assertEquals(0L, longValue("select count(*) from operational_audit_events "
                    + "where actor_user_id=? and action_code='TENANT_VISIT_REQUEST_CREATED'",
                    world.rahul().userId()));
            assertEquals(0L, longValue("select count(*) from visit_sessions where tenant_id=?",
                    world.rahul().userId()));
            assertEquals(0L, longValue("select count(*) from tenant_visit_entitlement_accounts "
                    + "where user_id=?", world.rahul().userId()));
            assertEquals(0L, longValue("select count(*) from visit_notification_outbox "
                    + "where recipient_user_id=?", world.rahul().userId()));
        } finally {
            psqlExecute("drop trigger if exists dc13_fail_routing_request on "
                    + schema + ".visit_request_routing_tasks");
            psqlExecute("drop function if exists "
                    + schema + ".dc13_fail_routing_request()");
        }

        HttpResponse<String> recovered = send("POST",
                "/api/v1/tenant/property-visit-commands", world.rahul().token(),
                body, Map.of());
        assertEquals(200, recovered.statusCode(), recovered.body());
        JsonNode recoveredRequest = json(recovered);
        assertTrue(recoveredRequest.path("created").asBoolean());
        assertEquals(1L, longValue("select count(*) from property_visit_requests "
                + "where tenant_id=? and listing_id=?", world.rahul().userId(),
                world.listingId()));
        assertEquals(1L, longValue("select count(*) from lead_assistance_command_receipts "
                + "where actor_user_id=? and operation_id=?",
                world.rahul().userId(), operationId));
        assertEquals(1L, longValue("select count(*) from visit_request_routing_tasks "
                + "where request_id=?", recoveredRequest.path("requestId").asLong()));
    }

    @Test
    void legacyAndAssistedConcurrentRequestsConvergeToOneRequestAndEnrollment()
            throws Exception {
        World world = world("legacy-dc-race");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> legacy;
        CompletableFuture<HttpResponse<String>> assisted;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, world.listingId());
                statement.executeQuery().close();
            }
            long blockerPid = longValueOn(lock, "select pg_backend_pid()");
            legacy = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return send("POST", "/api/v1/properties/" + world.listingId()
                                    + "/visit-requests", world.rahul().token(),
                            mapper.writeValueAsString(Map.of(
                                    "preferredVisitTiming", "Tomorrow morning")), Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            start.countDown();
            String witness = awaitRuntimeWait(blockerPid, 10_000);
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/legacy-canonical-race-lock-wait.txt"), witness);
            assisted = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/tenant/property-visit-commands",
                            world.rahul().token(), ownRequestBody(world, null, false), Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            lock.rollback();
        }
        HttpResponse<String> legacyResult = legacy.get(20, TimeUnit.SECONDS);
        HttpResponse<String> assistedResult = assisted.get(20, TimeUnit.SECONDS);
        assertTrue(List.of(200, 201).contains(legacyResult.statusCode()), legacyResult.body());
        assertTrue(List.of(200, 201).contains(assistedResult.statusCode()), assistedResult.body());
        long legacyRequest = json(legacyResult).path("requestId").asLong();
        long assistedRequest = json(assistedResult).path("requestId").asLong();
        assertEquals(legacyRequest, assistedRequest);
        assertEquals(1L, longValue("select count(*) from property_visit_requests "
                + "where tenant_id=? and listing_id=?",
                world.rahul().userId(), world.listingId()));
        assertEquals(1L, longValue("select count(*) from visit_request_routing_tasks "
                + "where request_id=?", legacyRequest));
    }

    @Test
    void concurrentAssociationDecisionsYieldOneWinnerAndOneConflict() throws Exception {
        World world = world("association-race");
        Lead lead = capture(world, world.ravi(), "Rahul Association Race",
                "+919700001015");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String token = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        Continuation context = exchange(token);
        JsonNode request = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, context, true),
                Map.of("Cookie", "pathome_visit_context=" + context.token())));
        assertEquals("PENDING", request.path("associationStatus").asText(), request.toString());

        JsonNode proposalView = json(send("GET",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals/"
                        + proposal.path("proposalId").asText(),
                world.ravi().token(), null, Map.of()));
        JsonNode claim = proposalView.path("associationClaims").get(0);
        String decisionPath = "/api/v1/operations/leads/" + lead.id()
                + "/visit-proposals/" + proposal.path("proposalId").asText()
                + "/association-claims/" + claim.path("claimId").asText() + "/decision";
        String confirmBody = mapper.writeValueAsString(Map.of(
                "decision", "CONFIRM",
                "expectedClaimVersion", claim.path("version").asLong(),
                "reasonCode", "CUSTOMER_CONSENT_VERIFIED"));
        String disputeBody = mapper.writeValueAsString(Map.of(
                "decision", "DISPUTE",
                "expectedClaimVersion", claim.path("version").asLong(),
                "reasonCode", "CUSTOMER_CONSENT_NOT_VERIFIED"));

        CompletableFuture<HttpResponse<String>> confirm;
        CompletableFuture<HttpResponse<String>> dispute;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from lead_routing_queue where id=? for update")) {
                statement.setLong(1, lead.id());
                statement.executeQuery().close();
            }
            long blockerPid = longValueOn(lock, "select pg_backend_pid()");
            confirm = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", decisionPath, world.ravi().token(), confirmBody,
                            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String witness = awaitRuntimeWait(blockerPid, 10_000);
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/association-race-lock-wait.txt"), witness);
            dispute = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", decisionPath, world.ravi().token(), disputeBody,
                            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            lock.rollback();
        }
        HttpResponse<String> winner = confirm.get(20, TimeUnit.SECONDS);
        HttpResponse<String> loser = dispute.get(20, TimeUnit.SECONDS);
        assertEquals(200, winner.statusCode(), winner.body());
        assertEquals(409, loser.statusCode(), loser.body());
        assertEquals("CONFIRMED", json(winner).path("state").asText());
        assertEquals(1L, longValue("select count(*) from lead_visit_links "
                + "where proposal_id=?::uuid and user_id=?",
                proposal.path("proposalId").asText(), world.rahul().userId()));
        assertEquals(1L, longValue("select count(*) from lead_assistance_command_receipts "
                + "where action='LEAD_VISIT_ASSOCIATION_DECISION' and result_reference=?",
                claim.path("claimId").asText()));
    }

    @Test
    void sameLeadAndUserRetainTwoPropertyAssociationsInBothOrders() throws Exception {
        for (boolean reverse : List.of(false, true)) {
            World world = world("association-" + reverse);
            Lead lead = capture(world, world.ravi(), "Rahul Multi Property",
                    reverse ? "+919700001014" : "+919700001013");
            long additional = createAdditionalListing(world,
                    reverse ? "Association Reverse Alpha" : "Association Forward Beta");
            long firstListing = reverse ? additional : world.listingId();
            long secondListing = reverse ? world.listingId() : additional;

            JsonNode firstSetup = shareSetup(world, lead, firstListing);
            long preferenceRevision = firstSetup.path("preferenceRevision").asLong();
            long firstRequest = associateProperty(world, lead, firstListing,
                    firstSetup.path("shortlistId").asLong(),
                    firstSetup.path("shortlistVersion").asLong(),
                    preferenceRevision);

            JsonNode secondShortlist = createShortlist(world, lead, List.of(secondListing));
            long secondRequest = associateProperty(world, lead, secondListing,
                    secondShortlist.path("id").asLong(),
                    secondShortlist.path("version").asLong(),
                    preferenceRevision);

            assertEquals(2L, longValue("select count(*) from lead_visit_links where lead_id=?",
                    lead.id()));
            assertEquals(2L, longValue("select count(distinct listing_id) from lead_visit_links "
                    + "where lead_id=? and user_id=?", lead.id(), world.rahul().userId()));
            assertEquals(world.rahul().userId(),
                    longValue("select linked_user_id from lead_routing_queue where id=?",
                            lead.id()));
            assertEquals(2L, longValue("select count(*) from property_visit_requests "
                    + "where id in (?,?) and status='RECEIVED'",
                    firstRequest, secondRequest));
        }
    }

    @Test
    void eightyDistinctLawfulListingsAndLessorContactsAreNotDeniedByCount() throws Exception {
        World world = world("distinct-volume");
        Lead lead = capture(world, world.ravi(), "Rahul Distinct Volume", "+919700001041");
        shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID()).statusCode());
        List<Long> listings = new java.util.ArrayList<>();
        for (int index = 0; index < 80; index++) {
            listings.add(createDistinctContactListing(world, index));
        }
        int revealedCount = 0;
        for (int offset = 0; offset < listings.size(); offset += 20) {
            List<Long> group = listings.subList(offset, offset + 20);
            JsonNode shortlist = createShortlist(world, lead, group);
            for (long listingId : group) {
                JsonNode setup = mapper.createObjectNode()
                        .put("shortlistId", shortlist.path("id").asLong())
                        .put("shortlistVersion", shortlist.path("version").asLong());
                UUID enquiryId = createAvailability(world, lead, setup, true, listingId);
                HttpResponse<String> response = send("POST",
                        "/api/v1/operations/leads/" + lead.id()
                                + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                        world.ravi().token(), revealBody(world, lead, setup, enquiryId, listingId),
                        Map.of());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(json(response).path("mobile").asText().startsWith("+91 "));
                revealedCount++;
            }
        }
        assertEquals(80, revealedCount);
        assertEquals(80L, longValue("select count(distinct listing_id) "
                + "from lead_lessor_availability_enquiries where lead_id=?", lead.id()));
        assertEquals(80L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));
    }

    @Test
    void invitationRequiresExactPersistedShortlistMembership() throws Exception {
        World world = world("proposal-membership");
        Lead lead = capture(world, world.ravi(), "Rahul Membership", "+919700001051");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        long absentListing = createAdditionalListing(world, "Synthetic Absent Membership");
        HttpResponse<String> rejected = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", absentListing,
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", 0,
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals(0L, longValue("select count(*) from lead_visit_proposals "
                + "where lead_id=? and listing_id=?", lead.id(), absentListing));
        JsonNode valid = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", 0,
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertEquals(world.listingId(), valid.path("listingId").asLong());
    }

    @Test
    void shortlistRemovalAndReadditionCannotRebindAStaleProposalContext()
            throws Exception {
        World world = world("shortlist-readd");
        Lead lead = capture(world, world.ravi(), "Rahul Shortlist Readd",
                "+919700001052");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        long shortlistId = setup.path("shortlistId").asLong();
        JsonNode initial = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertNotNull(initial.path("proposalId").asText());

        runner.update("delete from lead_shortlist_items "
                + "where shortlist_id=? and listing_id=?", shortlistId, world.listingId());
        runner.update("update lead_shortlists set version=version+1, "
                + "updated_at=clock_timestamp() where id=?", shortlistId);
        long removedVersion = longValue(
                "select version from lead_shortlists where id=?", shortlistId);
        HttpResponse<String> removed = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", removedVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(409, removed.statusCode(), removed.body());

        runner.update("insert into lead_shortlist_items(shortlist_id,listing_id,position) "
                + "values (?,?,0)", shortlistId, world.listingId());
        runner.update("update lead_shortlists set version=version+1, "
                + "updated_at=clock_timestamp() where id=?", shortlistId);
        long readdedVersion = longValue(
                "select version from lead_shortlists where id=?", shortlistId);
        HttpResponse<String> readded = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", shortlistId,
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", readdedVersion,
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, readded.statusCode(), readded.body());
        assertEquals(world.listingId(), json(readded).path("listingId").asLong());
    }

    @Test
    void staleOwnerClosedLeadAndInvalidSourceCannotRevealLessorContact() throws Exception {
        World world = world("fresh-authority");
        Lead lead = capture(world, world.ravi(), "Rahul Fresh Authority", "+919700001061");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID()).statusCode());
        UUID enquiryId = createAvailability(world, lead, setup, true);
        String body = revealBody(world, lead, setup, enquiryId);

        runner.update("update lead_source_events set processing_state='QUARANTINED' where id=?",
                lead.sourceId());
        assertRevealDenied(world, lead, enquiryId, body, 409);
        runner.update("update lead_source_events set processing_state='PROCESSED' where id=?",
                lead.sourceId());

        runner.update("update lead_routing_queue set owner_user_id=? where id=?",
                world.peer().userId(), lead.id());
        assertRevealDenied(world, lead, enquiryId, body, 404);
        runner.update("update lead_routing_queue set owner_user_id=? where id=?",
                world.ravi().userId(), lead.id());

        runner.update("update employee_profiles set staff_active=false where user_id=?",
                world.ravi().userId());
        assertRevealDenied(world, lead, enquiryId, body, 403);
        runner.update("update employee_profiles set staff_active=true where user_id=?",
                world.ravi().userId());

        runner.update("update lead_routing_queue set status='CLOSED' where id=?", lead.id());
        assertRevealDenied(world, lead, enquiryId, body, 409);
        assertEquals(0L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));
    }

    private void assertRevealDenied(World world, Lead lead, UUID enquiryId, String body,
                                    int expectedStatus) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/operations/leads/" + lead.id()
                        + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                world.ravi().token(), body, Map.of());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        assertFalse(response.body().contains(PRIVATE_MOBILE));
        assertFalse(response.body().contains(PRIVATE_MOBILE_DISPLAY));
    }

    @Test
    void amitRequestIsIndependentAndAssociationFailureCannotEraseACommittedRequest()
            throws Exception {
        World world = world("amit");
        Lead lead = capture(world, world.ravi(), "Rahul Forwarded", "+919700001007");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String token = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        Continuation amitContext = exchange(token);
        JsonNode amitRequest = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.amit().token(), ownRequestBody(world, amitContext, false), Map.of()));
        assertTrue(amitRequest.path("created").asBoolean());
        long amitRequestId = amitRequest.path("requestId").asLong();
        assertEquals(world.amit().userId(),
                longValue("select tenant_id from property_visit_requests where id=?", amitRequestId));
        assertTrue(text("select linked_user_id from lead_routing_queue where id=?", lead.id()) == null);

        Continuation rahulContext = exchange(token);
        JsonNode rahulRequest = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, rahulContext, true), Map.of()));
        assertTrue(rahulRequest.path("created").asBoolean());
        assertNotEquals(amitRequestId, rahulRequest.path("requestId").asLong());
        assertEquals("UNAVAILABLE", rahulRequest.path("associationStatus").asText());
        assertTrue(rahulRequest.path("message").asText().contains("Visit Request"),
                rahulRequest.path("message").asText());
        assertEquals("RECEIVED",
                text("select status from property_visit_requests where id=?",
                        rahulRequest.path("requestId").asLong()));
        assertEquals(2L, longValue("select count(*) from property_visit_requests "
                + "where listing_id=?", world.listingId()));
        assertEquals(0L, longValue("select count(*) from lead_visit_links"));
        assertTrue(text("select linked_user_id from lead_routing_queue where id=?", lead.id()) == null);
    }

    @Test
    void foreignRequestIdCannotBeAssociatedOrShownInAnotherTenantsHistory() throws Exception {
        World world = world("dc06-foreign-request");
        JsonNode rahulRequest = json(send("POST",
                "/api/v1/tenant/property-visit-commands", world.rahul().token(),
                ownRequestBody(world, null, false), Map.of()));
        long rahulRequestId = rahulRequest.path("requestId").asLong();
        assertTrue(rahulRequest.path("created").asBoolean());

        Lead lead = capture(world, world.ravi(), "Rahul Foreign Request",
                "+919700001071");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String token = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        Continuation amitContext = exchange(token);

        HttpResponse<String> foreign = send("POST",
                "/api/v1/tenant/lead-visit-proposals/" + amitContext.contextId()
                        + "/responses",
                world.amit().token(), mapper.writeValueAsString(Map.of(
                        "response", "REQUEST_RECORDED",
                        "requestId", rahulRequestId,
                        "expectedResponseVersion", -1,
                        "associateEnquiry", false,
                        "explicitEnquiryOwnershipConsent", false,
                        "consentWordingVersion", "")),
                Map.of("Cookie", "pathome_visit_context=" + amitContext.token()));

        assertEquals(404, foreign.statusCode(), foreign.body());
        assertFalse(foreign.body().contains("Rahul Foreign Request"));
        assertEquals(0L, longValue("select count(*) from lead_visit_customer_responses "
                        + "where proposal_id=?::uuid and user_id=?",
                proposal.path("proposalId").asText(), world.amit().userId()));
        assertEquals("RECEIVED", text("select status from property_visit_requests where id=?",
                rahulRequestId));
        JsonNode amitHistory = json(send("GET", "/api/v1/tenant/visit-requests?page=0",
                world.amit().token(), null, Map.of()));
        for (JsonNode item : amitHistory.path("requests")) {
            assertNotEquals(rahulRequestId, item.path("requestId").asLong());
        }
    }

    @Test
    void existingOwnRequestAcknowledgementIsVersionedDurableAndReplayable()
            throws Exception {
        World world = world("ack");
        Lead lead = capture(world, world.ravi(), "Rahul Replay", "+919700001008");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String token = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        Continuation context = exchange(token);
        JsonNode created = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, context, false), Map.of()));
        assertTrue(created.path("created").asBoolean());
        long requestId = created.path("requestId").asLong();
        long requestVersion = longValue(
                "select version from property_visit_requests where id=?", requestId);

        ObjectNode acknowledged = (ObjectNode) mapper.readTree(
                ownRequestBody(world, context, false));
        UUID acknowledgementKey = UUID.randomUUID();
        acknowledged.put("operationId", acknowledgementKey.toString());
        acknowledged.put("acknowledgeExistingRequest", true);
        acknowledged.put("expectedRequestVersion", requestVersion);
        String body = mapper.writeValueAsString(acknowledged);
        JsonNode first = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), body, Map.of()));
        assertFalse(first.path("created").asBoolean());
        assertFalse(first.path("requiresAcknowledgement").asBoolean());
        assertEquals(requestVersion, first.path("requestVersion").asLong());
        assertEquals(1L, longValue("select count(*) from lead_assistance_command_receipts "
                + "where actor_user_id=? and operation_id=? and action='TENANT_VISIT_REQUEST_ACKNOWLEDGE'",
                world.rahul().userId(), acknowledgementKey));

        runner.update("update listings set status='CLOSED' where id=?", world.listingId());
        HttpResponse<String> replayResponse = send("POST",
                "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), body, Map.of());
        assertEquals(200, replayResponse.statusCode(), replayResponse.body());
        JsonNode replay = json(replayResponse);
        assertEquals(requestId, replay.path("requestId").asLong());
        assertEquals(1L, longValue("select count(*) from property_visit_requests "
                + "where tenant_id=? and listing_id=?", world.rahul().userId(),
                world.listingId()));

        ((ObjectNode) acknowledged.path("request")).put("preferredVisitTiming", "Sunday morning");
        assertEquals(409, send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), mapper.writeValueAsString(acknowledged), Map.of())
                .statusCode());
    }

    @Test
    void genuineConnectedReactBrowserUsesRealSpringAndPostgres() throws Exception {
        World world = world("browser");
        Lead lead = capture(world, world.ravi(), "Rahul Browser", "+919700007777");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        PhoneChallenge mainChallenge = issuePhone(world, lead);
        DeliveryMaterial mainMaterial = deliveryMaterial(mainChallenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(world.ravi(), lead, mainChallenge,
                mainMaterial.code(), UUID.randomUUID()).statusCode());

        Lead otpLead = capture(world, world.ravi(), "Rahul Browser OTP", "+919700007778");
        PhoneChallenge otpChallenge = issuePhone(world, otpLead);
        DeliveryMaterial otpMaterial = deliveryMaterial(otpChallenge.id());
        phoneWorker.dispatchDue();
        String phoneToken = otpMaterial.confirmationUrl()
                .substring(otpMaterial.confirmationUrl().indexOf("token=") + 6);
        assertTrue(phoneToken.length() >= 40);
        String tenantEmail = "a1a7-browser-tenant-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String tenantPassword = "Synthetic-Browser-Auth-2026!";
        Actor browserTenant = authenticatedCustomer(
                "Rahul Browser Auth", tenantEmail, "+919700007779", tenantPassword);
        String amitEmail = "a1a7-browser-amit-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String amitPassword = "Synthetic-Browser-Amit-2026!";
        Actor browserAmit = authenticatedCustomer(
                "Amit Browser Auth", amitEmail, "+919700007780", amitPassword);
        String plainEmail = "a1a7-browser-plain-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String plainPassword = "Synthetic-Browser-Plain-2026!";
        Actor browserPlain = authenticatedCustomer(
                "Plain Browser Tenant", plainEmail, "+919700007781", plainPassword);
        long browserPlainListingId = createAdditionalListing(world, "D-C Plain Link");
        String browserPlainListingTitle = text("select title from listings where id=?",
                browserPlainListingId);
        JsonNode browserProposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", world.listingId(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "contactPreferenceRevision", setup.path("preferenceRevision").asLong())),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String browserHandoffToken = browserProposal.path("handoffUrl").asText()
                .substring(browserProposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        String raviEmail = "a1a7-browser-ravi-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String raviPassword = "Synthetic-Browser-Ravi-2026!";
        runner.update("update users set email=?,password_hash=? where id=?",
                raviEmail, passwordEncoder.encode(raviPassword), world.ravi().userId());
        String adminEmail = "a1a7-browser-admin-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String adminPassword = "Synthetic-Browser-Admin-2026!";
        runner.update("update users set email=?,password_hash=? where id=?",
                adminEmail, passwordEncoder.encode(adminPassword), world.admin().userId());
        String browserListingTitle = text("select title from listings where id=?",
                world.listingId());
        String browserTeamName = text("select display_name from operating_teams where id=?",
                world.teamId());

        Path repository = Path.of(System.getProperty("user.dir")).getFileName().toString()
                .equals("backend") ? Path.of(System.getProperty("user.dir")).getParent()
                : Path.of(System.getProperty("user.dir"));
        Path script = repository.resolve("frontend-web/browser-verification/a1-a7/connected-run.mjs");
        assertTrue(Files.isRegularFile(script));
        Path receiptDirectory = Path.of(A1A7IsolatedTarget.evidenceDirectory()).resolve("browser");
        Files.createDirectories(receiptDirectory);
        ProcessBuilder browser = new ProcessBuilder("node", script.toString());
        browser.directory(repository.toFile());
        browser.redirectErrorStream(true);
        browser.redirectOutput(receiptDirectory.resolve("connected-browser.log").toFile());
        browser.environment().put("PATHOME_A1_A7_BROWSER_BACKEND_PORT", String.valueOf(port));
        browser.environment().put("PATHOME_A1_A7_BROWSER_STAFF_TOKEN", world.ravi().token());
        browser.environment().put("PATHOME_A1_A7_BROWSER_STAFF_ID",
                String.valueOf(world.ravi().userId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_LEAD_ID", String.valueOf(lead.id()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_LISTING_ID",
                String.valueOf(world.listingId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_LISTING_TITLE",
                browserListingTitle);
        browser.environment().put("PATHOME_A1_A7_BROWSER_TEAM_NAME", browserTeamName);
        browser.environment().put("PATHOME_A1_A7_BROWSER_TEAM_ID",
                String.valueOf(world.teamId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_SHORTLIST_ID",
                String.valueOf(setup.path("shortlistId").asLong()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_PHONE_TOKEN", phoneToken);
        browser.environment().put("PATHOME_A1_A7_BROWSER_HANDOFF_TOKEN", browserHandoffToken);
        browser.environment().put("PATHOME_A1_A7_BROWSER_EVIDENCE_DIR",
                receiptDirectory.toAbsolutePath().toString());
        browser.environment().put("PATHOME_A1_A7_BROWSER_TENANT_EMAIL", tenantEmail);
        browser.environment().put("PATHOME_A1_A7_BROWSER_TENANT_PASSWORD", tenantPassword);
        browser.environment().put("PATHOME_A1_A7_BROWSER_TENANT_ID",
                String.valueOf(browserTenant.userId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_AMIT_EMAIL", amitEmail);
        browser.environment().put("PATHOME_A1_A7_BROWSER_AMIT_PASSWORD", amitPassword);
        browser.environment().put("PATHOME_A1_A7_BROWSER_AMIT_ID",
                String.valueOf(browserAmit.userId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_PLAIN_EMAIL", plainEmail);
        browser.environment().put("PATHOME_A1_A7_BROWSER_PLAIN_PASSWORD", plainPassword);
        browser.environment().put("PATHOME_A1_A7_BROWSER_PLAIN_ID",
                String.valueOf(browserPlain.userId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_PLAIN_LISTING_ID",
                String.valueOf(browserPlainListingId));
        browser.environment().put("PATHOME_A1_A7_BROWSER_PLAIN_LISTING_TITLE",
                browserPlainListingTitle);
        browser.environment().put("PATHOME_A1_A7_BROWSER_EXPIRED_TOKEN",
                expiredTenantToken(browserPlain));
        browser.environment().put("PATHOME_A1_A7_BROWSER_ADMIN_EMAIL", adminEmail);
        browser.environment().put("PATHOME_A1_A7_BROWSER_ADMIN_PASSWORD", adminPassword);
        browser.environment().put("PATHOME_A1_A7_BROWSER_ADMIN_ID",
                String.valueOf(world.admin().userId()));
        browser.environment().put("PATHOME_A1_A7_BROWSER_STAFF_EMAIL", raviEmail);
        browser.environment().put("PATHOME_A1_A7_BROWSER_STAFF_PASSWORD", raviPassword);
        Process process = browser.start();
        assertTrue(process.waitFor(180, TimeUnit.SECONDS),
                "Connected A1-A7 browser batch exceeded 180 seconds");
        assertEquals(0, process.exitValue(), "Connected A1-A7 browser batch failed");
        assertTrue(Files.isRegularFile(receiptDirectory.resolve("connected-browser-receipt.json")));
        JsonNode browserRequest = json(send("GET",
                "/api/v1/tenant/visit-requests?page=0", browserPlain.token(), null, Map.of()));
        JsonNode exactRequest = null;
        for (JsonNode item : browserRequest.path("requests")) {
            if (item.path("propertyId").asLong() == browserPlainListingId) exactRequest = item;
        }
        assertNotNull(exactRequest);
        long browserRequestId = exactRequest.path("requestId").asLong();
        assertEquals("RECEIVED", text("select status from property_visit_requests where id=?",
                browserRequestId));
        assertEquals(world.ravi().userId(), longValue(
                "select coordinator_user_id from property_visit_requests where id=?",
                browserRequestId));
        assertEquals(world.teamId(), longValue(
                "select operating_team_id from property_visit_requests where id=?",
                browserRequestId));
        assertEquals("DONE", text("select state from visit_request_routing_tasks "
                + "where request_id=?", browserRequestId));
    }

    @Test
    void concurrentOtpIssueAndAuthorityRevocationDuringLockedRevealFailClosed()
            throws Exception {
        World world = world("concurrency");
        Lead lead = capture(world, world.ravi(), "Rahul Concurrent", "+919700001008");
        Lead current = lead(lead.id());
        String issueBody = mapper.writeValueAsString(Map.of(
                "expectedLeadVersion", current.version(),
                "sourceEvent", Map.of("id", lead.sourceId().toString()),
                "expectedContactPhoneRevision", current.phoneRevision(),
                "noticeAgreed", true));
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads/" + lead.id()
                                + "/phone-verifications", world.ravi().token(), issueBody,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads/" + lead.id()
                                + "/phone-verifications", world.ravi().token(), issueBody,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        assertEquals(201, first.get(20, TimeUnit.SECONDS).statusCode());
        assertEquals(201, second.get(20, TimeUnit.SECONDS).statusCode());
        assertEquals(1L, longValue("select count(*) from lead_phone_challenges "
                + "where lead_id=? and state='PENDING'", lead.id()));
        assertEquals(2, intValue("select max(generation) from lead_phone_challenges "
                + "where lead_id=?", lead.id()));
        assertEquals(1L, longValue("select count(*) from lead_phone_challenges "
                + "where lead_id=? and state='CANCELLED'", lead.id()));

        Map<String, Object> latest = runner.queryForMap(
                "select id,version from lead_phone_challenges where lead_id=? "
                        + "and state='PENDING'", lead.id());
        PhoneChallenge currentChallenge = new PhoneChallenge(
                (UUID) latest.get("id"), ((Number) latest.get("version")).longValue(), 2, "");
        DeliveryMaterial material = deliveryMaterial(currentChallenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(world.ravi(), lead, currentChallenge,
                material.code(), UUID.randomUUID()).statusCode());

        JsonNode setup = shareSetup(world, lead, world.listingId());
        UUID enquiryId = createAvailability(world, lead, setup, true);
        String revealBody = revealBody(world, lead, setup, enquiryId);
        CompletableFuture<HttpResponse<String>> reveal;
        long blockerPid;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, world.listingId());
                statement.executeQuery().close();
            }
            blockerPid = longValueOn(lock, "select pg_backend_pid()");
            reveal = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/" + lead.id()
                                    + "/availability-enquiries/" + enquiryId + "/lessor-contact",
                            world.ravi().token(), revealBody, Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String witness = awaitRuntimeWait(blockerPid, 10_000);
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/concurrency-lock-wait.txt"), witness);
            assertEquals(1, runner.update("update staff_access_grants "
                            + "set revoked_at=clock_timestamp(),revoked_by_user_id=?,"
                            + "revoke_reason_code='A1A7_LOCK_WAIT_REVOKE' "
                            + "where id=? and revoked_at is null",
                    world.admin().userId(), world.ravi().grantId()));
            lock.rollback();
        }
        HttpResponse<String> denied = reveal.get(20, TimeUnit.SECONDS);
        assertEquals(403, denied.statusCode(), denied.body());
        assertFalse(denied.body().contains(PRIVATE_MOBILE));
        assertFalse(denied.body().contains(PRIVATE_MOBILE_DISPLAY));
        assertEquals(0L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                world.ravi().userId()));
    }

    @Test
    void availabilityCloseAndRevealAreSerializedInBothTransactionOrders()
            throws Exception {
        World closeFirstWorld = world("close-first");
        Lead closeFirstLead = capture(closeFirstWorld, closeFirstWorld.ravi(),
                "Rahul Close First", "+919700001021");
        JsonNode closeFirstSetup = shareSetup(closeFirstWorld, closeFirstLead,
                closeFirstWorld.listingId());
        PhoneChallenge closeFirstChallenge = issuePhone(closeFirstWorld, closeFirstLead);
        DeliveryMaterial closeFirstMaterial = deliveryMaterial(closeFirstChallenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(closeFirstWorld.ravi(), closeFirstLead,
                closeFirstChallenge, closeFirstMaterial.code(), UUID.randomUUID()).statusCode());
        UUID closeFirstEnquiry = createAvailability(closeFirstWorld, closeFirstLead,
                closeFirstSetup, true);
        String closeFirstRevealBody = revealBody(closeFirstWorld, closeFirstLead,
                closeFirstSetup, closeFirstEnquiry);
        CompletableFuture<HttpResponse<String>> closeFirstReveal;
        long closeFirstBlocker;
        try (Connection closer = runner.getDataSource().getConnection()) {
            closer.setAutoCommit(false);
            try (var statement = closer.prepareStatement(
                    "update lead_lessor_availability_enquiries set outcome='CUSTOMER_WITHDREW',"
                            + "version=version+1 where id=?")) {
                statement.setObject(1, closeFirstEnquiry);
                assertEquals(1, statement.executeUpdate());
            }
            closeFirstBlocker = longValueOn(closer, "select pg_backend_pid()");
            closeFirstReveal = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/" + closeFirstLead.id()
                                    + "/availability-enquiries/" + closeFirstEnquiry
                                    + "/lessor-contact",
                            closeFirstWorld.ravi().token(), closeFirstRevealBody, Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String witness = awaitRuntimeWait(closeFirstBlocker, 10_000);
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/availability-close-first-witness.txt"), witness);
            closer.commit();
        }
        HttpResponse<String> closed = closeFirstReveal.get(20, TimeUnit.SECONDS);
        assertEquals(409, closed.statusCode(), closed.body());
        assertFalse(closed.body().contains(PRIVATE_MOBILE));
        assertEquals(0L, longValue("select count(*) from operational_audit_events "
                + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                closeFirstWorld.ravi().userId()));

        World revealFirstWorld = world("reveal-first");
        Lead revealFirstLead = capture(revealFirstWorld, revealFirstWorld.ravi(),
                "Rahul Reveal First", "+919700001022");
        JsonNode revealFirstSetup = shareSetup(revealFirstWorld, revealFirstLead,
                revealFirstWorld.listingId());
        PhoneChallenge revealFirstChallenge = issuePhone(revealFirstWorld, revealFirstLead);
        DeliveryMaterial revealFirstMaterial = deliveryMaterial(revealFirstChallenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(revealFirstWorld.ravi(), revealFirstLead,
                revealFirstChallenge, revealFirstMaterial.code(), UUID.randomUUID()).statusCode());
        UUID revealFirstEnquiry = createAvailability(revealFirstWorld, revealFirstLead,
                revealFirstSetup, true);
        String revealFirstBody = revealBody(revealFirstWorld, revealFirstLead,
                revealFirstSetup, revealFirstEnquiry);
        CompletableFuture<HttpResponse<String>> revealFirst;
        CompletableFuture<HttpResponse<String>> closeSecond;
        long listingBlocker;
        try (Connection lock = runner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, revealFirstWorld.listingId());
                statement.executeQuery().close();
            }
            listingBlocker = longValueOn(lock, "select pg_backend_pid()");
            revealFirst = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/" + revealFirstLead.id()
                                    + "/availability-enquiries/" + revealFirstEnquiry
                                    + "/lessor-contact",
                            revealFirstWorld.ravi().token(), revealFirstBody, Map.of());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String revealWait = awaitRuntimeWait(listingBlocker, 10_000);
            JsonNode currentEnquiry = json(send("GET",
                    "/api/v1/operations/leads/" + revealFirstLead.id()
                            + "/availability-enquiries/" + revealFirstEnquiry,
                    revealFirstWorld.ravi().token(), null, Map.of()));
            String closeBody = mapper.writeValueAsString(Map.of(
                    "expectedLeadVersion", lead(revealFirstLead.id()).version(),
                    "expectedEnquiryVersion", currentEnquiry.path("version").asLong(),
                    "outcome", "CUSTOMER_WITHDREW",
                    "note", "Synthetic closure races a reveal."));
            closeSecond = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/" + revealFirstLead.id()
                                    + "/availability-enquiries/" + revealFirstEnquiry + "/responses",
                            revealFirstWorld.ravi().token(), closeBody,
                            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            long revealWaiterPid = waitPid(revealWait);
            String closeWitness = awaitRuntimeWait(revealWaiterPid, 10_000);
            List<List<Object>> rows = psqlRows(
                    "select pid,coalesce(wait_event_type,''),coalesce(wait_event,''),"
                            + "array_to_string(pg_blocking_pids(pid),',', '<null>') "
                            + "from pg_stat_activity "
                            + "where datname=current_database() "
                            + "and usename=current_user "
                            + "and pid in (" + revealWaiterPid + ","
                            + waitPid(closeWitness) + ") "
                            + "order by pid");
            assertEquals(2, rows.size(), "reverse-order witness must retain both rows");
            assertEquals("Lock", rows.get(0).get(1));
            assertEquals("Lock", rows.get(1).get(1));
            String closeWait = revealWait + closeWitness
                    + "runtime_rows=" + rows.size() + "\n";
            Files.writeString(Path.of(A1A7IsolatedTarget.evidenceDirectory())
                    .resolve("tests/availability-reveal-first-witness.txt"),
                    closeWait);
            lock.commit();
        }
        HttpResponse<String> revealed = revealFirst.get(20, TimeUnit.SECONDS);
        assertEquals(200, revealed.statusCode(), revealed.body());
        assertEquals(PRIVATE_MOBILE_DISPLAY, json(revealed).path("mobile").asText());
        assertEquals(200, closeSecond.get(20, TimeUnit.SECONDS).statusCode());
        assertEquals("CUSTOMER_WITHDREW",
                text("select outcome from lead_lessor_availability_enquiries where id=?",
                        revealFirstEnquiry));
    }

    @Test
    void retiredLastAssetCannotResurrectFromLegacyGallery() throws Exception {
        World world = world("retired-media");
        String requestId = "a1a7retired" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        String publicId = "pathome/properties/images/" + requestId;
        String url = "https://res.cloudinary.com/hbgzetjy/image/upload/" + publicId + ".webp";
        insert("insert into property_media_assets(listing_id,media_url,cloudinary_public_id,"
                        + "upload_request_id,media_type,room_tag,is_primary_cover,uploaded_at) "
                        + "values (?,?,?,?,'IMAGE','LIVING_ROOM',true,clock_timestamp()) returning id",
                world.listingId(), url, publicId, requestId);
        JsonNode active = publicProperty(world.listingId());
        assertTrue(active.path("media").toString().contains(requestId), active.toString());

        runner.update("update property_media_assets set retired_at=clock_timestamp() "
                + "where listing_id=? and cloudinary_public_id=?", world.listingId(), publicId);
        runner.update("update listings set media_gallery_urls=? where id=?", url, world.listingId());
        JsonNode retired = publicProperty(world.listingId());
        assertFalse(retired.toString().contains(requestId), retired.toString());
        assertFalse(retired.toString().contains(publicId), retired.toString());
        HttpResponse<String> discovery = send("GET",
                "/api/v1/properties?page=0&city="
                        + java.net.URLEncoder.encode("Synthetic retired-media City",
                        java.nio.charset.StandardCharsets.UTF_8), null, null, Map.of());
        assertEquals(200, discovery.statusCode(), discovery.body());
        assertFalse(discovery.body().contains(requestId));
    }

    @Test
    void auditInsertFailureReturnsNoLessorPiiAndFreshRetryReauthorizes() throws Exception {
        World world = world("audit-fault");
        Lead lead = capture(world, world.ravi(), "Rahul Audit Fault", "+919700001031");
        JsonNode setup = shareSetup(world, lead, world.listingId());
        PhoneChallenge challenge = issuePhone(world, lead);
        DeliveryMaterial material = deliveryMaterial(challenge.id());
        phoneWorker.dispatchDue();
        assertEquals(200, confirmOperator(world.ravi(), lead, challenge, material.code(),
                UUID.randomUUID()).statusCode());
        UUID enquiryId = createAvailability(world, lead, setup, true);
        String body = revealBody(world, lead, setup, enquiryId);
        String schema = System.getenv(A1A7IsolatedTarget.SCHEMA_FLAG);
        assertTrue(schema != null && schema.matches("^[a-z][a-z0-9_]{0,62}$"));
        psqlExecute("create function " + schema + ".a1a7_fail_reveal_audit() "
                + "returns trigger language plpgsql as $$ begin "
                + "if new.action_code='LEAD_LESSOR_CONTACT_REVEALED' then "
                + "raise exception 'synthetic audit failure'; end if; return new; end $$");
        psqlExecute("create trigger a1a7_fail_reveal_audit before insert on "
                + schema + ".operational_audit_events for each row execute function "
                + schema + ".a1a7_fail_reveal_audit()");
        try {
            HttpResponse<String> failed = send("POST",
                    "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries/"
                            + enquiryId + "/lessor-contact",
                    world.ravi().token(), body, Map.of());
            assertEquals(500, failed.statusCode(), failed.body());
            assertFalse(failed.body().contains(PRIVATE_MOBILE));
            assertFalse(failed.body().contains(PRIVATE_MOBILE_DISPLAY));
            assertEquals(0L, longValue("select count(*) from operational_audit_events "
                    + "where action_code='LEAD_LESSOR_CONTACT_REVEALED' and actor_user_id=?",
                    world.ravi().userId()));
        } finally {
            psqlExecute("drop trigger if exists a1a7_fail_reveal_audit "
                    + "on " + schema + ".operational_audit_events");
            psqlExecute("drop function if exists " + schema + ".a1a7_fail_reveal_audit()");
        }
        HttpResponse<String> retried = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries/"
                        + enquiryId + "/lessor-contact",
                world.ravi().token(), body, Map.of());
        assertEquals(200, retried.statusCode(), retried.body());
        assertEquals(PRIVATE_MOBILE_DISPLAY, json(retried).path("mobile").asText());
    }

    @Test
    void runtimeObserverCorrelatesExactEdgeAndRejectsWrongMaskedOrMissingRows()
            throws Exception {
        World world = world("runtime-observer-controls");
        A1A7IsolatedTarget.Target runtimeTarget =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNTIME_ROLE);
        A1A7IsolatedTarget.Target runnerTarget =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNNER_ROLE);
        CompletableFuture<Void> runtimeWaiter = null;
        long waiterPid;
        try (Connection holder = runner.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "select id from listings where id=? for update")) {
                statement.setLong(1, world.listingId());
                statement.executeQuery().close();
            }
            long blockerPid = longValueOn(holder, "select pg_backend_pid()");
            runtimeWaiter = CompletableFuture.runAsync(() -> {
                try (Connection waiter = runtimeTarget.open();
                     PreparedStatement statement = waiter.prepareStatement(
                             "select id from listings where id=? for update")) {
                    statement.setLong(1, world.listingId());
                    statement.executeQuery().close();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            WaitObservation first = awaitRuntimeWaitObservation(blockerPid, 10_000);
            WaitObservation repeated = awaitRuntimeWaitObservation(blockerPid, 10_000);
            waiterPid = first.waiterPid();
            assertEquals(waiterPid, repeated.waiterPid());
            assertEquals("Lock", first.waitEventType());
            assertTrue(first.blockerPids().contains(blockerPid));
            assertThrows(AssertionError.class,
                    () -> awaitRuntimeWaitObservation(blockerPid + 10_000_000L, 500));

            try (Connection runnerObserver = runnerTarget.open()) {
                assertTrue(lockWaitRows(runnerObserver, blockerPid).isEmpty(),
                        "runner observation must remain masked and never satisfy the witness");
            }
            holder.rollback();
        } finally {
            if (runtimeWaiter != null) runtimeWaiter.get(10, TimeUnit.SECONDS);
        }

        List<List<Object>> gone = psqlRows(
                "select count(*) from pg_stat_activity where pid=" + waiterPid);
        assertEquals(0L, ((Number) gone.get(0).get(0)).longValue(),
                "runtime waiter must close after holder release");

        List<List<Object>> ordered = psqlRows(
                "select value from (values (1,'first'),(2,null),(3,'third')) "
                        + "v(ordinal,value) order by ordinal");
        assertEquals(3, ordered.size());
        assertEquals("first", ordered.get(0).get(0));
        assertNull(ordered.get(1).get(0));
        assertEquals("third", ordered.get(2).get(0));
        assertEquals("first", psqlScalar(
                "select value from (values ('first'),('second')) v(value) order by value"));
        assertNull(psqlScalar("select null::text"));

        IllegalStateException queryFailure = assertThrows(IllegalStateException.class,
                () -> psqlRows("select synthetic_missing_runtime_column"));
        assertTrue(queryFailure.getMessage().contains("witness query failed"));
    }

    private String awaitRuntimeWait(long blockerPid, long timeoutMillis) throws Exception {
        WaitObservation observation = awaitRuntimeWaitObservation(blockerPid, timeoutMillis);
        return observation.evidence() + "\n";
    }

    private WaitObservation awaitRuntimeWaitObservation(long blockerPid, long timeoutMillis)
            throws Exception {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNTIME_ROLE);
        requireObserverIdentity(target);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try (Connection observer = target.open()) {
            while (System.nanoTime() < deadline) {
                List<WaitObservation> rows = lockWaitRows(observer, blockerPid);
                if (rows.size() == 1) return rows.get(0);
                if (rows.size() > 1) {
                    throw new AssertionError("ambiguous runtime lock edge for blocker "
                            + blockerPid + ": " + rows);
                }
                Thread.sleep(100);
            }
            throw new AssertionError("exact runtime waiter did not block on PostgreSQL blocker "
                    + blockerPid + "; diagnostic=" + diagnostic(observer));
        }
    }

    private static List<WaitObservation> lockWaitRows(Connection observer, long blockerPid)
            throws SQLException {
        return queryLockWaitRows(observer,
                "select pid,coalesce(wait_event_type,'<null>'),"
                        + "coalesce(wait_event,'<null>'),"
                        + "pg_blocking_pids(pid),coalesce(state,'<null>'),"
                        + "coalesce(query,'<null>') "
                        + "from pg_stat_activity "
                        + "where datname=current_database() "
                        + "and usename=current_user "
                        + "and pid<>pg_backend_pid() "
                        + "and wait_event_type='Lock' "
                        + "and ?=any(pg_blocking_pids(pid)) "
                        + "order by pid", blockerPid);
    }

    private static List<WaitObservation> queryLockWaitRows(Connection observer, String sql,
                                                            long blockerPid)
            throws SQLException {
        List<WaitObservation> observations = new ArrayList<>();
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setLong(1, blockerPid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long waiterPid = rows.getLong(1);
                    String waitEventType = rows.getString(2);
                    String waitEvent = rows.getString(3);
                    Object[] blockers = (Object[]) rows.getArray(4).getArray();
                    List<Long> blockerValues = Arrays.stream(blockers)
                            .map(Number.class::cast)
                            .map(Number::longValue)
                            .toList();
                    String state = rows.getString(5);
                    String query = rows.getString(6);
                    observations.add(new WaitObservation(waiterPid, waitEventType, waitEvent,
                            blockerValues, state, query));
                }
            }
        }
        return observations;
    }

    private static String diagnostic(Connection observer) throws SQLException {
        try (var statement = observer.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select coalesce(string_agg(
                         pid || '|' || coalesce(state,'<null>') || '|' ||
                         coalesce(wait_event_type,'<null>') || '|' ||
                         coalesce(wait_event,'<null>') || '|' ||
                         array_to_string(pg_blocking_pids(pid), ',', '<null>'), ';'), 'none')
                     from pg_stat_activity
                     where datname=current_database()
                       and usename=current_user
                       and pid<>pg_backend_pid()
                     """)) {
            return rows.next() ? rows.getString(1) : "none";
        }
    }

    private static void requireObserverIdentity(A1A7IsolatedTarget.Target target)
            throws SQLException {
        try (Connection connection = target.open();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select current_database(),current_user,current_schema()")) {
            if (!rows.next()
                    || !A1A7IsolatedTarget.DATABASE.equals(rows.getString(1))
                    || !A1A7IsolatedTarget.RUNTIME_ROLE.equals(rows.getString(2))
                    || !target.schema().equals(rows.getString(3))) {
                throw new IllegalStateException(
                        "runtime observer database/role/schema identity mismatch");
            }
        }
    }

    private static long waitPid(String witness) {
        for (String line : witness.lines().toList()) {
            int marker = line.indexOf("waiter_pid=");
            if (marker >= 0) {
                String digits = line.substring(marker + "waiter_pid=".length())
                        .split("[^0-9]", 2)[0];
                return Long.parseLong(digits);
            }
        }
        throw new IllegalArgumentException("witness has no waiter PID");
    }

    private void clearQueuedPhoneDeliveries() {
        runner.update("""
                update lead_phone_delivery_outbox
                set state='SUPPRESSED', lease_until=null, lease_token=null,
                    payload_ciphertext='', payload_iv='', payload_key_version='PURGED',
                    updated_at=clock_timestamp()
                where state in ('QUEUED','DISPATCHING')
                """);
    }

    private LeadPhoneDeliveryClaimService claimService(
            LeadPhoneDeliveryOutboxRepository outboxRepository) {
        return new LeadPhoneDeliveryClaimService(
                outboxRepository, phoneChallenges, leadRepository, sourceEventRepository,
                databaseClock, protection, phoneProvider, staffAccess, securityGuards,
                mapper, entityManager);
    }

    private void installFailpoint(String schema, String triggerName, String table) {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.MIGRATOR_ROLE);
        try (Connection connection = target.open(); var statement = connection.createStatement()) {
            statement.execute("create or replace function " + schema + "." + triggerName
                    + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'N1_SYNTHETIC_FAILPOINT'; end $$");
            statement.execute("create trigger " + triggerName + " before update on "
                    + schema + "." + table + " for each row execute function "
                    + schema + "." + triggerName + "()");
        } catch (Exception exception) {
            throw new IllegalStateException("N1 synthetic failpoint installation failed",
                    exception);
        }
    }

    private void removeFailpoint(String schema, String triggerName, String table) {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.MIGRATOR_ROLE);
        try (Connection connection = target.open(); var statement = connection.createStatement()) {
            statement.execute("drop trigger if exists " + triggerName + " on "
                    + schema + "." + table);
            statement.execute("drop function if exists " + schema + "." + triggerName + "()");
        } catch (Exception exception) {
            throw new IllegalStateException("N1 synthetic failpoint cleanup failed", exception);
        }
    }

    private LeadPhoneDeliveryOutboxRepository pauseAfterDiscovery(
            LeadPhoneDeliveryOutboxRepository delegate,
            CountDownLatch discovered,
            CountDownLatch resume) {
        return (LeadPhoneDeliveryOutboxRepository) Proxy.newProxyInstance(
                LeadPhoneDeliveryOutboxRepository.class.getClassLoader(),
                new Class<?>[]{LeadPhoneDeliveryOutboxRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "PausedLeadPhoneDeliveryOutboxRepository";
                            default -> method.invoke(delegate, arguments);
                        };
                    }
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("findDueIds".equals(method.getName())) {
                            discovered.countDown();
                            if (!resume.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "stale worker was not released by the test");
                            }
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private World world(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long city = insert("insert into supported_cities(code,display_name,active) "
                        + "values (?,?,true) returning id", "a1a7-" + label + "-" + suffix,
                "Synthetic " + label + " City");
        long team = insert("insert into operating_teams(city_id,code,display_name,active) "
                        + "values (?,?,?,true) returning id", city,
                "a1a7-team-" + label + "-" + suffix, "Synthetic " + label + " Team");
        long foreignCity = insert("insert into supported_cities(code,display_name,active) "
                        + "values (?,?,true) returning id", "a1a7-foreign-" + suffix,
                "Synthetic Foreign City");
        long foreignTeam = insert("insert into operating_teams(city_id,code,display_name,active) "
                        + "values (?,?,?,true) returning id", foreignCity,
                "a1a7-foreign-team-" + suffix, "Synthetic Foreign Team");
        long locality = insert("insert into localities(city,sector_name,supported_city_id) "
                        + "values (?,?,?) returning id", "Synthetic " + label,
                "Sector " + suffix, city);
        Actor ravi = staff(label + "-ravi", "OPS_COORDINATE", "TEAM", null, team);
        Actor peer = staff(label + "-peer", "OPS_COORDINATE", "TEAM", null, team);
        Actor supervisor = staff(label + "-supervisor", "OPS_SUPERVISE", "TEAM", null, team);
        Actor admin = staff(label + "-admin", "STAFF_ADMIN", "GLOBAL", null, null);
        Actor ge = staff(label + "-ge", null, null, null, null);
        Actor foreignRavi = staff(label + "-foreign", "OPS_COORDINATE", "TEAM",
                null, foreignTeam);
        Actor rahul = customer(label + "-rahul", "+919700001" + suffix.substring(0, 3));
        Actor amit = customer(label + "-amit", "+919700002" + suffix.substring(0, 3));
        long lessorUser = insert("insert into users(email,full_name,phone_number,role) "
                        + "values (?,?,?,'ROLE_LANDLORD') returning id",
                "lessor-" + suffix + "@example.test", "Synthetic Lessor", PRIVATE_MOBILE);
        long profile = insert("insert into lessor_profiles(linked_user_id,display_name,mobile_number,"
                        + "email,source_type,created_at,updated_at) values (?,?,?,?,'CRM',"
                        + "clock_timestamp(),clock_timestamp()) returning id",
                lessorUser, "Synthetic Lessor", PRIVATE_MOBILE, PRIVATE_EMAIL);
        long listing = insert("insert into listings(title,description,address,city,sector,"
                        + "listing_type,status,property_type,bhk_count,workflow_status,"
                        + "canonical_locality_id,lessor_profile_id,owner_user_id,location_resolution,"
                        + "version,published_at) values (?,?,?,?,?,'RENT','ACTIVE','FLAT','2 BHK',"
                        + "'PUBLISHED',?,?,?,'CANONICAL',0,clock_timestamp()) returning id",
                "Synthetic Alpha Residency " + suffix,
                "Calm two-bedroom synthetic home", "Synthetic public address",
                "Synthetic " + label + " City", "Sector " + suffix, locality, profile,
                lessorUser);
        runner.update("insert into rental_details(id,monthly_rent,security_deposit,bachelor_allowed) "
                + "values (?,18000,36000,true)", listing);
        return new World(city, team, locality, listing, ravi, peer, supervisor, admin,
                ge, foreignRavi, rahul, amit);
    }

    private Lead capture(World world, Actor actor, String name, String phone) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("cityId", world.cityId());
        body.put("teamId", world.teamId());
        body.put("customerName", name);
        body.put("contactPhone", phone);
        body.put("customerSaid", "Synthetic call: needs a rented two-bedroom home.");
        body.put("intent", "RENT");
        body.put("propertyType", "Flat");
        body.put("bhk", "2 BHK");
        body.put("budgetMin", 15000);
        body.put("budgetMax", 22000);
        body.put("currency", "INR");
        body.put("sourceChannel", "PHONE_CALL");
        HttpResponse<String> created = send("POST", "/api/v1/operations/leads",
                actor.token(), mapper.writeValueAsString(body),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode result = json(created);
        long leadId = result.path("summary").path("id").asLong();
        JsonNode source = result.path("sourceAttributions").get(0);
        return new Lead(leadId, result.path("summary").path("version").asLong(),
                UUID.fromString(source.path("sourceEventId").asText()),
                result.path("contactPhoneRevision").asLong());
    }

    private JsonNode shareSetup(World world, Lead lead, long listingId) throws Exception {
        long currentVersion = lead(lead.id()).version();
        JsonNode preference = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/contact-preferences",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", currentVersion,
                        "expectedPreferenceRevision", 0,
                        "channel", "WHATSAPP",
                        "destinationChoice", "SAME",
                        "consentStatus", "GRANTED",
                        "consentWordingVersion", "property-sharing-v1",
                        "evidenceMethod", "CUSTOMER_VERBAL")),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        JsonNode shortlist = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/shortlists",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", preference.path("leadVersion").asLong(),
                        "listingIds", List.of(listingId))),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        HttpResponse<String> shareResponse = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/shares",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "operationId", UUID.randomUUID().toString(),
                        "expectedLeadVersion", shortlist.path("leadVersion").asLong(),
                        "shortlistId", shortlist.path("id").asLong(),
                        "expectedShortlistVersion", shortlist.path("version").asLong(),
                        "contactPreferenceRevision", preference.path("revision").asLong(),
                        "mode", "MANUAL_BUSINESS_HANDOFF")), Map.of());
        assertEquals(201, shareResponse.statusCode(), shareResponse.body());
        ObjectNode result = mapper.createObjectNode();
        result.put("shortlistId", shortlist.path("id").asLong());
        result.put("shortlistVersion", shortlist.path("version").asLong());
        result.put("preferenceRevision", preference.path("revision").asLong());
        result.put("shareStatus", shareResponse.statusCode());
        result.set("share", json(shareResponse));
        return result;
    }

    private long createAdditionalListing(World world, String title) {
        Map<String, Object> source = runner.queryForMap(
                "select city,sector,canonical_locality_id,lessor_profile_id,owner_user_id "
                        + "from listings where id=?", world.listingId());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long listingId = insert("insert into listings(title,description,address,city,sector,"
                        + "listing_type,status,property_type,bhk_count,workflow_status,"
                        + "canonical_locality_id,lessor_profile_id,owner_user_id,location_resolution,"
                        + "version,published_at) values (?,?,?,?,?,'RENT','ACTIVE','FLAT','2 BHK',"
                        + "'PUBLISHED',?,?,?,'CANONICAL',0,clock_timestamp()) returning id",
                title + " " + suffix, "Synthetic second eligible rental",
                "Synthetic public address " + suffix, source.get("city"), source.get("sector"),
                source.get("canonical_locality_id"), source.get("lessor_profile_id"),
                source.get("owner_user_id"));
        runner.update("insert into rental_details(id,monthly_rent,security_deposit,"
                + "bachelor_allowed) values (?,18500,37000,true)", listingId);
        return listingId;
    }

    private long createDistinctContactListing(World world, int index) {
        Map<String, Object> source = runner.queryForMap(
                "select city,sector,canonical_locality_id from listings where id=?",
                world.listingId());
        String syntheticPhone = String.format(java.util.Locale.ROOT, "+919711%06d", index);
        long lessorUser = insert("insert into users(email,full_name,phone_number,role) "
                        + "values (?,?,?,'ROLE_LANDLORD') returning id",
                "volume-lessor-" + index + "-" + UUID.randomUUID() + "@example.test",
                "Synthetic Volume Lessor " + index, syntheticPhone);
        long profile = insert("insert into lessor_profiles(linked_user_id,display_name,"
                        + "mobile_number,email,source_type,created_at,updated_at) "
                        + "values (?,?,?,?,'CRM',clock_timestamp(),clock_timestamp()) returning id",
                lessorUser, "Synthetic Volume Lessor " + index, syntheticPhone,
                "volume-profile-" + index + "-" + UUID.randomUUID() + "@example.test");
        long listingId = insert("insert into listings(title,description,address,city,sector,"
                        + "listing_type,status,property_type,bhk_count,workflow_status,"
                        + "canonical_locality_id,lessor_profile_id,owner_user_id,location_resolution,"
                        + "version,published_at) values (?,?,?,?,?,'RENT','ACTIVE','FLAT','2 BHK',"
                        + "'PUBLISHED',?,?,?,'CANONICAL',0,clock_timestamp()) returning id",
                "Synthetic Volume Property " + index, "Synthetic distinct eligible rental",
                "Synthetic public address " + index, source.get("city"), source.get("sector"),
                source.get("canonical_locality_id"), profile, lessorUser);
        runner.update("insert into rental_details(id,monthly_rent,security_deposit,"
                + "bachelor_allowed) values (?,?,?,true)", listingId, 18000 + index, 36000);
        return listingId;
    }

    private JsonNode createShortlist(World world, Lead lead, List<Long> listingIds) throws Exception {
        long currentVersion = lead(lead.id()).version();
        return json(send("POST", "/api/v1/operations/leads/" + lead.id() + "/shortlists",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", currentVersion,
                        "listingIds", listingIds)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
    }

    private long associateProperty(World world, Lead lead, long listingId, long shortlistId,
                                   long shortlistVersion, long preferenceRevision) throws Exception {
        Lead current = lead(lead.id());
        JsonNode proposal = json(send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", current.version(),
                        "shortlistId", shortlistId,
                        "listingId", listingId,
                        "expectedShortlistVersion", shortlistVersion,
                        "expectedListingVersion", 0,
                        "contactPreferenceRevision", preferenceRevision)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        String token = proposal.path("handoffUrl").asText()
                .substring(proposal.path("handoffUrl").asText().indexOf("#visit=") + 7);
        Continuation context = exchange(token);
        JsonNode request = json(send("POST", "/api/v1/tenant/property-visit-commands",
                world.rahul().token(), ownRequestBody(world, listingId, context, true),
                Map.of("Cookie", "pathome_visit_context=" + context.token())));
        assertTrue(request.path("created").asBoolean(), request.toString());
        JsonNode proposalView = json(send("GET",
                "/api/v1/operations/leads/" + lead.id() + "/visit-proposals/"
                        + proposal.path("proposalId").asText(),
                world.ravi().token(), null, Map.of()));
        JsonNode claim = proposalView.path("associationClaims").get(0);
        assertEquals(200, status("POST", "/api/v1/operations/leads/" + lead.id()
                        + "/visit-proposals/" + proposal.path("proposalId").asText()
                        + "/association-claims/" + claim.path("claimId").asText() + "/decision",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "decision", "CONFIRM",
                        "expectedClaimVersion", claim.path("version").asLong(),
                        "reasonCode", "CUSTOMER_CONSENT_VERIFIED")),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        return request.path("requestId").asLong();
    }

    private PhoneChallenge issuePhone(World world, Lead lead) throws Exception {
        Lead current = lead(lead.id());
        HttpResponse<String> response = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/phone-verifications",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", current.version(),
                        "sourceEvent", Map.of("id", lead.sourceId().toString()),
                        "expectedContactPhoneRevision", current.phoneRevision(),
                        "noticeAgreed", true)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, response.statusCode(), response.body());
        JsonNode result = json(response);
        return new PhoneChallenge(UUID.fromString(result.path("challengeId").asText()),
                result.path("challengeVersion").asLong(),
                result.path("generation").asInt(), response.body());
    }

    private HttpResponse<String> confirmOperator(Actor actor, Lead lead, PhoneChallenge challenge,
                                                  String code, UUID operation) throws Exception {
        long currentChallengeVersion = longValue(
                "select version from lead_phone_challenges where id=?", challenge.id());
        return send("POST", "/api/v1/operations/leads/" + lead.id()
                        + "/phone-verifications/" + challenge.id() + "/confirm",
                actor.token(), mapper.writeValueAsString(Map.of(
                        "expectedLeadVersion", lead(lead.id()).version(),
                        "expectedChallengeVersion", currentChallengeVersion,
                        "code", code)),
                Map.of("Idempotency-Key", operation.toString()));
    }

    private DeliveryMaterial deliveryMaterial(UUID challengeId) throws Exception {
        Map<String, Object> row = runner.queryForMap(
                "select payload_ciphertext,payload_iv,payload_key_version "
                        + "from lead_phone_delivery_outbox where challenge_id=?", challengeId);
        String plain = protection.reveal(
                (String) row.get("payload_ciphertext"), (String) row.get("payload_iv"),
                (String) row.get("payload_key_version"));
        JsonNode payload = mapper.readTree(plain);
        return new DeliveryMaterial(payload.path("code").asText(),
                payload.path("confirmationUrl").asText());
    }

    private UUID createAvailability(World world, Lead lead, JsonNode setup,
                                    boolean genuine) throws Exception {
        return createAvailability(world, lead, setup, genuine, world.listingId());
    }

    private UUID createAvailability(World world, Lead lead, JsonNode setup,
                                    boolean genuine, long listingId) throws Exception {
        Lead current = lead(lead.id());
        HttpResponse<String> response = send("POST",
                "/api/v1/operations/leads/" + lead.id() + "/availability-enquiries",
                world.ravi().token(), mapper.writeValueAsString(Map.of(
                        "shortlistId", setup.path("shortlistId").asLong(),
                        "listingId", listingId,
                        "sourceEventId", lead.sourceId().toString(),
                        "expectedLeadVersion", current.version(),
                        "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                        "expectedListingVersion", world.listingVersion(),
                        "genuineEnquiryAttested", genuine)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).path("enquiryId").asText());
    }

    private String revealBody(World world, Lead lead, JsonNode setup, UUID enquiryId) throws Exception {
        return revealBody(world, lead, setup, enquiryId, world.listingId());
    }

    private String revealBody(World world, Lead lead, JsonNode setup, UUID enquiryId,
                              long listingId) throws Exception {
        Lead current = lead(lead.id());
        JsonNode enquiry = json(send("GET", "/api/v1/operations/leads/" + lead.id()
                + "/availability-enquiries/" + enquiryId, world.ravi().token(),
                null, Map.of()));
        return mapper.writeValueAsString(Map.of(
                "expectedLeadVersion", current.version(),
                "expectedShortlistVersion", setup.path("shortlistVersion").asLong(),
                "expectedListingVersion", 0,
                "expectedEnquiryVersion", enquiry.path("version").asLong(),
                "expectedContactPhoneRevision", current.phoneRevision(),
                "genuineEnquiryAttested", true));
    }

    private Continuation exchange(String token) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/lead-visit-handoffs/exchange",
                null, mapper.writeValueAsString(Map.of("token", token)), Map.of());
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow();
        String continuation = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        return new Continuation(json(response).path("contextId").asText(), continuation);
    }

    private String ownRequestBody(World world, Continuation context, boolean associate) throws Exception {
        return ownRequestBody(world, world.listingId(), context, associate);
    }

    private String ownRequestBody(World world, long listingId, Continuation context,
                                  boolean associate) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("listingId", listingId);
        body.put("expectedListingVersion", world.listingVersion());
        body.put("explicitVisitIntent", true);
        body.put("acknowledgeExistingRequest", false);
        if (context != null) body.put("handoffContextId", context.contextId());
        body.put("associateEnquiry", associate);
        body.put("consentWordingVersion", associate ? CONSENT_VERSION : null);
        ObjectNode request = body.putObject("request");
        request.put("budgetMin", 15000);
        request.put("budgetMax", 22000);
        request.put("preferredAreas", "Synthetic preferred locality");
        request.put("moveInTiming", "Within 30 days");
        request.put("preferredVisitTiming", "Tomorrow morning");
        request.put("note", "Synthetic customer intent; no appointment is implied.");
        return mapper.writeValueAsString(body);
    }

    private JsonNode publicProperty(long listingId) throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/properties/" + listingId,
                null, null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        return json(response);
    }

    private void assertNoPii(JsonNode payload) {
        String text = payload.toString();
        assertFalse(text.contains(PRIVATE_MOBILE));
        assertFalse(text.contains(PRIVATE_MOBILE_DISPLAY));
        assertFalse(text.contains(PRIVATE_EMAIL));
        assertFalse(text.contains("Rahul"));
        assertFalse(text.contains("Synthetic Lessor"));
        assertFalse(text.contains("Synthetic public address"));
    }

    private void assertNoSecret(String payload, String secret) {
        assertFalse(payload.contains(secret));
        assertFalse(payload.contains("verificationDigest"));
        assertFalse(payload.contains("confirmationToken"));
    }

    private void assertNoStore(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("")
                .toLowerCase(java.util.Locale.ROOT).contains("no-store"));
    }

    private HttpResponse<String> send(String method, String path, String token, String body,
                                      Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String method, String path, String token, String body,
                       Map<String, String> headers) throws Exception {
        return send(method, path, token, body, headers).statusCode();
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private Actor staff(String label, String capability, String scope, Long cityId, Long teamId) {
        long userId = customerId(label);
        runner.update("insert into employee_profiles(user_id,role_type,closed_deals_count,"
                        + "staff_active,staff_activated_at,version) "
                        + "values (?,'OPERATIONS',0,true,clock_timestamp(),0)", userId);
        Long grantId = null;
        if (capability != null) {
            grantId = insert("insert into staff_access_grants(user_id,capability,scope_type,"
                            + "city_id,team_id,effective_at,expires_at,grant_reason_code,"
                            + "provisioning_source) values (?,?,?,?,?,clock_timestamp()-"
                            + "interval '1 hour',NULL,'A1A7_ISOLATED_TEST','ADMIN_API') returning id",
                    userId, capability, scope, cityId, teamId);
        }
        String email = "a1a7-" + label + "-" + UUID.randomUUID().toString().substring(0, 6)
                + "@example.test";
        runner.update("update users set email=? where id=?", email, userId);
        return new Actor(label, userId, grantId,
                jwt.generateToken(userId, email, "ROLE_TENANT"));
    }

    private Actor customer(String label, String phone) {
        long userId = customerId(label);
        String email = "a1a7-customer-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        runner.update("update users set email=?,phone_number=? where id=?", email, phone, userId);
        return new Actor(label, userId, null, jwt.generateToken(userId, email, "ROLE_TENANT"));
    }

    private Actor authenticatedCustomer(String label, String email, String phone, String password) {
        long userId = insert("insert into users(email,full_name,phone_number,role,"
                        + "free_visits_remaining,password_hash) "
                        + "values (?,?,?,'ROLE_TENANT',5,?) returning id",
                email, label, phone, passwordEncoder.encode(password));
        return new Actor(label, userId, null,
                jwt.generateToken(userId, email, "ROLE_TENANT"));
    }

    private String expiredTenantToken(Actor actor) {
        String email = text("select email from users where id=?", actor.userId());
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("user_id", actor.userId())
                .claim("email", email)
                .claim("role", "ROLE_TENANT")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(
                        "pathome-a1-a7-http-jwt-secret-32-bytes-minimum"
                                .getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private long customerId(String label) {
        return insert("insert into users(email,full_name,phone_number,role,free_visits_remaining) "
                        + "values (?,?,?,'ROLE_TENANT',5) returning id",
                "placeholder-" + UUID.randomUUID() + "@example.test", label,
                "+919700009999");
    }

    private long insert(String sql, Object... arguments) {
        Long value = migrator.queryForObject(sql, Long.class, arguments);
        if (value == null) throw new IllegalStateException("fixture insert returned no ID");
        return value;
    }

    private Lead lead(long id) {
        Map<String, Object> row = runner.queryForMap(
                "select l.version,l.contact_phone_revision,"
                        + "(select id::text from lead_source_events e where e.lead_id=l.id "
                        + "and e.processing_state='PROCESSED' order by received_at limit 1) source_id "
                        + "from lead_routing_queue l where l.id=?", id);
        return new Lead(id, ((Number) row.get("version")).longValue(),
                UUID.fromString((String) row.get("source_id")),
                ((Number) row.get("contact_phone_revision")).longValue());
    }

    private String text(String sql, Object... arguments) {
        return runner.queryForObject(sql, String.class, arguments);
    }

    private long longValue(String sql, Object... arguments) {
        Long value = runner.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private boolean booleanValue(String sql, Object... arguments) {
        Boolean value = runner.queryForObject(sql, Boolean.class, arguments);
        return Boolean.TRUE.equals(value);
    }

    private static long longValueOn(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("scalar query returned no row");
            return rows.getLong(1);
        }
    }

    private static String textValueOn(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("scalar query returned no row");
            return rows.getString(1);
        }
    }

    private static String psqlScalar(String sql) throws Exception {
        List<List<Object>> rows = psqlRows(sql);
        if (rows.isEmpty()) return null;
        Object value = rows.get(0).get(0);
        return value == null ? null : String.valueOf(value);
    }

    private static List<List<Object>> psqlRows(String sql) throws Exception {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNTIME_ROLE);
        requireObserverIdentity(target);
        try (Connection connection = target.open();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            List<List<Object>> result = new ArrayList<>();
            while (rows.next()) {
                List<Object> row = new ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(rows.getObject(column));
                }
                result.add(Collections.unmodifiableList(row));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("PostgreSQL witness query failed", exception);
        }
    }

    private static void psqlExecute(String sql) throws Exception {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.MIGRATOR_ROLE);
        try (Connection connection = target.open();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("PostgreSQL fixture DDL failed", exception);
        }
    }

    private int intValue(String sql, Object... arguments) {
        Integer value = runner.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static String flip(String code) {
        char first = code.charAt(0);
        return (first == '0' ? '1' : '0') + code.substring(1);
    }

    private static void assertNotEquals(Object first, Object second) {
        assertFalse(java.util.Objects.equals(first, second));
    }

    private record Actor(String label, long userId, Long grantId, String token) {}
    private record Lead(long id, long version, UUID sourceId, long phoneRevision) {}
    private record PhoneChallenge(UUID id, long version, int generation, String issueBody) {}
    private record DeliveryMaterial(String code, String confirmationUrl) {}
    private record GuardBlocker(String name, String sql,
                                java.util.function.ToLongFunction<World> value) {}
    private record WaitObservation(long waiterPid, String waitEventType, String waitEvent,
                                   List<Long> blockerPids, String state, String query) {
        String evidence() {
            return "blocker_pid=" + blockerPids.get(0)
                    + "\nwaiter_pid=" + waiterPid
                    + "\nwait_event_type=" + waitEventType
                    + "\nwait_event=" + waitEvent
                    + "\nblocking_pids=" + blockerPids;
        }
    }
    private record Continuation(String contextId, String token) {}
    private record World(long cityId, long teamId, long localityId, long listingId,
                         Actor ravi, Actor peer, Actor supervisor, Actor admin, Actor ge,
                         Actor foreignRavi, Actor rahul, Actor amit) {
        long listingVersion() {
            return 0L;
        }
    }
}
