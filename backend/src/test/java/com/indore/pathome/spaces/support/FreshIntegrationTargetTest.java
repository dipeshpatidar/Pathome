package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FreshIntegrationTargetTest {
    @Test
    void acceptsOnlyTheLiteralFreshManifest() {
        FreshIntegrationTarget.Target target = target(
                validManifest("I"), FreshIntegrationTarget.Stage.INTEGRATION);

        assertEquals(FreshIntegrationTarget.RUN_UUID, target.manifest().runUuid());
        assertEquals(FreshIntegrationTarget.BUSINESS_DATABASE, target.database());
        assertEquals(FreshIntegrationTarget.RUNTIME_ROLE, target.role());
        assertEquals("jdbc:postgresql://127.0.0.1:55432/"
                + FreshIntegrationTarget.BUSINESS_DATABASE, target.jdbcUrl());
        assertTrue(target.toString().contains("secret=<redacted>"));
    }

    @Test
    void recoveryRecordLinksImmutableOriginalContainerToActiveReplacement() {
        String original = validManifest("A");
        FreshIntegrationTarget.Manifest recovered = FreshIntegrationTarget.applyRecovery(
                original, validRecovery(original));

        assertEquals(FreshIntegrationTarget.ORIGINAL_CONTAINER_ID,
                originalContainerId(original));
        assertEquals("c".repeat(64), recovered.containerId());
        assertEquals("172.18.0.3", recovered.containerServerAddress());
        assertEquals(FreshIntegrationTarget.RUN_UUID, recovered.runUuid());
    }

    @Test
    void recoveryRecordRejectsStaleOrContradictoryProvenance() {
        String original = validManifest("A");
        String recovery = validRecovery(original);

        assertRecoveryRefused(original, recovery.replace(
                "\"originalContainerId\": \"" + FreshIntegrationTarget.ORIGINAL_CONTAINER_ID + "\"",
                "\"originalContainerId\": \"" + "b".repeat(64) + "\""));
        assertRecoveryRefused(original, recovery.replace(
                "\"activeContainerId\": \"" + "c".repeat(64) + "\"",
                "\"activeContainerId\": \"" + FreshIntegrationTarget.ORIGINAL_CONTAINER_ID + "\""));
        assertRecoveryRefused(original, recovery.replace(
                "\"pgSystemIdentifier\": \"7654321098765432100\"",
                "\"pgSystemIdentifier\": \"7654321098765432101\""));
        assertRecoveryRefused(original, recovery.replace(
                "\"schemaVersion\": 1", "\"schemaVersion\": 1,\"unexpected\":true"));
        assertRecoveryRefused(original, recovery.replace(
                "\"beforeSha256\": \"" + "d".repeat(64) + "\"",
                "\"beforeSha256\": \"" + "e".repeat(64) + "\""));
    }

    @Test
    void rejectsDuplicateUnknownAndMissingFieldsBeforeConnection() {
        String duplicate = validManifest("I").replaceFirst(
                "\"runName\"", "\"runName\":\"backoffice-it-20261025-01\",\"runName\"");
        assertRefused(duplicate);
        assertRefused(validManifest("I").replace(
                "\"sourcePins\"", "\"unexpected\":true,\"sourcePins\""));
        assertRefused(validManifest("I").replace(
                "\"containerServerPort\": 5432,", ""));
    }

    @Test
    void rejectsWrongStageRoleDatabasePortAndProtectedAliases() {
        assertRefusedFor(validManifest("I"), FreshIntegrationTarget.Stage.PREPARATION);
        assertRefused(validManifest("I").replace(
                FreshIntegrationTarget.BUSINESS_DATABASE, "pathome_db"));
        assertRefused(validManifest("I").replace("\"port\": 55432", "\"port\": 5432"));
        assertRefused(validManifest("I").replace(
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID, "a".repeat(64)));
        assertRefused(validManifest("I").replace(
                FreshIntegrationTarget.APPROVED_IMAGE_DIGEST,
                "sha256:" + "b".repeat(64)));
        assertRefused(validManifest("I").replace(
                FreshIntegrationTarget.RUNTIME_ROLE, FreshIntegrationTarget.MIGRATOR_ROLE));
        assertRefused(validManifest("I").replace(
                "\"permittedStages\": [\"A\", \"I\"]",
                "\"permittedStages\": [\"A\", \"A\"]"));
    }

    @Test
    void inheritedDatasourceAndHistoricalFlagsAreRefusedBeforeManifestAccess() {
        FreshIntegrationTarget.RefusedException failure = assertThrows(
                FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.load(
                        java.nio.file.Path.of("/does/not/exist"),
                        FreshIntegrationTarget.Stage.INTEGRATION,
                        FreshIntegrationTarget.Cohort.BUSINESS,
                        Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost/pathome_db")));
        assertTrue(failure.getMessage().contains("SPRING_DATASOURCE_URL"));

        FreshIntegrationTarget.RefusedException historical = assertThrows(
                FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.load(
                        java.nio.file.Path.of("/does/not/exist"),
                        FreshIntegrationTarget.Stage.INTEGRATION,
                        FreshIntegrationTarget.Cohort.BUSINESS,
                        Map.of("PATHOME_PACKAGE5_FLYWAY_TEST", "true")));
        assertTrue(historical.getMessage().contains("PATHOME_PACKAGE5_FLYWAY_TEST"));
    }

    @Test
    void connectionIdentityMismatchIsRejectedWithExactFacts() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION);
        Connection connection = connectionWithFacts("pathome_db");

        SQLException failure = assertThrows(SQLException.class,
                () -> FreshIntegrationTarget.verifyConnection(connection, target));
        assertTrue(failure.getMessage().contains("database"));
    }

    @Test
    void exactSentinelAndRoleFactsAreAccepted() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION);
        assertEquals(FreshIntegrationTarget.METADATA_ROLE, target.role());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> FreshIntegrationTarget.verifyConnection(
                        connectionWithFacts(FreshIntegrationTarget.BUSINESS_DATABASE), target));
    }

    @Test
    void activeReplacementStillRequiresImmutableOriginalContainerInSentinel() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION);
        Connection connection = connectionFacts(FreshIntegrationTarget.BUSINESS_DATABASE,
                FreshIntegrationTarget.METADATA_ROLE, 1, 3, 0, "f".repeat(64));

        SQLException failure = assertThrows(SQLException.class,
                () -> FreshIntegrationTarget.verifyConnection(connection, target));
        assertTrue(failure.getMessage().contains("immutable original container"));
    }

    @Test
    void duplicateSentinelOrCleanupRowFailsClosed() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION);
        Connection duplicate = connection(FreshIntegrationTarget.BUSINESS_DATABASE,
                FreshIntegrationTarget.METADATA_ROLE, 2, 3, 0);
        assertThrows(SQLException.class,
                () -> FreshIntegrationTarget.verifyConnection(duplicate, target));

        Connection cleanup = connection(FreshIntegrationTarget.BUSINESS_DATABASE,
                FreshIntegrationTarget.METADATA_ROLE, 1, 3, 1);
        assertThrows(SQLException.class,
                () -> FreshIntegrationTarget.verifyConnection(cleanup, target));
    }

    @Test
    void connectionRejectsActualServerSystemIdentityMismatch() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION);
        Connection connection = connectionWithFacts(
                FreshIntegrationTarget.BUSINESS_DATABASE);
        ResultSet rows = rows(connection);
        when(rows.getString("server_system_identifier"))
                .thenReturn("7000000000000000000");

        SQLException failure = assertThrows(SQLException.class,
                () -> FreshIntegrationTarget.verifyConnection(connection, target));
        assertTrue(failure.getMessage().contains("server system identifier"));
    }

    @Test
    void runtimeGuardDoesNotReadOrGrantCleanupPlanAccess() throws Exception {
        FreshIntegrationTarget.Target target = target(
                validManifest("I"), FreshIntegrationTarget.Stage.INTEGRATION);
        Connection connection = connectionFacts(
                FreshIntegrationTarget.BUSINESS_DATABASE,
                FreshIntegrationTarget.RUNTIME_ROLE, 1, 3, 0,
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID);
        ResultSet rows = rows(connection);
        when(rows.getBoolean("cleanup_select")).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> FreshIntegrationTarget.verifyConnection(connection, target));
        verify(connection, org.mockito.Mockito.atLeastOnce()).createStatement();
        verify(rows, org.mockito.Mockito.atLeastOnce()).getString("database");
    }

    @Test
    void liveAttestationMustBindNetworkAndPostgresSystemIdentity() {
        FreshIntegrationTarget.Manifest manifest = target(
                validManifest("A"), FreshIntegrationTarget.Stage.ATTESTATION).manifest();
        String networkId = "e".repeat(64);
        long ownerUid = ownerUid();
        String live = validLiveAttestation(manifest, networkId, ownerUid);
        Instant now = Instant.parse("2026-10-25T17:31:00Z");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> FreshIntegrationTarget.validateLiveAttestation(
                        live, manifest, networkId, "1".repeat(64), "2".repeat(64),
                        "3".repeat(64), now, ownerUid));

        for (String[] mutation : new String[][]{
                {"\"activeContainerId\": \"" + manifest.containerId() + "\"",
                        "\"activeContainerId\": \"" + "f".repeat(64) + "\""},
                {"\"networkId\": \"" + networkId + "\"",
                        "\"networkId\": \"" + "f".repeat(64) + "\""},
                {"\"pgSystemIdentifier\": \"7654321098765432100\"",
                        "\"pgSystemIdentifier\": \"7000000000000000000\""}}) {
            assertThrows(FreshIntegrationTarget.RefusedException.class,
                    () -> FreshIntegrationTarget.validateLiveAttestation(
                            live.replace(mutation[0], mutation[1]),
                            manifest, networkId, "1".repeat(64), "2".repeat(64),
                            "3".repeat(64), now, ownerUid));
        }
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.validateLiveAttestation(
                        live, manifest, networkId, "1".repeat(64), "2".repeat(64),
                        "3".repeat(64), Instant.parse("2026-10-25T17:50:00Z"),
                        ownerUid));
    }

    @Test
    void migrationReceiptMustBindTheBusinessRuntimeAndExactSourcePins() {
        FreshIntegrationTarget.Target target = target(
                validManifest("I"), FreshIntegrationTarget.Stage.INTEGRATION);
        FreshIntegrationTarget.MigrationReceipt valid = receipt(target, 60, target.database());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> FreshIntegrationTarget.validateReceipt(valid, target));

        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.validateReceipt(
                        receipt(target, 49, target.database()), target));
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.validateReceipt(
                        receipt(target, 60, FreshIntegrationTarget.CLEAN_DATABASE), target));
    }

    @Test
    void unchangedLegacyCoreAndV2ToV60SourcePinsMatchTheApproval() {
        FreshIntegrationTarget.SourcePins pins = new FreshIntegrationTarget.SourcePins(
                FreshIntegrationTarget.LEGACY_CORE_SHA256,
                FreshIntegrationTarget.MIGRATIONS_SHA256,
                FreshIntegrationTarget.V55_SHA256,
                FreshIntegrationTarget.V56_SHA256,
                FreshIntegrationTarget.V57_SHA256);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> FreshIntegrationSchemaHarness.verifySourcePins(pins));
    }

    @Test
    void runtimeRegistryRemovesSchedulingBeforeOrdinaryBeansAndFailsIfEnabled() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition(
                FreshIntegrationRuntimeConfiguration.SCHEDULED_PROCESSOR,
                new RootBeanDefinition(Object.class));
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.task.scheduling.enabled", "false"))
                .thenReturn("false");

        FreshIntegrationRuntimeConfiguration.disableScheduledWorkers(environment)
                .postProcessBeanFactory(factory);
        org.junit.jupiter.api.Assertions.assertFalse(
                factory.containsBeanDefinition(
                        FreshIntegrationRuntimeConfiguration.SCHEDULED_PROCESSOR));

        when(environment.getProperty("spring.task.scheduling.enabled", "false"))
                .thenReturn("true");
        assertThrows(IllegalStateException.class,
                () -> FreshIntegrationRuntimeConfiguration.disableScheduledWorkers(environment)
                        .postProcessBeanFactory(factory));
        verify(environment, org.mockito.Mockito.atLeast(2))
                .getProperty("spring.task.scheduling.enabled", "false");
    }

    @Test
    void realGateARoleGuardsAcceptOnlyTheirApprovedReads() throws Exception {
        assumeTrue("true".equals(System.getenv("PATHOME_FRESH_GUARD_LIVE")));
        Path manifest = Path.of(FreshIntegrationTarget.RUN_ROOT, "custody.json");
        for (FreshIntegrationTarget.Cohort cohort : FreshIntegrationTarget.Cohort.values()) {
            assertLiveGuard(manifest, FreshIntegrationTarget.Stage.ATTESTATION,
                    cohort, FreshIntegrationTarget.METADATA_ROLE);
            assertLiveGuard(manifest, FreshIntegrationTarget.Stage.ATTESTATION,
                    cohort, FreshIntegrationTarget.MIGRATOR_ROLE);
        }
        assertLiveGuard(manifest, FreshIntegrationTarget.Stage.ATTESTATION,
                FreshIntegrationTarget.Cohort.BUSINESS,
                FreshIntegrationTarget.RUNTIME_ROLE);
    }

    private static void assertRefused(String json) {
        assertRefusedFor(json, FreshIntegrationTarget.Stage.INTEGRATION);
    }

    private static void assertRefusedFor(String json, FreshIntegrationTarget.Stage stage) {
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.parse(
                        json, stage, FreshIntegrationTarget.Cohort.BUSINESS));
    }

    private static FreshIntegrationTarget.Target target(
            String json, FreshIntegrationTarget.Stage stage) {
        FreshIntegrationTarget.Selection selection = FreshIntegrationTarget.parse(
                json, stage, FreshIntegrationTarget.Cohort.BUSINESS);
        return new FreshIntegrationTarget.Target(
                selection.manifest(), selection.cohort(), selection.database(),
                selection.role(), selection.secretFile(), "", null);
    }

    private static Connection connectionWithFacts(String database) throws SQLException {
        return connection(database, FreshIntegrationTarget.METADATA_ROLE, 1, 3, 0);
    }

    private static ResultSet rows(Connection connection) throws SQLException {
        return connection.createStatement().executeQuery("test");
    }

    private static FreshIntegrationTarget.MigrationReceipt receipt(
            FreshIntegrationTarget.Target target, int targetVersion, String database) {
        return new FreshIntegrationTarget.MigrationReceipt(
                1, "I", FreshIntegrationTarget.RUN_UUID,
                target.manifest().approvalManifestSha256(), "business", database,
                FreshIntegrationTarget.MIGRATOR_ROLE, "public", 1, targetVersion,
                targetVersion == 60 ? 59 : 48, targetVersion == 60,
                target.manifest().custodianReference(),
                target.manifest().sourcePins(),
                FreshIntegrationTarget.V60_MIGRATIONS_SHA256,
                FreshIntegrationTarget.V58_SHA256,
                FreshIntegrationTarget.V59_SHA256,
                FreshIntegrationTarget.V60_SHA256,
                "2026-10-25T18:00:00Z");
    }

    private static Connection connection(String database, String role, int sentinels,
                                         int bindings, int cleanupRows) throws SQLException {
        return connectionFacts(database, role, sentinels, bindings, cleanupRows,
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID);
    }

    private static Connection connectionFacts(String database, String role, int sentinels,
                                               int bindings, int cleanupRows,
                                               String sentinelContainerId) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        ResultSet timeoutRows = mock(ResultSet.class);
        when(statement.executeQuery(contains("statement_timeout"))).thenReturn(timeoutRows);
        when(timeoutRows.next()).thenReturn(true);
        when(timeoutRows.getString(1)).thenReturn("15s");
        when(timeoutRows.getString(2)).thenReturn("5s");
        when(rows.next()).thenReturn(true);
        when(rows.getString("database")).thenReturn(database);
        when(rows.getString("session_user")).thenReturn(role);
        when(rows.getString("current_user")).thenReturn(role);
        boolean metadata = FreshIntegrationTarget.METADATA_ROLE.equals(role);
        boolean migrator = FreshIntegrationTarget.MIGRATOR_ROLE.equals(role);
        boolean runtime = FreshIntegrationTarget.RUNTIME_ROLE.equals(role);
        when(rows.getString("current_schema"))
                .thenReturn(metadata ? "it_extensions" : "public");
        when(rows.getString("search_path"))
                .thenReturn("public, it_extensions, pg_catalog");
        when(rows.getString("server_address")).thenReturn("172.18.0.2");
        when(rows.getInt("server_port")).thenReturn(5432);
        when(rows.getString("server_system_identifier"))
                .thenReturn("7654321098765432100");
        when(rows.getString("database_owner"))
                .thenReturn(FreshIntegrationTarget.DATABASE_OWNER_ROLE);
        when(rows.getString("public_schema_owner"))
                .thenReturn(FreshIntegrationTarget.MIGRATOR_ROLE);
        when(rows.getInt("run_identity_count")).thenReturn(sentinels);
        when(rows.getInt("binding_count")).thenReturn(bindings);
        when(rows.getInt("cleanup_plan_count")).thenReturn(cleanupRows);
        when(rows.getInt("extension_count")).thenReturn(2);
        when(rows.getBoolean("current_super")).thenReturn(false);
        when(rows.getBoolean("current_createdb")).thenReturn(false);
        when(rows.getBoolean("current_createrole")).thenReturn(false);
        when(rows.getBoolean("current_inherit")).thenReturn(false);
        when(rows.getBoolean("current_login")).thenReturn(true);
        when(rows.getBoolean("cleanup_login")).thenReturn(false);
        when(rows.getBoolean("cleanup_super")).thenReturn(false);
        when(rows.getInt("membership_count")).thenReturn(0);
        when(rows.getBoolean("database_temp")).thenReturn(migrator);
        when(rows.getBoolean("database_create")).thenReturn(false);
        when(rows.getBoolean("database_create_grant")).thenReturn(false);
        when(rows.getBoolean("public_schema_usage")).thenReturn(!metadata);
        when(rows.getBoolean("public_schema_create")).thenReturn(migrator);
        when(rows.getBoolean("control_schema_usage")).thenReturn(true);
        when(rows.getBoolean("control_schema_create")).thenReturn(false);
        when(rows.getBoolean("control_schema_create_grant")).thenReturn(false);
        when(rows.getBoolean("extensions_schema_usage")).thenReturn(true);
        when(rows.getBoolean("extensions_schema_create")).thenReturn(false);
        when(rows.getBoolean("run_select")).thenReturn(true);
        when(rows.getBoolean("binding_select")).thenReturn(true);
        when(rows.getBoolean("cleanup_select")).thenReturn(!runtime);
        when(rows.getBoolean("migrator_cleanup_grant")).thenReturn(false);
        when(rows.getBoolean("cleanup_function_execute")).thenReturn(false);
        when(rows.getBoolean("clean_connect")).thenReturn(!runtime);
        when(rows.getBoolean("upgrade_connect")).thenReturn(!runtime);
        when(rows.getBoolean("business_connect")).thenReturn(true);
        when(rows.getBoolean("cleanup_connect")).thenReturn(false);
        when(rows.getString("run_uuid")).thenReturn(FreshIntegrationTarget.RUN_UUID);
        when(rows.getString("disposable_marker")).thenReturn(FreshIntegrationTarget.MARKER);
        when(rows.getString("cohort_name")).thenReturn(database);
        when(rows.getString("manifest_sha256"))
                .thenReturn(FreshIntegrationTarget.APPROVAL_MANIFEST_SHA256);
        when(rows.getString("provisioning_state")).thenReturn("PREPARED");
        when(rows.getString("sentinel_container_id")).thenReturn(sentinelContainerId);
        return connection;
    }

    private static String originalContainerId(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(json).path("containerId").asText();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertRecoveryRefused(String original, String recovery) {
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationTarget.applyRecovery(original, recovery));
    }

    private static void assertLiveGuard(
            Path manifest,
            FreshIntegrationTarget.Stage stage,
            FreshIntegrationTarget.Cohort cohort,
            String role) throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.load(
                manifest, stage, cohort, role, Map.of());
        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password())) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            target.policy().prepare(connection);
            FreshIntegrationTarget.verifyConnection(connection, target);
            connection.rollback();
        }
    }

    private static String validRecovery(String originalCustody) {
        return """
                {
                  "schemaVersion": 1,
                  "provisioningState": "RECOVERED",
                  "recoveryReason": "SECRET_DIRECTORY_MOUNT_REMEDIATION",
                  "authorityReference": "OWNER_CONTROLLED_SECURITY_RECOVERY_20261025",
                  "runUuid": "9d38d6f0-791d-4cbb-9442-fde2d5f0b101",
                  "originalContainerId": "%s",
                  "activeContainerId": "%s",
                  "originalCustodySha256": "%s",
                  "container": "pathome-it-backoffice-20261025-01",
                  "imageDigest": "%s",
                  "network": "pathome-it-backoffice-20261025-01-net",
                  "networkId": "%s",
                  "volume": "pathome-it-backoffice-20261025-01-pgdata",
                  "volumeId": "volume-created-20261025",
                  "pgSystemIdentifier": "7654321098765432100",
                  "containerServerAddress": "172.18.0.3",
                  "replacedAt": "2026-10-25T17:30:00Z",
                  "mounts": [
                    {
                      "type": "volume",
                      "name": "pathome-it-backoffice-20261025-01-pgdata",
                      "source": null,
                      "destination": "/var/lib/postgresql/data",
                      "readOnly": false
                    },
                    {
                      "type": "bind",
                      "name": null,
                      "source": "/private/tmp/pathome-it-backoffice-20261025-01/secrets/itp_20261025_01_provisioner.password",
                      "destination": "/run/pathome-provisioner.password",
                      "readOnly": true
                    },
                    {
                      "type": "bind",
                      "name": null,
                      "source": "/private/tmp/pathome-it-backoffice-20261025-01/secrets/roles.pgpass",
                      "destination": "/run/pathome-roles.pgpass",
                      "readOnly": true
                    }
                  ],
                  "cohortSnapshots": {
                    "clean": {"beforeSha256": "%s", "afterSha256": "%s"},
                    "upgrade": {"beforeSha256": "%s", "afterSha256": "%s"},
                    "business": {"beforeSha256": "%s", "afterSha256": "%s"}
                  },
                  "preRecoveryEvidenceSha256": "%s",
                  "postRecoveryEvidenceSha256": "%s"
                }
                """.formatted(
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID,
                "c".repeat(64), sha256(originalCustody),
                FreshIntegrationTarget.APPROVED_IMAGE_DIGEST, "e".repeat(64),
                "d".repeat(64), "d".repeat(64), "d".repeat(64), "d".repeat(64),
                "d".repeat(64), "d".repeat(64), "d".repeat(64), "e".repeat(64));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String validLiveAttestation(
            FreshIntegrationTarget.Manifest manifest, String networkId, long ownerUid) {
        return """
                {
                  "schemaVersion": 1,
                  "status": "LIVE_ATTESTED",
                  "runUuid": "%s",
                  "custodySha256": "%s",
                  "recoveryCustodySha256": "%s",
                  "recoveryEvidenceSha256": "%s",
                  "originalContainerId": "%s",
                  "activeContainerId": "%s",
                  "networkId": "%s",
                  "containerServerAddress": "%s",
                  "pgSystemIdentifier": "%s",
                  "tunnel": {
                    "mode": "SSH_LOCAL_FORWARD",
                    "listener": "127.0.0.1:55432",
                    "pid": 4242,
                    "uid": %d,
                    "target": "127.0.0.1:55432",
                    "containerAddress": "%s",
                    "containerId": "%s",
                    "networkId": "%s",
                    "dockerPublishedPorts": {"5432/tcp": null},
                    "ephemeral": true
                  },
                  "attestedAt": "2026-10-25T17:30:00Z"
                }
                """.formatted(
                FreshIntegrationTarget.RUN_UUID,
                "1".repeat(64), "2".repeat(64), "3".repeat(64),
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID,
                manifest.containerId(), networkId, manifest.containerServerAddress(),
                manifest.pgSystemIdentifier(), ownerUid, manifest.containerServerAddress(),
                manifest.containerId(), networkId);
    }

    private static long ownerUid() {
        try {
            Object owner = Files.getAttribute(
                    Path.of(System.getProperty("java.io.tmpdir")), "unix:uid",
                    LinkOption.NOFOLLOW_LINKS);
            return ((Number) owner).longValue();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String validManifest(String stage) {
        return """
                {
                  "schemaVersion": 1,
                  "provisioningState": "PREPARED",
                  "permittedStages": ["A", "I"],
                  "runName": "backoffice-it-20261025-01",
                  "runUuid": "9d38d6f0-791d-4cbb-9442-fde2d5f0b101",
                  "container": "pathome-it-backoffice-20261025-01",
                  "network": "pathome-it-backoffice-20261025-01-net",
                  "volume": "pathome-it-backoffice-20261025-01-pgdata",
                  "host": "127.0.0.1",
                  "port": 55432,
                  "containerServerAddress": "172.18.0.2",
                  "containerServerPort": 5432,
                  "containerId": "%s",
                  "imageDigest": "%s",
                  "volumeId": "volume-created-20261025",
                  "serverVersion": "16.10",
                  "pgSystemIdentifier": "7654321098765432100",
                  "approvalManifestSha256": "%s",
                  "custodianReference": "owner-gate-p-20261025",
                  "roles": {
                    "provisioner": "itp_20261025_01_provisioner",
                    "databaseOwner": "itp_20261025_01_db_owner",
                    "metadata": "itp_20261025_01_metadata",
                    "migrator": "itp_20261025_01_migrator",
                    "runtime": "itp_20261025_01_runtime",
                    "cleanup": "itp_20261025_01_cleanup"
                  },
                  "cohorts": {
                    "clean": "pathome_it_20261025_01_clean",
                    "upgrade": "pathome_it_20261025_01_upgrade",
                    "business": "pathome_it_20261025_01_business"
                  },
                  "sourcePins": {
                    "legacyCore": "%s",
                    "migrations": "%s",
                    "v55": "%s",
                    "v56": "%s",
                    "v57": "%s"
                  }
                }
                """.formatted(
                FreshIntegrationTarget.ORIGINAL_CONTAINER_ID,
                FreshIntegrationTarget.APPROVED_IMAGE_DIGEST,
                FreshIntegrationTarget.APPROVAL_MANIFEST_SHA256,
                FreshIntegrationTarget.LEGACY_CORE_SHA256,
                FreshIntegrationTarget.MIGRATIONS_SHA256,
                FreshIntegrationTarget.V55_SHA256,
                FreshIntegrationTarget.V56_SHA256,
                FreshIntegrationTarget.V57_SHA256);
    }
}
