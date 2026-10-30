package com.indore.pathome.spaces.support;

import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migration-only harness reserved for the separately approved Gate I JVM.
 *
 * <p>It verifies source bytes before opening JDBC, executes the unchanged V1 legacy resource,
 * records baseline version 1 explicitly, and caps Flyway at V60. It offers no clean, repair,
 * unbounded-latest, or baseline-on-unknown-schema behavior.</p>
 */
public final class FreshIntegrationSchemaHarness {
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final int EXPECTED_V57_MIGRATION_COUNT = 56;
    private static final int EXPECTED_V58_MIGRATION_COUNT = 57;
    private static final int EXPECTED_V59_MIGRATION_COUNT = 58;
    private static final int EXPECTED_V60_MIGRATION_COUNT = 59;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record MigrationReceipt(
            String runUuid,
            String manifestSha256,
            String cohort,
            String database,
            String migratorRole,
            String stage,
            int ceiling,
            String custodianReference,
            FreshIntegrationTarget.SourcePins sourcePins,
            Instant completedAt) {
        public MigrationReceipt {
            if (!"I".equals(stage) || ceiling != 60) {
                throw new IllegalArgumentException("migration receipt must bind stage I at V60");
            }
        }
    }

    private FreshIntegrationSchemaHarness() {}

    public static MigrationReceipt prepareClean(FreshIntegrationTarget.Target target)
            throws SQLException, IOException {
        FreshIntegrationTarget.Target migrationTarget = requireMigrationTarget(
                target, FreshIntegrationTarget.Cohort.CLEAN);
        verifySourcePins(migrationTarget.manifest().sourcePins());
        try (Connection connection = DriverManager.getConnection(
                migrationTarget.jdbcUrl(), migrationTarget.role(), migrationTarget.password())) {
            establishMigrationSearchPath(connection);
            prepareLegacyBaseline(connection);
            Flyway flyway = flyway(migrationTarget, 60);
            flyway.migrate();
            flyway.validate();
        }
        return receipt(migrationTarget);
    }

    public static MigrationReceipt prepareUpgradeTo54(FreshIntegrationTarget.Target target)
            throws SQLException, IOException {
        FreshIntegrationTarget.Target migrationTarget = requireMigrationTarget(
                target, FreshIntegrationTarget.Cohort.UPGRADE);
        verifySourcePins(migrationTarget.manifest().sourcePins());
        try (Connection connection = DriverManager.getConnection(
                migrationTarget.jdbcUrl(), migrationTarget.role(), migrationTarget.password())) {
            establishMigrationSearchPath(connection);
            prepareLegacyBaseline(connection);
            flyway(migrationTarget, 54).migrate();
        }
        return receipt(migrationTarget);
    }

    public static MigrationReceipt applyV55To60(FreshIntegrationTarget.Target target)
            throws IOException {
        FreshIntegrationTarget.Target migrationTarget = requireMigrationTarget(
                target, FreshIntegrationTarget.Cohort.UPGRADE);
        verifySourcePins(migrationTarget.manifest().sourcePins());
        Flyway flyway = flyway(migrationTarget, 60);
        flyway.migrate();
        flyway.validate();
        return receipt(migrationTarget);
    }

    public static MigrationReceipt prepareBusiness(FreshIntegrationTarget.Target target)
            throws SQLException, IOException {
        FreshIntegrationTarget.Target migrationTarget = requireMigrationTarget(
                target, FreshIntegrationTarget.Cohort.BUSINESS);
        verifySourcePins(migrationTarget.manifest().sourcePins());
        try (Connection connection = DriverManager.getConnection(
                migrationTarget.jdbcUrl(), migrationTarget.role(), migrationTarget.password())) {
            establishMigrationSearchPath(connection);
            prepareLegacyBaseline(connection);
            Flyway flyway = flyway(migrationTarget, 60);
            flyway.migrate();
            flyway.validate();
        }
        MigrationReceipt receipt = receipt(migrationTarget);
        writeCompletionReceipt(receipt);
        return receipt;
    }

    public static void verifySourcePins(FreshIntegrationTarget.SourcePins expected)
            throws IOException {
        expected.requireApprovedPins();
        requireDigest("db/baseline/pathome-v1-legacy-core.sql", FreshIntegrationTarget.LEGACY_CORE_SHA256);
        requireDigest("db/migration/V55__backoffice_property_publication_history.sql",
                FreshIntegrationTarget.V55_SHA256);
        requireDigest("db/migration/V56__property_removal_approval.sql",
                FreshIntegrationTarget.V56_SHA256);
        requireDigest("db/migration/V57__backoffice_activity_keyset_index.sql",
                FreshIntegrationTarget.V57_SHA256);
        requireDigest("db/migration/V58__property_staff_grant_capabilities.sql",
                FreshIntegrationTarget.V58_SHA256);
        requireDigest("db/migration/V59__staff_direct_draft_media_ownership.sql",
                FreshIntegrationTarget.V59_SHA256);
        requireDigest("db/migration/V60__staff_direct_draft_owner_null_safe_check.sql",
                FreshIntegrationTarget.V60_SHA256);
        String historicalManifest = migrationManifestDigest(57, EXPECTED_V57_MIGRATION_COUNT);
        if (!FreshIntegrationTarget.MIGRATIONS_SHA256.equals(historicalManifest)) {
            throw new IllegalStateException(
                    "REFUSED historical V2-V57 manifest differs from the approved baseline");
        }
        String currentManifest = migrationManifestDigest(60, EXPECTED_V60_MIGRATION_COUNT);
        if (!FreshIntegrationTarget.V60_MIGRATIONS_SHA256.equals(currentManifest)) {
            throw new IllegalStateException(
                    "REFUSED fresh migration source manifest differs from the approved pin");
        }
    }

    private static String migrationManifestDigest(int ceiling, int expectedCount)
            throws IOException {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*.sql");
        } catch (IOException exception) {
            throw new IllegalStateException("REFUSED migration sources could not be enumerated");
        }
        List<Resource> ordered = java.util.Arrays.stream(resources)
                .sorted(Comparator.comparingInt(FreshIntegrationSchemaHarness::version))
                .filter(resource -> version(resource) <= ceiling)
                .toList();
        if (ordered.size() != expectedCount) {
            throw new IllegalStateException(
                    "REFUSED migration source count differs from V2-V" + ceiling);
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < ordered.size(); index++) {
            Resource resource = ordered.get(index);
            int expectedVersion = index + 2;
            if (version(resource) != expectedVersion) {
                throw new IllegalStateException("REFUSED migration versions are not contiguous at V"
                        + expectedVersion);
            }
            String filename = resource.getFilename();
            canonical.append(sha256(resource.getInputStream())).append("  ")
                    .append(filename).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int version(Resource resource) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(
                resource.getFilename() == null ? "" : resource.getFilename());
        if (!matcher.matches()) {
            throw new IllegalStateException("REFUSED unexpected migration filename");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Flyway flyway(FreshIntegrationTarget.Target target, int ceiling) {
        return Flyway.configure()
                .dataSource(target.jdbcUrl(), target.role(), target.password())
                .schemas("public")
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .initSql("SET search_path TO public, it_extensions, pg_catalog")
                .target(String.valueOf(ceiling))
                .load();
    }

    private static void prepareLegacyBaseline(Connection connection)
            throws SQLException, IOException {
        if (!hasFlywayHistory(connection)) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            return;
        }
        try (var statement = connection.prepareStatement(
                "select count(*) from public.flyway_schema_history "
                        + "where version = '1' and success")) {
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 1) {
                    throw new IllegalStateException(
                            "REFUSED existing Flyway history lacks the supported baseline 1");
                }
            }
        }
    }

    private static boolean hasFlywayHistory(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select to_regclass('public.flyway_schema_history') is not null")) {
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void establishMigrationSearchPath(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to public, it_extensions, pg_catalog");
        }
    }

    private static FreshIntegrationTarget.Target requireMigrationTarget(
            FreshIntegrationTarget.Target target, FreshIntegrationTarget.Cohort cohort) {
        if (target == null || target.cohort() != cohort
                || !FreshIntegrationTarget.MIGRATOR_ROLE.equals(target.role())
                || !target.manifest().permittedStages().contains("I")) {
            throw new IllegalStateException(
                    "REFUSED only the manifest-bound stage-I migrator may run the migration harness");
        }
        return target;
    }

    private static MigrationReceipt receipt(FreshIntegrationTarget.Target target) {
        return new MigrationReceipt(target.manifest().runUuid(),
                target.manifest().approvalManifestSha256(), target.cohort().key(),
                target.database(), target.role(), "I", 60,
                target.manifest().custodianReference(),
                target.manifest().sourcePins(), Instant.now());
    }

    private static void writeCompletionReceipt(MigrationReceipt receipt) throws IOException {
        Path evidence = Path.of(FreshIntegrationTarget.RUN_ROOT, "evidence");
        if (Files.isSymbolicLink(evidence) || !Files.isDirectory(evidence)) {
            throw new IllegalStateException(
                    "REFUSED migration receipt evidence directory is unavailable");
        }
        Path target = evidence.resolve("migrator-v60-completion.json");
        if (Files.exists(target) || Files.isSymbolicLink(target)) {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) {
                throw new IllegalStateException(
                        "REFUSED migration completion receipt was replaced");
            }
            var existing = JSON.readTree(Files.readString(target, StandardCharsets.UTF_8));
            if (existing.path("targetVersion").asInt() != 60
                    || existing.path("migrationCount").asInt() != 59
                    || !FreshIntegrationTarget.V60_MIGRATIONS_SHA256.equals(
                            existing.path("currentMigrationsSha256").asText())
                    || !FreshIntegrationTarget.V58_SHA256.equals(
                            existing.path("v58Sha256").asText())
                    || !FreshIntegrationTarget.V59_SHA256.equals(
                            existing.path("v59Sha256").asText())
                    || !FreshIntegrationTarget.V60_SHA256.equals(
                            existing.path("v60Sha256").asText())) {
                throw new IllegalStateException(
                        "REFUSED existing V60 completion receipt does not match current evidence");
            }
            return;
        }
        String payload = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                java.util.Map.ofEntries(
                        java.util.Map.entry("schemaVersion", 1),
                        java.util.Map.entry("stage", receipt.stage()),
                        java.util.Map.entry("runUuid", receipt.runUuid()),
                        java.util.Map.entry("manifestSha256", receipt.manifestSha256()),
                        java.util.Map.entry("cohort", receipt.cohort()),
                        java.util.Map.entry("database", receipt.database()),
                        java.util.Map.entry("migratorRole", receipt.migratorRole()),
                        java.util.Map.entry("applicationSchema", "public"),
                        java.util.Map.entry("baselineVersion", 1),
                        java.util.Map.entry("targetVersion", 60),
                        java.util.Map.entry("migrationCount", 59),
                        java.util.Map.entry("historyValidated", true),
                        java.util.Map.entry("custodianReference", receipt.custodianReference()),
                        java.util.Map.entry("sourcePins", receipt.sourcePins()),
                        java.util.Map.entry("currentMigrationsSha256",
                                FreshIntegrationTarget.V60_MIGRATIONS_SHA256),
                        java.util.Map.entry("v58Sha256",
                                FreshIntegrationTarget.V58_SHA256),
                        java.util.Map.entry("v59Sha256",
                                FreshIntegrationTarget.V59_SHA256),
                        java.util.Map.entry("v60Sha256",
                                FreshIntegrationTarget.V60_SHA256),
                        java.util.Map.entry("completedAt", receipt.completedAt().toString()))) + "\n";
        Files.createFile(target,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void requireDigest(String resourcePath, String expected) throws IOException {
        Resource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("REFUSED pinned migration resource is absent");
        }
        String actual;
        try (InputStream stream = resource.getInputStream()) {
            actual = sha256(stream);
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "REFUSED pinned migration resource differs from approved bytes");
        }
    }

    private static String sha256(InputStream input) {
        try (input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("REFUSED source digest could not be calculated");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("REFUSED source digest could not be calculated");
        }
    }
}
