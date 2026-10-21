package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.MediaUploadClaimRepository;
import com.indore.pathome.spaces.repository.MediaUploadFailureRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.StaffAdministrationService;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Controlled application-security verification for uploader media without any real provider call. The
 * provider seam returns synthetic transfer metadata, while authorization, HTTP, transactions and
 * persistence use the real application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p1-uploader-media-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=p1-uploader-media-test-otp-secret-32b",
        "cloudinary.api-key=p1-uploader-test-key",
        "cloudinary.api-secret=p1-uploader-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = DisposableIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class UploaderMediaClosureHttpPostgresTest {
    private static final String SCHEMA =
            "p1_uploader_media_" + UUID.randomUUID().toString().replace("-", "");
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

    @MockBean private CloudinaryService cloudinaryService;

    @Autowired private JwtUtils jwt;
    @Autowired private StaffAdministrationService staffAdministration;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository profiles;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private ListingRepository listings;
    @Autowired private MediaUploadClaimRepository claims;
    @Autowired private PropertyMediaAssetRepository mediaAssets;
    @Autowired private MediaUploadFailureRepository failures;
    @Autowired private FailedUploadService failedUploadService;
    @LocalServerPort private int port;

    @Test
    void revocationCommittedAfterSyntheticTransferPreventsAttachmentAndReplay() throws Exception {
        Actor admin = admin("post-transfer");
        Listing listing = listing(admin);
        String uploadRequestId = "media-" + listing.getId() + "-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);

        CountDownLatch transferCompleted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        when(cloudinaryService.uploadImageResult(any(), anyString())).thenAnswer(invocation -> {
            transferCompleted.countDown();
            if (!releaseResponse.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out holding synthetic provider completion");
            }
            return new CloudinaryService.CloudinaryUploadResult(
                    "https://cdn.example/private-transfer.webp", uploadRequestId, "image");
        });

        CompletableFuture<HttpResponse<String>> upload = CompletableFuture.supplyAsync(() -> {
            try {
                return upload(listing.getId(), uploadRequestId, admin.token(), "private.webp",
                        new byte[]{1, 2, 3, 4});
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });

        boolean reachedTransfer = false;
        for (int attempt = 0; attempt < 80 && !reachedTransfer; attempt++) {
            reachedTransfer = transferCompleted.await(100, TimeUnit.MILLISECONDS);
            if (!reachedTransfer && upload.isDone()) {
                HttpResponse<String> early = upload.join();
                fail("Upload completed before the synthetic provider barrier: status="
                        + early.statusCode() + " body=" + early.body());
            }
        }
        assertTrue(reachedTransfer,
                "Synthetic provider transfer did not reach its post-transfer barrier");
        revoke(admin);
        releaseResponse.countDown();

        HttpResponse<String> denied = upload.get(15, TimeUnit.SECONDS);
        assertEquals(403, denied.statusCode(), denied.body());
        assertTrue(mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId()).isEmpty());
        assertFalse(denied.body().contains("private-transfer.webp"));
        var claim = claims.findByListingIdAndUploadRequestId(listing.getId(), uploadRequestId).orElseThrow();
        assertEquals("FAILED", claim.getStatus(),
                "Post-transfer authorization denial must close the owned claim, not leave a stale lease");
        assertTrue(claim.getErrorMessage() != null && claim.getErrorMessage().contains("Authorization denied"),
                claim.getErrorMessage());
        Listing afterDenial = listings.findById(listing.getId()).orElseThrow();
        assertTrue(afterDenial.getMediaGalleryUrls() == null || afterDenial.getMediaGalleryUrls().isBlank());
        assertTrue(failures.findByUploadRequestId(uploadRequestId).isEmpty(),
                "A denial must not be fabricated as a DB_PERSIST upload failure");

        PropertyMediaAsset winner = new PropertyMediaAsset();
        winner.setListingId(listing.getId());
        winner.setUploadRequestId(uploadRequestId);
        winner.setMediaUrl("https://cdn.example/private-winner.webp");
        mediaAssets.saveAndFlush(winner);

        HttpResponse<String> replay = upload(listing.getId(), uploadRequestId, admin.token(),
                "private.webp", new byte[]{5, 6, 7});
        assertEquals(403, replay.statusCode(), replay.body());
        assertFalse(replay.body().contains("private-winner.webp"));
        assertEquals(1, mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId()).size());
    }

    @Test
    void governedGrantRevocationCommittedFirstDeniesEveryRecoveryCommandWithoutMutation() throws Exception {
        Actor admin = admin("recovery-revoked");
        Actor revoker = admin("recovery-revoker-first");
        Listing listing = listing(admin);
        StaffAccessGrant grant =
                grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
        staffAdministration.revokeGrant(revoker.user().getId(), grant.getId(),
                "P1_RECOVERY_REVOKE_FIRST");
        assertNotNull(grants.findById(grant.getId()).orElseThrow().getRevokedAt());
        installSecurityContext(admin);

        MediaUploadFailure retryFailure = failure(listing, "revoke-first-retry");
        MediaUploadFailure dismissFailure = failure(listing, "revoke-first-dismiss");
        MediaUploadFailure resolutionFailure = failure(listing, "revoke-first-resolution");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> failedUploadService.markRetrying(retryFailure.getId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> failedUploadService.dismiss(dismissFailure.getId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> failedUploadService.recordResolution(
                        resolutionFailure.getId(), "https://cdn.example/denied.webp"));
        for (MediaUploadFailure failure : List.of(retryFailure, dismissFailure, resolutionFailure)) {
            MediaUploadFailure unchanged = failures.findById(failure.getId()).orElseThrow();
            assertEquals("FAILED", unchanged.getStatus());
            assertNull(unchanged.getResolvedMediaUrl());
        }
    }

    @Test
    void governedProfileDeactivationCommittedFirstDeniesRecoveryWithoutMutation() throws Exception {
        Actor admin = admin("profile-recovery-revoked");
        Actor revoker = admin("profile-recovery-revoker");
        Listing listing = listing(admin);
        EmployeeProfile profile = profiles.findByUserId(admin.user().getId()).orElseThrow();
        staffAdministration.deactivateStaff(revoker.user().getId(), admin.user().getId(),
                new com.indore.pathome.spaces.dto.StaffStateCommand(
                        profile.getVersion(), "P1_PROFILE_DEACTIVATE_FIRST"));
        assertFalse(profiles.findByUserId(admin.user().getId()).orElseThrow().isStaffActive());
        installSecurityContext(admin);

        MediaUploadFailure failure = failure(listing, "profile-revoked");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> failedUploadService.markRetrying(failure.getId()));
        assertEquals("FAILED", failures.findById(failure.getId()).orElseThrow().getStatus());
    }

    @Test
    void syntheticPersistedRoleDemotionCommittedFirstDeniesRecoveryWithoutMutation() {
        Actor admin = admin("role-recovery-demoted");
        Listing listing = listing(admin);
        User demoted = users.findById(admin.user().getId()).orElseThrow();
        demoted.setRole(Role.ROLE_TENANT);
        users.saveAndFlush(demoted);
        installSecurityContext(admin);

        MediaUploadFailure failure = failure(listing, "role-demoted");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> failedUploadService.markRetrying(failure.getId()));
        assertEquals("FAILED", failures.findById(failure.getId()).orElseThrow().getStatus());
        assertEquals(Role.ROLE_TENANT,
                users.findById(admin.user().getId()).orElseThrow().getRole());
    }

    @Test
    void authorityEvidenceLockOrderSerializesTheSupportedGrantRevocationWriter() throws Exception {
        Actor admin = admin("authority-lock-order");
        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);

        try (Connection evidenceHolder = connect()) {
            evidenceHolder.setSchema(SCHEMA);
            evidenceHolder.setAutoCommit(false);
            try (var profileLock = evidenceHolder.prepareStatement(
                    "select id from employee_profiles where user_id=? for share");
                 var grantLock = evidenceHolder.prepareStatement(
                         "select id from staff_access_grants where user_id=? and revoked_at is null "
                                 + "order by id for share");
                 var userLock = evidenceHolder.prepareStatement(
                         "select id from users where id=? for share")) {
                profileLock.setLong(1, admin.user().getId());
                try (var ignored = profileLock.executeQuery()) {}
                grantLock.setLong(1, admin.user().getId());
                try (var ignored = grantLock.executeQuery()) {}
                userLock.setLong(1, admin.user().getId());
                try (var ignored = userLock.executeQuery()) {}
            }

            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch writerCompleted = new CountDownLatch(1);
            Thread revocationWriter = new Thread(() -> {
                try (Connection writer = connect()) {
                    writer.setSchema(SCHEMA);
                    writer.setAutoCommit(false);
                    writerStarted.countDown();
                    try (var update = writer.prepareStatement(
                            "update staff_access_grants set revoked_at=?, revoked_by_user_id=?, "
                                    + "revoke_reason_code=? where id=? and revoked_at is null")) {
                        update.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
                        update.setLong(2, admin.user().getId());
                        update.setString(3, "P1_AUTHORITY_LOCK_ORDER");
                        update.setLong(4, grant.getId());
                        assertEquals(1, update.executeUpdate());
                    }
                    writer.commit();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                } finally {
                    writerCompleted.countDown();
                }
            }, "p1-grant-revocation-writer");
            revocationWriter.start();
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS), "Writer connection did not start");
            assertFalse(writerCompleted.await(500, TimeUnit.MILLISECONDS),
                    "Grant revocation did not wait for the held authority evidence");
            int waitingLocks = 0;
            try (Connection observer = connect(); var statement = observer.prepareStatement(
                    "select count(*) from pg_stat_activity where datname=current_database() "
                            + "and pid<>pg_backend_pid() and wait_event_type='Lock'")) {
                observer.setSchema(SCHEMA);
                for (int attempt = 0; attempt < 40 && waitingLocks == 0; attempt++) {
                    assertTrue(writerCompleted.getCount() > 0,
                            "Grant revocation completed before the lock witness was observed");
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        waitingLocks = rows.getInt(1);
                    }
                    if (waitingLocks == 0) {
                        Thread.sleep(25);
                    }
                }
            }
            assertTrue(waitingLocks > 0, "No waiting PostgreSQL lock was observed");
            evidenceHolder.commit();
            assertTrue(writerCompleted.await(8, TimeUnit.SECONDS), "Revocation did not finish after release");
            revocationWriter.join(2_000);
            assertFalse(revocationWriter.isAlive());
        }
    }

    @Test
    void actualRecoveryTransactionsSerializeWithSupportedGrantRevocationUntilCommit() throws Exception {
        installRecoveryPauseTrigger();
        List<RecoveryCase> cases = List.of(
                new RecoveryCase("retry", "RETRYING", true),
                new RecoveryCase("dismiss", "DISMISSED", false),
                new RecoveryCase("resolution", "RESOLVED", false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (RecoveryCase recoveryCase : cases) {
                Actor target = admin("recovery-command-" + recoveryCase.label());
                Actor revoker = admin("recovery-revoker-" + recoveryCase.label());
                Listing listing = listing(target);
                MediaUploadFailure failure = failure(listing, recoveryCase.label());
                StaffAccessGrant grant =
                        grants.findAllByUserIdOrderByCreatedAtDesc(target.user().getId()).get(0);

                try (Connection pauseHolder = connect()) {
                    pauseHolder.setSchema(SCHEMA);
                    pauseHolder.setAutoCommit(true);
                    try (var lock = pauseHolder.prepareStatement(
                            "select pg_advisory_lock(?)")) {
                        lock.setLong(1, RECOVERY_PAUSE_LOCK_KEY);
                        lock.execute();
                    }

                    Future<?> recovery = executor.submit(() -> {
                        installSecurityContext(target);
                        try {
                            switch (recoveryCase.label()) {
                                case "retry" -> failedUploadService.markRetrying(failure.getId());
                                case "dismiss" -> failedUploadService.dismiss(failure.getId());
                                case "resolution" -> failedUploadService.recordResolution(
                                        failure.getId(), "https://cdn.example/resolved.webp");
                                default -> throw new IllegalStateException("Unknown recovery case");
                            }
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    });

                    LockWitness recoveryWriter = awaitWaitingLock(-1);
                    assertTrue(recoveryWriter.pid() > 0,
                            recoveryCase.label() + ": recovery transaction did not hold authority before its lock wait");

                    Future<?> revocation = executor.submit(() ->
                            staffAdministration.revokeGrant(revoker.user().getId(), grant.getId(),
                                    "P1_ACTUAL_RECOVERY_REVOKE"));
                    LockWitness revocationWriter = awaitWaitingLock(recoveryWriter.pid());
                    assertTrue(revocationWriter.pid() > 0,
                            recoveryCase.label() + ": supported revocation did not wait for recovery evidence");
                    assertTrue(revocationWriter.blockingPids().contains(String.valueOf(recoveryWriter.pid())),
                            recoveryCase.label() + ": writer PID " + revocationWriter.pid()
                                    + " was not blocked by recovery PID " + recoveryWriter.pid()
                                    + " (" + revocationWriter.blockingPids() + ")");
                    assertFalse(revocation.isDone(), recoveryCase.label()
                            + ": revocation completed before the recovery transaction committed");

                    try (var unlock = pauseHolder.prepareStatement("select pg_advisory_unlock(?)")) {
                        unlock.setLong(1, RECOVERY_PAUSE_LOCK_KEY);
                        unlock.execute();
                    }
                    recovery.get(10, TimeUnit.SECONDS);
                    revocation.get(10, TimeUnit.SECONDS);
                }

                assertEquals(recoveryCase.expectedStatus(),
                        failures.findById(failure.getId()).orElseThrow().getStatus());
                assertNotNull(grants.findById(grant.getId()).orElseThrow().getRevokedAt(),
                        recoveryCase.label() + ": granted revocation was not persisted");

                installSecurityContext(target);
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
                    if (recoveryCase.retryCommand()) {
                        failedUploadService.markRetrying(failure.getId());
                    } else if ("dismiss".equals(recoveryCase.label())) {
                        failedUploadService.dismiss(failure.getId());
                    } else {
                        failedUploadService.recordResolution(
                                failure.getId(), "https://cdn.example/should-not-resolve.webp");
                    }
                });
                assertEquals(recoveryCase.expectedStatus(),
                        failures.findById(failure.getId()).orElseThrow().getStatus());
                SecurityContextHolder.clearContext();
            }
        } finally {
            SecurityContextHolder.clearContext();
            executor.shutdownNow();
            dropRecoveryPauseTrigger();
        }
    }

    @Test
    void mutationFirstMediaCommitMakesRevocationWaitAndPreservesOneTruthfulAttachment() throws Exception {
        Actor admin = admin("media-mutation-first");
        Actor revoker = admin("media-mutation-revoker");
        Listing listing = listing(admin);
        String uploadRequestId = "media-mutation-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);
        when(cloudinaryService.uploadImageResult(any(), anyString())).thenReturn(
                new CloudinaryService.CloudinaryUploadResult(
                        "https://cdn.example/mutation-first.webp", uploadRequestId, "image"));
        installUploadPauseTrigger();
        try (Connection pauseHolder = connect()) {
            pauseHolder.setSchema(SCHEMA);
            pauseHolder.setAutoCommit(true);
            try (var lock = pauseHolder.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, UPLOAD_PAUSE_LOCK_KEY);
                lock.execute();
            }

            CompletableFuture<HttpResponse<String>> upload = CompletableFuture.supplyAsync(() -> {
                try {
                    return upload(listing.getId(), uploadRequestId, admin.token(),
                            "mutation-first.webp", new byte[]{1, 2, 3, 4});
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            LockWitness uploadWriter = awaitWaitingLock(-1);
            assertTrue(uploadWriter.pid() > 0,
                    "Media mutation did not hold evidence before its attachment commit");

            StaffAccessGrant grant =
                    grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
            CompletableFuture<?> revocation = CompletableFuture.runAsync(() ->
                    staffAdministration.revokeGrant(revoker.user().getId(), grant.getId(),
                            "P1_MEDIA_MUTATION_FIRST"));
            LockWitness revocationWriter = awaitWaitingLock(uploadWriter.pid());
            assertTrue(revocationWriter.pid() > 0,
                    "Governance revocation did not wait for the media mutation");
            assertTrue(revocationWriter.blockingPids().contains(String.valueOf(uploadWriter.pid())),
                    revocationWriter.blockingPids());

            try (var unlock = pauseHolder.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, UPLOAD_PAUSE_LOCK_KEY);
                unlock.execute();
            }
            HttpResponse<String> accepted = upload.get(15, TimeUnit.SECONDS);
            assertEquals(201, accepted.statusCode(), accepted.body());
            revocation.get(10, TimeUnit.SECONDS);
        } finally {
            dropUploadPauseTrigger();
        }

        assertEquals(1, mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId()).size());
        Listing persisted = listings.findById(listing.getId()).orElseThrow();
        assertTrue(persisted.getMediaGalleryUrls().contains("https://cdn.example/mutation-first.webp"));
        var claim = claims.findByListingIdAndUploadRequestId(listing.getId(), uploadRequestId).orElseThrow();
        assertEquals("COMPLETED", claim.getStatus());

        HttpResponse<String> deniedAfterCommit = upload(listing.getId(),
                "media-after-mutation-" + UUID.randomUUID(), admin.token(),
                "denied.webp", new byte[]{5, 6});
        assertEquals(403, deniedAfterCommit.statusCode(), deniedAfterCommit.body());
        assertEquals(1, mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId()).size());
    }

    @Test
    void recoveryClaimCommitIsTruthfulButPostProviderRevocationBlocksCompletion() throws Exception {
        Actor admin = admin("recovery-provider-phase");
        Actor revoker = admin("recovery-provider-revoker");
        Listing listing = listing(admin);
        MediaUploadFailure failure = failure(listing, "provider-phase");

        CountDownLatch providerCompleted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(cloudinaryService.uploadImage(any(), anyString())).thenAnswer(invocation -> {
            providerCompleted.countDown();
            if (!releaseProvider.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out holding recovery provider completion");
            }
            return "https://cdn.example/recovery-after-revoke.webp";
        });

        CompletableFuture<HttpResponse<String>> retry = CompletableFuture.supplyAsync(() -> {
            try {
                return retryFailure(failure.getId(), admin.token(), "replacement.webp",
                        new byte[]{1, 2, 3, 4});
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertTrue(providerCompleted.await(8, TimeUnit.SECONDS),
                "Recovery did not reach the controlled provider transfer");
        assertEquals("RETRYING", failures.findById(failure.getId()).orElseThrow().getStatus(),
                "The earlier recovery claim must be committed before provider I/O");

        StaffAccessGrant grant =
                grants.findAllByUserIdOrderByCreatedAtDesc(admin.user().getId()).get(0);
        staffAdministration.revokeGrant(revoker.user().getId(), grant.getId(),
                "P1_RECOVERY_PROVIDER_PHASE");
        releaseProvider.countDown();

        HttpResponse<String> denied = retry.get(15, TimeUnit.SECONDS);
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals("RETRYING", failures.findById(failure.getId()).orElseThrow().getStatus(),
                "The committed claim remains truthful; completion must not fabricate RESOLVED");
        assertTrue(mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId()).isEmpty());
        Listing unchanged = listings.findById(listing.getId()).orElseThrow();
        assertTrue(unchanged.getMediaGalleryUrls() == null || unchanged.getMediaGalleryUrls().isBlank());
    }

    private static final long RECOVERY_PAUSE_LOCK_KEY = 7_310_421_210L;
    private static final long UPLOAD_PAUSE_LOCK_KEY = 7_310_421_211L;

    private void installRecoveryPauseTrigger() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            statement.execute("""
                    create or replace function p1_pause_recovery_update() returns trigger language plpgsql as $$
                    begin
                      perform pg_advisory_xact_lock(7310421210);
                      return new;
                    end
                    $$""");
            statement.execute("drop trigger if exists p1_pause_recovery on media_upload_failures");
            statement.execute("create trigger p1_pause_recovery before update on media_upload_failures "
                    + "for each row execute function p1_pause_recovery_update()");
        }
    }

    private void dropRecoveryPauseTrigger() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            statement.execute("drop trigger if exists p1_pause_recovery on media_upload_failures");
            statement.execute("drop function if exists p1_pause_recovery_update()");
        }
    }

    private void installUploadPauseTrigger() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            statement.execute("""
                    create or replace function p1_pause_media_attachment() returns trigger language plpgsql as $$
                    begin
                      perform pg_advisory_xact_lock(7310421211);
                      return new;
                    end
                    $$""");
            statement.execute("drop trigger if exists p1_pause_media on property_media_assets");
            statement.execute("create trigger p1_pause_media before insert on property_media_assets "
                    + "for each row execute function p1_pause_media_attachment()");
        }
    }

    private void dropUploadPauseTrigger() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            statement.execute("drop trigger if exists p1_pause_media on property_media_assets");
            statement.execute("drop function if exists p1_pause_media_attachment()");
        }
    }

    private LockWitness awaitWaitingLock(int excludedPid) throws Exception {
        try (Connection observer = connect(); var statement = observer.prepareStatement(
                "select pid, query, pg_blocking_pids(pid)::text from pg_stat_activity "
                        + "where datname=current_database() and pid<>pg_backend_pid() "
                        + "and pid<>? and wait_event_type='Lock' and state='active' "
                        + "and query not like '%pg_stat_activity%' order by query_start")) {
            observer.setSchema(SCHEMA);
            statement.setInt(1, excludedPid);
            for (int attempt = 0; attempt < 120; attempt++) {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return new LockWitness(rows.getInt(1), rows.getString(2), rows.getString(3));
                    }
                }
                Thread.sleep(25);
            }
        }
        return new LockWitness(0, "", "");
    }

    private record RecoveryCase(String label, String expectedStatus, boolean retryCommand) {}

    private record LockWitness(int pid, String query, String blockingPids) {}

    private HttpResponse<String> upload(Long listingId, String uploadRequestId, String token,
                                        String filename, byte[] bytes) throws Exception {
        String boundary = "----PathomeBoundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/properties/"
                        + listingId + "/tagged-media?uploadRequestId=" + uploadRequestId))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> retryFailure(Long failureId, String token,
                                              String filename, byte[] bytes) throws Exception {
        String boundary = "----PathomeBoundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/failed-uploads/"
                        + failureId + "/retry"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void installSecurityContext(Actor actor) {
        var credential = new UsernamePasswordAuthenticationToken(
                actor.user().getEmail(), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        credential.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(),
                actor.user().getId()));
        SecurityContextHolder.getContext().setAuthentication(credential);
    }

    private Actor admin(String label) {
        User user = new User();
        user.setEmail("p1-media-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987664" + String.format("%04d", Math.abs(label.hashCode()) % 10_000));
        user.setRole(Role.ROLE_ADMIN);
        user = users.saveAndFlush(user);
        EmployeeProfile profile = new EmployeeProfile(user, "LEGACY_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(user, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "P1_MEDIA_TEST", StaffGrantProvisioningSource.ADMIN_API));
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), Role.ROLE_ADMIN.name()));
    }

    private Listing listing(Actor owner) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Uploader media closure fixture");
        listing.setDescription("Synthetic uploader media closure fixture");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic address");
        listing.setCity("Indore");
        listing.setSector("Vijay Nagar");
        listing.setBhkCount("2 BHK");
        listing.setMonthlyRent(BigDecimal.valueOf(22000));
        listing.setSecurityDeposit(BigDecimal.valueOf(44000));
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setOwnerPhoneNumber("+919876543210");
        listing.setOriginDraftId("p1-media-owner-" + owner.user().getId() + "-" + UUID.randomUUID());
        return listings.saveAndFlush(listing);
    }

    private MediaUploadFailure failure(Listing listing, String label) {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setListingId(listing.getId());
        failure.setUploadRequestId("media-" + label + "-" + UUID.randomUUID());
        failure.setStatus("FAILED");
        failure.setMediaType("IMAGE");
        failure.setOriginalFilename(label + ".webp");
        failure.setFailureReason("Synthetic upload failure");
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        return failures.saveAndFlush(failure);
    }

    private void revoke(Actor actor) {
        StaffAccessGrant grant = grants.findAllByUserIdOrderByCreatedAtDesc(actor.user().getId()).get(0);
        grant.revoke(actor.user(), Instant.now(), "P1_POST_TRANSFER_REVOKE");
        grants.saveAndFlush(grant);
    }

    private record Actor(User user, String token) {}
}
