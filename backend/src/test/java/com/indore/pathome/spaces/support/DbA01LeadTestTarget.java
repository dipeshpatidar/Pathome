package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed binding for the fresh, owner-approved OE D-B Lead database.
 *
 * <p>The target is deliberately literal. It has no fallback to a historical cohort,
 * pathome_db, PostgreSQL's default port, or any parent integration environment.</p>
 */
public final class DbA01LeadTestTarget {
    public static final String TEST_FLAG = "PATHOME_DB_A01_LEAD_TEST";
    public static final String MANIFEST_FLAG = "PATHOME_DB_A01_LEAD_MANIFEST";
    public static final String RUN_NAME = "oe-dbf-20261030-cbb093e7";
    public static final String RUN_UUID = "cbb093e7-ff92-49e0-8c91-add63f8a9db6";
    public static final String RUN_ROOT =
            "/Users/dipeshpatidar/Library/Application Support/Pathome/"
                    + "OE-DBF-20261030-CBB093E7";
    public static final String MANIFEST_PATH = RUN_ROOT + "/custody.json";
    public static final String DATABASE = "pathome_it_20261030_oe_dbf_cbb093e7";
    public static final String RUNTIME_ROLE = "oe_dbf_runtime_cbb093e7";
    public static final String MIGRATOR_ROLE = "oe_dbf_migrator_cbb093e7";
    public static final String DATABASE_OWNER = "oe_dbf_db_owner_cbb093e7";
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 63509;
    public static final String MARKER = "PATHOME_OE_DBF_20261030_CBB093E7_V1";
    public static final String IMAGE_DIGEST =
            "sha256:d81f7b51697014367236d07ec58b54153fc28f828b72bf247fe5c7487520bcb0";

    private static final String MIGRATOR_PASSWORD_FILE =
            RUN_ROOT + "/secrets/migrator.password";
    private static final String RUNTIME_PASSWORD_FILE =
            RUN_ROOT + "/secrets/runtime.password";
    private static final String LIVE_ATTESTATION_DIRECTORY =
            RUN_ROOT + "/live-attestations";
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE",
            "PGOPTIONS", "PGSERVICEFILE", "PGPASSFILE", "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", "PATHOME_OE_TEST_ALLOW_DISPOSABLE",
            "PATHOME_OE_TEST_DB", "PATHOME_OE_TEST_DB_HOST", "PATHOME_OE_TEST_DB_PORT",
            "PATHOME_PACKAGE5_FLYWAY_TEST", "PATHOME_FRESH_INTEGRATION_TEST",
            "PATHOME_FRESH_INTEGRATION_MANIFEST");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile Target verified;

    private DbA01LeadTestTarget() {}

    public record Target(
            String database,
            String runtimeRole,
            String migratorRole,
            Path runtimePasswordFile,
            Path migratorPasswordFile,
            String runtimePassword,
            String migratorPassword,
            Path manifestPath,
            long databaseOid,
            String systemIdentifier,
            String containerId,
            String networkId,
            String hbaSourceAddress) {
        public String runtimeUrl() {
            return jdbcUrl(database);
        }

        public String migratorUrl() {
            return jdbcUrl(database);
        }

        public Connection openRuntime() throws SQLException {
            return DriverManager.getConnection(runtimeUrl(), runtimeRole, runtimePassword);
        }

        public Connection openMigrator() throws SQLException {
            return DriverManager.getConnection(migratorUrl(), migratorRole, migratorPassword);
        }

        @Override
        public String toString() {
            return "DbA01LeadTestTarget[database=" + database + ", runtimeRole=" + runtimeRole
                    + ", migratorRole=" + migratorRole + ", databaseOid=" + databaseOid
                    + ", containerId=" + containerId + ", secret=<redacted>]";
        }
    }

    public static Target requireFromEnvironment() {
        Target current = verified;
        if (current != null) return current;
        synchronized (DbA01LeadTestTarget.class) {
            if (verified == null) {
                verified = verify(System.getenv());
            }
            return verified;
        }
    }

    static Target verify(Map<String, String> environment) {
        if (!"true".equals(environment.get(TEST_FLAG))) {
            throw refuse("the explicit DB-A01 Lead test flag is absent");
        }
        for (String name : FORBIDDEN_ENVIRONMENT) {
            if (environment.containsKey(name)) {
                throw refuse("inherited database/test setting " + name + " is forbidden");
            }
        }
        if (!MANIFEST_PATH.equals(environment.get(MANIFEST_FLAG))) {
            throw refuse("the manifest path is not the exact child custody file");
        }
        JsonNode manifest = readManifest(Path.of(MANIFEST_PATH));
        verifyManifestDocument(manifest);
        String manifestSha256 = sha256(Path.of(MANIFEST_PATH));
        verifyAttestation(
                latestAttestation(Path.of(manifest.at("/liveAttestationDirectory").asText())),
                manifest,
                manifestSha256);

        Path runtimeSecret = Path.of(manifest.at("/secrets/runtimePasswordFile").asText());
        require(RUNTIME_PASSWORD_FILE.equals(runtimeSecret.toString()), "runtime secret path");
        Path migratorSecret =
                Path.of(manifest.at("/secrets/migratorPasswordFile").asText());
        require(MIGRATOR_PASSWORD_FILE.equals(migratorSecret.toString()), "migrator secret path");
        String runtimePassword = readSecret(runtimeSecret);
        String migratorPassword = readSecret(migratorSecret);

        Target target = new Target(DATABASE, RUNTIME_ROLE, MIGRATOR_ROLE, runtimeSecret,
                migratorSecret, runtimePassword, migratorPassword, Path.of(MANIFEST_PATH),
                manifest.at("/database/oid").asLong(),
                manifest.at("/database/systemIdentifier").asText(),
                manifest.at("/container/id").asText(),
                manifest.at("/container/networkId").asText(),
                manifest.at("/database/hbaSourceCidr").asText().replace("/32", ""));
        verifyLiveDatabaseIdentity(target);
        return target;
    }

    static void verifyManifestDocument(JsonNode manifest) {
        require(manifest.path("schemaVersion").asInt() == 1, "manifest schema");
        require("PREPARED".equals(manifest.path("provisioningState").asText()),
                "manifest provisioning state");
        require(RUN_NAME.equals(manifest.path("runName").asText()), "run name");
        require(RUN_UUID.equals(manifest.path("runUuid").asText()), "run UUID");
        require(MARKER.equals(manifest.path("marker").asText()), "database marker");
        require(DATABASE.equals(manifest.at("/database/name").asText()), "database");
        require(DATABASE_OWNER.equals(manifest.at("/database/owner").asText()),
                "database owner");
        require(HOST.equals(manifest.at("/database/host").asText()), "database host");
        require(PORT == manifest.at("/database/port").asInt(), "database port");
        require(manifest.at("/database/oid").asLong() > 0, "database OID");
        require(manifest.at("/database/systemIdentifier").asText()
                .matches("[0-9]{10,20}"), "PostgreSQL system ID");
        require(manifest.at("/database/hbaSourceCidr").asText()
                .matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}/32"), "HBA source address");
        require(RUNTIME_ROLE.equals(manifest.at("/roles/runtime").asText()), "runtime role");
        require(MIGRATOR_ROLE.equals(manifest.at("/roles/migrator").asText()), "migrator role");
        require(DATABASE_OWNER.equals(manifest.at("/roles/databaseOwner").asText()),
                "database owner role");
        require(manifest.at("/container/name").asText().equals(RUN_NAME), "container name");
        require(manifest.at("/container/id").asText().matches("[0-9a-f]{64}"), "container ID");
        require(manifest.at("/container/networkId").asText().matches("[0-9a-f]{64}"), "network ID");
        require(manifest.at("/container/volume").asText()
                .equals(RUN_NAME + "-pgdata"), "volume name");
        require(IMAGE_DIGEST.equals(manifest.at("/container/imageDigest").asText()),
                "image digest");
        require(RUNTIME_PASSWORD_FILE.equals(
                manifest.at("/secrets/runtimePasswordFile").asText()), "runtime secret path");
        require(MIGRATOR_PASSWORD_FILE.equals(
                manifest.at("/secrets/migratorPasswordFile").asText()), "migrator secret path");
        require(LIVE_ATTESTATION_DIRECTORY.equals(
                manifest.at("/liveAttestationDirectory").asText()), "attestation directory");
    }

    public static void verifyLiveDatabaseIdentity(Target target) {
        try (Connection runtime = target.openRuntime()) {
            require(DATABASE.equals(scalar(runtime, "select current_database()")),
                    "runtime database identity");
            require(RUNTIME_ROLE.equals(scalar(runtime, "select current_user")),
                    "runtime role identity");
            require(target.hbaSourceAddress().equals(
                    scalar(runtime, "select host(inet_client_addr())")),
                    "runtime authenticated client source");
            require(target.databaseOid() == scalarLong(runtime,
                    "select oid from pg_database where datname = current_database()"),
                    "runtime database OID");
            require(Boolean.FALSE.equals(scalarBoolean(runtime,
                    "select rolcanlogin and rolsuper or rolcreatedb or rolcreaterole "
                            + "or rolinherit from pg_roles where rolname = current_user")),
                    "runtime role privilege boundary");
        } catch (SQLException exception) {
            throw refuse("runtime connection identity could not be verified", exception);
        }
        try (Connection migrator = target.openMigrator()) {
            require(DATABASE.equals(scalar(migrator, "select current_database()")),
                    "migrator database identity");
            require(MIGRATOR_ROLE.equals(scalar(migrator, "select current_user")),
                    "migrator role identity");
            require(MARKER.equals(scalar(migrator,
                    "select disposable_marker from it_control.run_identity")),
                    "disposable marker");
            require(RUN_UUID.equals(scalar(migrator,
                    "select run_uuid::text from it_control.run_identity")), "run marker identity");
            require(MARKER.equals(scalar(migrator,
                    "select disposable_marker from it_control.run_identity")), "control marker");
            require(target.systemIdentifier().equals(scalar(migrator,
                    "select pg_system_identifier from it_control.run_identity")),
                    "control system identifier");
            require(target.containerId().equals(scalar(migrator,
                    "select container_id from it_control.run_identity")), "control container identity");
            require(target.networkId().equals(scalar(migrator,
                    "select network_id from it_control.run_identity")), "control network identity");
            require(DATABASE_OWNER.equals(scalar(migrator,
                    "select pg_get_userbyid(datdba) from pg_database "
                            + "where datname = current_database()")), "database owner identity");
            require(target.hbaSourceAddress().equals(
                    scalar(migrator, "select host(inet_client_addr())")),
                    "migrator authenticated client source");
            require(target.databaseOid() == scalarLong(migrator,
                    "select oid from pg_database where datname = current_database()"),
                    "migrator database OID");
            require(Boolean.FALSE.equals(scalarBoolean(migrator,
                    "select rolsuper or rolcreatedb or rolcreaterole or rolinherit "
                            + "from pg_roles where rolname = current_user")),
                    "migrator role privilege boundary");
        } catch (SQLException exception) {
            throw refuse("migrator/database marker could not be verified", exception);
        }
    }

    public static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw refuse("required evidence file could not be hashed", exception);
        }
    }

    private static JsonNode readManifest(Path path) {
        require(path.isAbsolute() && !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "manifest file");
        require(permissions(path).equals(PosixFilePermissions.fromString("rw-------")),
                "manifest permissions");
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw refuse("manifest could not be read", exception);
        }
    }

    private static void verifyAttestation(
            Path path,
            JsonNode manifest,
            String manifestSha256
    ) {
        require(path.startsWith(Path.of(LIVE_ATTESTATION_DIRECTORY)),
                "attestation must stay inside the live-attestation directory");
        require(permissions(path).equals(PosixFilePermissions.fromString("rw-------")),
                "attestation permissions");
        verifyAttestationDocument(readManifest(path), Instant.now(), manifest, manifestSha256);
    }

    static void verifyAttestationDocument(JsonNode attestation, Instant now) {
        JsonNode manifest = readManifest(Path.of(MANIFEST_PATH));
        verifyAttestationDocument(attestation, now, manifest, sha256(Path.of(MANIFEST_PATH)));
    }

    private static void verifyAttestationDocument(
            JsonNode attestation,
            Instant now,
            JsonNode manifest,
            String manifestSha256
    ) {
        require(attestation.path("schemaVersion").asInt() == 1,
                "attestation schema");
        require("LIVE_ATTESTED".equals(attestation.path("status").asText()),
                "live attestation status");
        require(RUN_UUID.equals(attestation.path("runUuid").asText()),
                "attestation run UUID");
        require(manifestSha256.equals(attestation.path("manifestSha256").asText()),
                "attestation manifest digest");
        require(DATABASE.equals(attestation.path("database").asText()),
                "attestation database");
        require(manifest.at("/database/oid").asLong() ==
                attestation.path("databaseOid").asLong(), "attestation database OID");
        require(manifest.at("/container/id").asText()
                .equals(attestation.path("containerId").asText()),
                "attestation container");
        require(manifest.at("/container/networkId").asText()
                .equals(attestation.path("networkId").asText()),
                "attestation network");
        require(manifest.at("/database/systemIdentifier").asText()
                .equals(attestation.path("systemIdentifier").asText()),
                "attestation system ID");
        require(IMAGE_DIGEST.equals(attestation.path("imageDigest").asText()),
                "attestation image");
        require((HOST + ":" + PORT).equals(attestation.path("endpoint").asText()),
                "attestation endpoint");
        require(manifest.at("/database/hbaSourceCidr").asText()
                .equals(attestation.path("hbaSourceCidr").asText()),
                "attestation HBA source");
        require(MIGRATOR_ROLE.equals(attestation.path("migratorRole").asText()),
                "attestation migrator role");
        require(RUNTIME_ROLE.equals(attestation.path("runtimeRole").asText()),
                "attestation runtime role");
        try {
            Instant attestedAt = Instant.parse(attestation.path("attestedAt").asText());
            require(!attestedAt.isBefore(now.minus(Duration.ofMinutes(10))),
                    "live attestation freshness");
            require(!attestedAt.isAfter(now.plus(Duration.ofMinutes(1))),
                    "live attestation future time");
        } catch (DateTimeParseException exception) {
            throw refuse("live attestation time is malformed", exception);
        }
    }

    private static Path latestAttestation(Path directory) {
        require(directory.isAbsolute() && !Files.isSymbolicLink(directory)
                        && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
                "attestation directory");
        try (var files = Files.list(directory)) {
            return files
                    .filter(path -> path.getFileName().toString()
                            .matches("^[0-9]{8}T[0-9]{12}Z-live-attestation\\.json$"))
                    .filter(path -> !Files.isSymbolicLink(path)
                            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(java.util.Comparator.comparing(path ->
                            path.getFileName().toString()))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> refuse("no live attestation receipt exists"));
        } catch (IOException exception) {
            throw refuse("live attestation directory could not be read", exception);
        }
    }

    private static String readSecret(Path path) {
        require(path.isAbsolute() && !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "secret file");
        require(permissions(path).equals(PosixFilePermissions.fromString("rw-------")),
                "secret permissions");
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            require(!value.isBlank(), "secret content");
            return value;
        } catch (IOException exception) {
            throw refuse("secret could not be read", exception);
        }
    }

    private static Set<PosixFilePermission> permissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw refuse("POSIX permissions could not be checked", exception);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("database identity query returned no row");
            return rows.getString(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("database identity query returned no row");
            return rows.getLong(1);
        }
    }

    private static Boolean scalarBoolean(Connection connection, String sql)
            throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("database identity query returned no row");
            return rows.getBoolean(1);
        }
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw refuse(message);
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("REFUSED DB-A01 Lead target: " + message);
    }

    private static IllegalStateException refuse(String message, Exception cause) {
        return new IllegalStateException("REFUSED DB-A01 Lead target: " + message, cause);
    }
}

