package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.service.BatchPropertyPublishingService;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * Real HTTP/PostgreSQL admission for the uploader P1/P2 correction. It uses only a disposable schema and
 * synthetic users; no owner account, protected database or provider is touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p1-uploader-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=p1-uploader-http-test-otp-secret-32b",
        "cloudinary.api-key=p1-uploader-test-key",
        "cloudinary.api-secret=p1-uploader-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = DisposableIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class BackOfficePropertyAuthorizationHttpPostgresTest {
    private static final String SCHEMA =
            "p1_uploader_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DisposableIntegrationTarget.Target target;
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        target = DisposableIntegrationTarget.verifyFromEnvironment();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
            connection.setSchema(SCHEMA);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            Flyway.configure().dataSource(target.jdbcUrl(), target.username(), target.password())
                    .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration")
                    .baselineOnMigrate(true).baselineVersion("1").target("40").load().migrate();
            Flyway.configure().dataSource(target.jdbcUrl(), target.username(), target.password())
                    .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration")
                    .baselineOnMigrate(true).load().migrate();
        }
        registry.add("spring.datasource.url", () -> target.schemaUrl(SCHEMA));
        registry.add("spring.datasource.username", target::username);
        registry.add("spring.datasource.password", target::password);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!schemaCreated || target == null) return;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            schemaCreated = false;
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password());
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository profiles;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private PropertyUploadDraftRepository drafts;
    @Autowired private ListingRepository listings;
    @Autowired private LocalityRepository localities;
    @SpyBean private BatchPropertyPublishingService batchPublishing;
    @LocalServerPort private int port;

    @Test
    void activeOwnerAdminRetainsSingleAndBulkUploaderActions() throws Exception {
        Actor admin = admin("active-owner");
        assertEquals(200, send("POST", "/api/v1/properties/parse-prompt", admin.token(),
                "{\"prompt\":\"2 BHK flat in Indore Vijay Nagar rent 22000 owner 9826012345 deposit 44000\"}")
                .statusCode());
        long before = listings.count();
        assertEquals(200, create(admin, reviewedDto(null, "active-owner")).statusCode());
        assertEquals(before + 1, listings.count());
    }

    @Test
    void inactiveDemotedAndGrantRevokedAdminsAreDeniedWithRetainedTokens() throws Exception {
        Actor inactive = admin("inactive");
        EmployeeProfile inactiveProfile = profiles.findByUserId(inactive.user().getId()).orElseThrow();
        inactiveProfile.setStaffActive(false);
        profiles.saveAndFlush(inactiveProfile);
        assertDeniedSingle(inactive, "inactive");

        Actor demoted = admin("demoted");
        demoted.user().setRole(Role.ROLE_TENANT);
        users.saveAndFlush(demoted.user());
        assertDeniedSingle(demoted, "demoted");

        Actor revoked = admin("revoked-grant");
        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(revoked.user().getId()).get(0);
        grant.revoke(revoked.user(), Instant.now(), "P1_TEST_REVOKE");
        grants.saveAndFlush(grant);
        assertDeniedSingle(revoked, "revoked");
    }

    @Test
    void activeSubAdminRetainsNarrowUploaderButNotGlobalOrManualAdministration() throws Exception {
        Actor subAdmin = actor("subadmin", Role.ROLE_SUB_ADMIN, true);
        PropertyUploadDraft ownDraft = draft(subAdmin, "own");
        assertEquals(200, create(subAdmin, reviewedDto(ownDraft.getDraftId(), "subadmin")).statusCode());
        assertEquals(403, send("POST", "/api/v1/properties", subAdmin.token(),
                        mapper.writeValueAsString(reviewedDto(null, "manual")))
                .statusCode());
        assertEquals(403, send("GET", "/api/v1/admin/failed-uploads", subAdmin.token(), null).statusCode());
    }

    @Test
    void adminsAndSubAdminsCanCreateResumeAndUpdateFreshTextOnlyDrafts() throws Exception {
        Actor admin = admin("fresh-admin");
        Actor subAdmin = actor("fresh-subadmin", Role.ROLE_SUB_ADMIN, true);
        assertFreshTextDraft(admin, "SINGLE");
        assertFreshTextDraft(admin, "BATCH");
        assertFreshTextDraft(subAdmin, "SINGLE");
        assertFreshTextDraft(subAdmin, "BATCH");
    }

    @Test
    void grantRevocationBetweenCommittedBulkCardsPreservesEarlierSuccessAndBlocksTheRest() throws Exception {
        Actor admin = admin("bulk-revocation");
        PropertyUploadDraft batchDraft = draft(admin, "bulk-revocation");
        batchDraft.setDraftType("BATCH");
        batchDraft.setItemCount(2);
        drafts.saveAndFlush(batchDraft);

        CountDownLatch firstCardCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirstCard = new CountDownLatch(1);
        AtomicInteger publicationCalls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = publicationCalls.incrementAndGet();
            if (call == 1 && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        firstCardCommitted.countDown();
                        try {
                            if (!releaseFirstCard.await(8, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out waiting to release committed first bulk card");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                });
            }
            Object saved = invocation.callRealMethod();
            if (call == 1 && !TransactionSynchronizationManager.isSynchronizationActive()) {
                firstCardCommitted.countDown();
                if (!releaseFirstCard.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the first bulk card transaction");
                }
            }
            return saved;
        }).when(batchPublishing).publish(any(), anyList());

        Map<String, Object> first = reviewedDto(batchDraft.getDraftId(), "bulk-card-one");
        first.put("cardId", "card-one");
        Map<String, Object> second = reviewedDto(batchDraft.getDraftId(), "bulk-card-two");
        second.put("cardId", "card-two");
        String requestBody = mapper.writeValueAsString(Map.of(
                "draftId", batchDraft.getDraftId(),
                "listings", java.util.List.of(first, second)));

        CompletableFuture<HttpResponse<String>> responseFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return send("POST", "/api/v1/properties/create-batch", admin.token(), requestBody);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        assertTrue(firstCardCommitted.await(8, TimeUnit.SECONDS),
                "The first real REQUIRES_NEW card did not reach its committed barrier");
        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
        grant.revoke(admin.user(), Instant.now(), "P1_M21_BETWEEN_CARDS");
        grants.saveAndFlush(grant);
        releaseFirstCard.countDown();

        HttpResponse<String> response = responseFuture.get(15, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), response.body());
        var json = mapper.readTree(response.body());
        assertEquals(2, json.path("total").asInt());
        assertEquals(1, json.path("successCount").asInt());
        assertEquals(1, json.path("failedCount").asInt());
        assertEquals(1, json.path("createdIds").size());
        assertEquals(1, json.path("failedListings").size());

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            try (ResultSet rows = statement.executeQuery(
                    "select count(*) from listings where origin_draft_id like '"
                            + batchDraft.getDraftId().replace("'", "''") + "%'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void grantRevocationBeforeTheActualPerCardServiceCommitBoundaryPreventsTheCard() throws Exception {
        Actor admin = admin("bulk-service-boundary");
        PropertyUploadDraft batchDraft = draft(admin, "bulk-svc");
        batchDraft.setDraftType("BATCH");
        batchDraft.setItemCount(2);
        drafts.saveAndFlush(batchDraft);

        CountDownLatch serviceEntered = new CountDownLatch(1);
        CountDownLatch releaseService = new CountDownLatch(1);
        doAnswer(invocation -> {
            serviceEntered.countDown();
            if (!releaseService.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out parking before the per-card service guard");
            }
            return invocation.callRealMethod();
        }).when(batchPublishing).publish(any(), anyList());

        Map<String, Object> first = reviewedDto(batchDraft.getDraftId(), "service-card-one");
        first.put("cardId", "service-card-one");
        Map<String, Object> second = reviewedDto(batchDraft.getDraftId(), "service-card-two");
        second.put("cardId", "service-card-two");
        String requestBody = mapper.writeValueAsString(Map.of(
                "draftId", batchDraft.getDraftId(),
                "listings", java.util.List.of(first, second)));

        CompletableFuture<HttpResponse<String>> responseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/properties/create-batch", admin.token(), requestBody);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(serviceEntered.await(8, TimeUnit.SECONDS),
                "The first card did not reach the actual service boundary");

        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
        grant.revoke(admin.user(), Instant.now(), "P1_M21_SERVICE_BOUNDARY");
        grants.saveAndFlush(grant);
        releaseService.countDown();

        HttpResponse<String> response = responseFuture.get(15, TimeUnit.SECONDS);
        var json = mapper.readTree(response.body());
        assertEquals(0, json.path("successCount").asInt(), response.body());
        assertEquals(2, json.path("failedCount").asInt(), response.body());
        assertEquals(0, json.path("createdIds").size(), response.body());

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            try (ResultSet rows = statement.executeQuery(
                    "select count(*) from listings where origin_draft_id like '"
                            + batchDraft.getDraftId().replace("'", "''") + "%'")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void revocationAfterTheLastCommittedCardPreventsPostDenialDraftLearning() throws Exception {
        Actor admin = admin("bulk-after-last");
        PropertyUploadDraft batchDraft = draft(admin, "bulk-last");
        batchDraft.setDraftType("BATCH");
        batchDraft.setItemCount(2);
        drafts.saveAndFlush(batchDraft);

        CountDownLatch secondCardCommitted = new CountDownLatch(1);
        CountDownLatch releaseSecondCard = new CountDownLatch(1);
        AtomicInteger publicationCalls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = publicationCalls.incrementAndGet();
            Object saved = invocation.callRealMethod();
            if (call == 2 && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        secondCardCommitted.countDown();
                        try {
                            if (!releaseSecondCard.await(8, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out holding the final committed bulk card");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                });
            }
            return saved;
        }).when(batchPublishing).publish(any(), anyList());

        Map<String, Object> first = reviewedDto(batchDraft.getDraftId(), "after-last-one");
        first.put("cardId", "after-last-one");
        Map<String, Object> second = reviewedDto(batchDraft.getDraftId(), "after-last-two");
        second.put("cardId", "after-last-two");
        String requestBody = mapper.writeValueAsString(Map.of(
                "draftId", batchDraft.getDraftId(),
                "listings", java.util.List.of(first, second)));

        CompletableFuture<HttpResponse<String>> responseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/properties/create-batch", admin.token(), requestBody);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(secondCardCommitted.await(8, TimeUnit.SECONDS),
                "The final card did not reach its committed barrier");

        long parserExamplesBefore = scalar("select count(*) from parser_training_examples");
        long notificationsBefore = scalar("select count(*) from system_notifications");
        long auditEventsBefore = scalar("select count(*) from operational_audit_events");
        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
        grant.revoke(admin.user(), Instant.now(), "P1_M21_AFTER_LAST");
        grants.saveAndFlush(grant);
        releaseSecondCard.countDown();

        HttpResponse<String> response = responseFuture.get(15, TimeUnit.SECONDS);
        var json = mapper.readTree(response.body());
        assertEquals(2, json.path("successCount").asInt(), response.body());
        assertEquals(0, json.path("failedCount").asInt(), response.body());
        assertEquals(2, json.path("createdIds").size(), response.body());
        PropertyUploadDraft unchanged = drafts.findByDraftId(batchDraft.getDraftId()).orElseThrow();
        assertEquals("DRAFT", unchanged.getStatus(),
                "Post-denial final reconciliation must not mutate the draft");
        assertEquals(parserExamplesBefore, scalar("select count(*) from parser_training_examples"),
                "revoked final reconciliation must not commit learning rows");
        assertEquals(notificationsBefore, scalar("select count(*) from system_notifications"),
                "revoked final reconciliation must not emit notifications");
        assertEquals(auditEventsBefore, scalar("select count(*) from operational_audit_events"),
                "revoked final reconciliation must not emit final audit events");

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            try (ResultSet rows = statement.executeQuery(
                    "select count(*) from listings where origin_draft_id like '"
                            + batchDraft.getDraftId().replace("'", "''") + "%'")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    @Test
    void concurrentFirstSaveForTheSameDraftIdCannotDuplicateOrSwitchOwnership() throws Exception {
        Actor first = admin("draft-race-first");
        Actor second = admin("draft-race-second");
        String draftId = "draft-race-" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<HttpResponse<String>> firstSave = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/admin/drafts", first.token(),
                        mapper.writeValueAsString(Map.of(
                                "draftId", draftId,
                                "draftType", "SINGLE",
                                "status", "DRAFT",
                                "titleSummary", "Race first owner",
                                "itemCount", 1,
                                "version", 1,
                                "payload", "{\"owner\":\"first\"}")));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        CompletableFuture<HttpResponse<String>> secondSave = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/admin/drafts", second.token(),
                        mapper.writeValueAsString(Map.of(
                                "draftId", draftId,
                                "draftType", "SINGLE",
                                "status", "DRAFT",
                                "titleSummary", "Race second owner",
                                "itemCount", 1,
                                "version", 1,
                                "payload", "{\"owner\":\"second\"}")));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });

        start.countDown();
        List<Integer> statuses = new ArrayList<>(List.of(
                firstSave.get(15, TimeUnit.SECONDS).statusCode(),
                secondSave.get(15, TimeUnit.SECONDS).statusCode()));
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(200, 404), statuses);

        PropertyUploadDraft row = drafts.findByDraftId(draftId).orElseThrow();
        assertEquals(1, drafts.findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(
                first.user().getEmail(), "DISCARDED").stream()
                .filter(draft -> draftId.equals(draft.getDraftId())).count()
                + drafts.findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(
                second.user().getEmail(), "DISCARDED").stream()
                .filter(draft -> draftId.equals(draft.getDraftId())).count());
        assertTrue(first.user().getEmail().equals(row.getAdminId())
                || second.user().getEmail().equals(row.getAdminId()));
    }

    @Test
    void concurrentFirstSaveByTheSameActorSerializesWithoutOwnershipLossAndStaleRetryConflicts() throws Exception {
        Actor admin = admin("draft-same-actor-race");
        String draftId = "draft-same-actor-" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Function<String, CompletableFuture<HttpResponse<String>>> save = label ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        return send("POST", "/api/v1/admin/drafts", admin.token(),
                                mapper.writeValueAsString(Map.of(
                                        "draftId", draftId,
                                        "draftType", "SINGLE",
                                        "status", "DRAFT",
                                        "titleSummary", label,
                                        "itemCount", 1,
                                        "version", 1,
                                        "payload", "{\"rawPrompt\":\"" + label + "\"}")));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        CompletableFuture<HttpResponse<String>> first = save.apply("same-actor-first");
        CompletableFuture<HttpResponse<String>> second = save.apply("same-actor-second");
        start.countDown();
        List<Integer> statuses = new ArrayList<>(List.of(
                first.get(15, TimeUnit.SECONDS).statusCode(),
                second.get(15, TimeUnit.SECONDS).statusCode()));
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(200, 200), statuses,
                "create-or-update requests are serialized; both received a valid committed outcome");

        PropertyUploadDraft row = drafts.findByDraftId(draftId).orElseThrow();
        assertEquals(2, row.getVersion());
        assertEquals(admin.user().getEmail(), row.getAdminId(),
                "The serialized same-actor save must preserve draft ownership");
        assertEquals(409, send("POST", "/api/v1/admin/drafts", admin.token(),
                mapper.writeValueAsString(Map.of(
                        "draftId", draftId,
                        "draftType", "SINGLE",
                        "status", "DRAFT",
                        "titleSummary", "stale retry",
                        "itemCount", 1,
                        "version", 1,
                        "payload", "{\"rawPrompt\":\"stale retry\"}"))).statusCode());
    }

    @Test
    void foreignSingleAndBulkDraftReferencesFailBeforePrivateDataOrWrites() throws Exception {
        Actor owner = admin("foreign-owner");
        Actor caller = admin("foreign-caller");
        PropertyUploadDraft foreignDraft = draft(owner, "foreign");
        assertEquals(404, send("GET", "/api/v1/admin/drafts/" + foreignDraft.getDraftId(),
                caller.token(), null).statusCode());
        assertEquals(404, send("DELETE", "/api/v1/admin/drafts/" + foreignDraft.getDraftId(),
                caller.token(), null).statusCode());
        assertTrue(drafts.findByDraftId(foreignDraft.getDraftId()).isPresent());
        assertEquals(404, send("GET", "/api/v1/admin/drafts/draft-missing-" +
                UUID.randomUUID().toString().replace("-", ""), caller.token(), null).statusCode());

        PropertyUploadDraft published = draft(owner, "published");
        published.setPublishedPropertyId(9001L);
        published.setStatus("PUBLISHING");
        drafts.saveAndFlush(published);
        assertEquals(404, create(caller, reviewedDto(published.getDraftId(), "foreign-single")).statusCode());

        long before = listings.count();
        String body = mapper.writeValueAsString(Map.of(
                "draftId", foreignDraft.getDraftId(),
                "listings", java.util.List.of(reviewedDto(foreignDraft.getDraftId(), "foreign-bulk"))));
        HttpResponse<String> response = send("POST", "/api/v1/properties/create-batch",
                caller.token(), body);
        assertEquals(404, response.statusCode(), response.body());
        assertEquals(before, listings.count());
        assertFalse(response.body().contains(foreignDraft.getDraftId()));
    }

    private void assertDeniedSingle(Actor actor, String label) throws Exception {
        long before = listings.count();
        long parserExamplesBefore = scalar(
                "select count(*) from parser_training_examples");
        assertEquals(403, send("POST", "/api/v1/properties/parse-prompt", actor.token(),
                "{\"prompt\":\"2 BHK denied " + label + " parse\"}").statusCode());
        assertEquals(parserExamplesBefore, scalar(
                "select count(*) from parser_training_examples"),
                label + ": denied parse must not quarantine learning data");
        assertEquals(403, create(actor, reviewedDto(null, label)).statusCode());
        assertEquals(before, listings.count());
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = connect()) {
            connection.setSchema(SCHEMA);
            try (Statement statement = connection.createStatement();
                 var result = statement.executeQuery(sql)) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void assertFreshTextDraft(Actor actor, String draftType) throws Exception {
        String draftId = "draft-" + draftType.toLowerCase() + "-" +
                UUID.randomUUID().toString().replace("-", "");
        String initialPayload = "{\"rawPrompt\":\"fresh " + draftType + " text only\"}";
        String updatedPayload = "{\"rawPrompt\":\"resumed and edited " + draftType + " text only\"}";

        HttpResponse<String> created = send("POST", "/api/v1/admin/drafts", actor.token(),
                mapper.writeValueAsString(Map.of(
                        "draftId", draftId,
                        "draftType", draftType,
                        "status", "DRAFT",
                        "titleSummary", "Fresh " + draftType,
                        "itemCount", "BATCH".equals(draftType) ? 2 : 1,
                        "version", 1,
                        "payload", initialPayload)));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(1, mapper.readTree(created.body()).path("version").asInt());

        HttpResponse<String> resumed = send("GET", "/api/v1/admin/drafts/" + draftId,
                actor.token(), null);
        assertEquals(200, resumed.statusCode(), resumed.body());
        assertEquals(initialPayload, mapper.readTree(resumed.body()).path("payload").asText());

        HttpResponse<String> updated = send("PUT", "/api/v1/admin/drafts/" + draftId,
                actor.token(), mapper.writeValueAsString(Map.of(
                        "draftId", draftId,
                        "draftType", draftType,
                        "status", "DRAFT",
                        "titleSummary", "Updated " + draftType,
                        "itemCount", "BATCH".equals(draftType) ? 2 : 1,
                        "version", 1,
                        "payload", updatedPayload)));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(2, mapper.readTree(updated.body()).path("version").asInt());
        assertEquals(updatedPayload, mapper.readTree(updated.body()).path("payload").asText());
    }

    private HttpResponse<String> create(Actor actor, Map<String, Object> dto) throws Exception {
        return send("POST", "/api/v1/properties/create-from-parsed", actor.token(),
                mapper.writeValueAsString(dto));
    }

    private Map<String, Object> reviewedDto(String draftId, String label) {
        Map<String, Object> dto = new LinkedHashMap<>();
        if (draftId != null) dto.put("draftId", draftId);
        dto.put("adminVerified", true);
        dto.put("title", "2 BHK Flat in Vijay Nagar " + label);
        dto.put("description", "Synthetic P1 uploader verification property");
        dto.put("bhk", "2 BHK");
        dto.put("type", "Flat");
        dto.put("status", "LIVE");
        dto.put("city", "Indore");
        dto.put("sector", localities.findFirstByCityIgnoreCaseOrderByCityAscIdAsc("Indore")
                .orElseThrow().getSectorName());
        dto.put("ownerPhone", "+91 98260 12345");
        dto.put("rentAmount", 22000);
        dto.put("depositVal", "44000 Security Deposit");
        return dto;
    }

    private Actor admin(String label) {
        Actor actor = actor(label, Role.ROLE_ADMIN, true);
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "P1_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API));
        return actor;
    }

    private Actor actor(String label, Role role, boolean activeProfile) {
        User user = new User();
        user.setEmail("p1-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987555" + String.format("%04d", Math.abs(label.hashCode()) % 10_000));
        user.setRole(role);
        user = users.saveAndFlush(user);
        EmployeeProfile profile = new EmployeeProfile(user, "LEGACY_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(activeProfile);
        profiles.saveAndFlush(profile);
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), role.name()));
    }

    private PropertyUploadDraft draft(Actor owner, String label) {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("p1-http-" + label + "-" + UUID.randomUUID());
        draft.setAdminId(owner.user().getEmail());
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setPayload("{}");
        draft.setItemCount(1);
        draft.setVersion(1);
        return drafts.saveAndFlush(draft);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        else request.method(method, HttpRequest.BodyPublishers.noBody());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Actor(User user, String token) {}
}
