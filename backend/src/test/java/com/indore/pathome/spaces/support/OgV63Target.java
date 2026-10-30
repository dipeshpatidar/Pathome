package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Separate, fail-closed Option B binding. No historical designation is reused. */
public final class OgV63Target {
    public static final String DATABASE = "pathome_oe_test_og_v63_20261029_01";
    public static final String RUN_ID = "og-v63-20261029-01";
    public static final String UUID = "937475a5-8e9c-4ee6-9f42-c8204cfe2955";
    public static final String OWNER = "og_v63_20261029_01_owner";
    public static final String MIGRATOR = "og_v63_20261029_01_migrator";
    public static final String RUNNER = "og_v63_20261029_01_runner";
    public static final String RUNTIME = "og_v63_20261029_01_runtime";
    public static final Path ROOT = Path.of("/private/tmp/pathome-og-v63-20261029-01");
    public static final String SOURCES = "21ad552ddebe723b167b9f78ddbbc70af8da0bef6233096727caa3ee1a883092";
    public static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE
            + "?currentSchema=public&connectTimeout=3&socketTimeout=15&ApplicationName=" + RUN_ID;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> MANIFEST_KEYS = Set.of("host", "port", "database", "schema",
            "searchPath", "runId", "runUuid", "owner", "systemIdentifier", "databaseOid",
            "custodian", "migrationManifestSha256");
    private static final Set<String> RECEIPT_KEYS = Set.of("runUuid", "manifestSha256",
            "migrationManifestSha256", "historySha256", "versionCount", "target", "state");

    private OgV63Target() {}

    public static final class Target {
        private final String role;
        private final String password;
        private final JsonNode manifest;
        private final String manifestHash;

        private Target(String role, String password, JsonNode manifest, String manifestHash) {
            this.role = role;
            this.password = password;
            this.manifest = manifest;
            this.manifestHash = manifestHash;
        }

        public String role() { return role; }
        String password() { return password; }
        String manifestHash() { return manifestHash; }

        public Connection open() throws SQLException {
            Connection connection = DriverManager.getConnection(URL, role, password);
            try {
                prepare(connection);
                return connection;
            } catch (Throwable failure) {
                connection.close();
                throw failure;
            }
        }

        public void prepare(Connection connection) throws SQLException {
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO public,pg_catalog");
                statement.execute("SET statement_timeout='10s'");
                statement.execute("SET lock_timeout='2s'");
                statement.execute("SET idle_in_transaction_session_timeout='15s'");
            }
            verify(connection);
        }

        public void verify(Connection connection) throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    select current_database(),current_user,current_schema(),
                           current_setting('search_path'),inet_server_addr()::text,inet_server_port(),
                           d.oid::text,pg_get_userbyid(d.datdba),r.rolsuper,r.rolcreatedb,
                           r.rolcreaterole,r.rolreplication,r.rolbypassrls,r.rolinherit,
                           (select count(*) from pg_auth_members where member=r.oid)
                    from pg_database d cross join pg_roles r
                    where d.datname=current_database() and r.rolname=current_user
                    """)) {
                require(rows.next(), "connection facts absent");
                require(DATABASE.equals(rows.getString(1)) && role.equals(rows.getString(2))
                        && "public".equals(rows.getString(3))
                        && "public, pg_catalog".equals(rows.getString(4))
                        && "127.0.0.1/32".equals(rows.getString(5)) && rows.getInt(6) == 5432
                        && manifest.path("databaseOid").asText().equals(rows.getString(7))
                        && OWNER.equals(rows.getString(8)), "connection target differs");
                for (int i = 9; i <= 14; i++) require(!rows.getBoolean(i), "unsafe role attribute");
                require(rows.getInt(15) == 0 && !rows.next(), "role membership or duplicate facts");
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    select run_uuid::text,manifest_sha256,system_identifier from it_control.run_identity
                    """)) {
                require(rows.next() && UUID.equals(rows.getString(1))
                        && manifestHash.equals(rows.getString(2))
                        && manifest.path("systemIdentifier").asText().equals(rows.getString(3))
                        && !rows.next(), "missing, duplicate or foreign run marker");
            }
        }

        public void requireReceipt(Connection connection) throws SQLException {
            JsonNode receipt = readJson(ROOT.resolve("receipt.json"), RECEIPT_KEYS);
            validateReceipt(receipt, manifestHash);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select receipt_sha256 from it_control.migration_receipt")) {
                require(rows.next() && digestFile(ROOT.resolve("receipt.json")).equals(rows.getString(1))
                        && !rows.next(), "unsealed migration receipt");
            }
        }

        @Override public String toString() { return "OgV63Target[" + DATABASE + "," + role + "]"; }
    }

    record LiveFacts(
            String database,
            String role,
            String currentSchema,
            String searchPath,
            String serverAddress,
            int serverPort,
            String databaseOid,
            String databaseOwner,
            String systemIdentifier,
            boolean roleAttributesSafe,
            int roleMemberships,
            int markerCount,
            String markerRunUuid,
            String markerManifestSha256,
            String markerSystemIdentifier) {}

    static void validateLiveFacts(JsonNode manifest, String manifestHash, String expectedRole,
                                  LiveFacts facts) {
        require(DATABASE.equals(facts.database()) && expectedRole.equals(facts.role())
                && "public".equals(facts.currentSchema())
                && "public, pg_catalog".equals(facts.searchPath())
                && "127.0.0.1/32".equals(facts.serverAddress())
                && facts.serverPort() == 5432
                && manifest.path("databaseOid").asText().equals(facts.databaseOid())
                && OWNER.equals(facts.databaseOwner())
                && manifest.path("systemIdentifier").asText().equals(facts.systemIdentifier())
                && facts.roleAttributesSafe() && facts.roleMemberships() == 0,
                "connection target/role facts differ");
        require(facts.markerCount() == 1 && UUID.equals(facts.markerRunUuid())
                && manifestHash.equals(facts.markerManifestSha256())
                && manifest.path("systemIdentifier").asText().equals(
                        facts.markerSystemIdentifier()),
                "missing, duplicate or foreign run marker");
    }

    public static Target requireTarget(String role, boolean receiptRequired) {
        validateEnvironment(System.getenv(), System.getProperties().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString())));
        require(Set.of(MIGRATOR, RUNNER, RUNTIME).contains(role), "unapproved role");
        requirePrivatePath(ROOT, true, "rwx------");
        Path path = ROOT.resolve("manifest.json");
        JsonNode manifest = readJson(path, MANIFEST_KEYS);
        validateManifest(manifest);
        String manifestHash = digestFile(path);
        require(manifestHash.equals(readPrivate(ROOT.resolve("manifest.sha256"), "r--------").strip()),
                "tampered custody manifest");
        if (receiptRequired) validateReceipt(readJson(ROOT.resolve("receipt.json"), RECEIPT_KEYS), manifestHash);
        String secret = readCredential(ROOT.resolve(role + ".password"));
        return new Target(role, secret, manifest, manifestHash);
    }

    static String readCredential(Path path) {
        String secret = readPrivate(path, "rw-------").strip();
        require(secret.matches("[a-f0-9]{64}"), "invalid role credential");
        return secret;
    }

    static void validateEnvironment(Map<String, String> environment, Map<String, String> properties) {
        require("true".equals(environment.get("PATHOME_OG_V63_TEST")), "explicit opt-in absent");
        require(ROOT.resolve("manifest.json").toString().equals(environment.get("PATHOME_OG_V63_MANIFEST")),
                "wrong manifest path");
        for (String key : environment.keySet()) {
            require(!(key.startsWith("PG") || key.startsWith("SPRING_") || key.contains("JDBC")
                    || key.equals("DATABASE_URL") || key.equals("TEST_DATABASE_URL")
                    || key.startsWith("PATHOME_A1_A7_") || key.startsWith("PATHOME_FRESH_")
                    || key.startsWith("PATHOME_OE_") || key.startsWith("PATHOME_DB_A01_")
                    || key.equals("PATHOME_VISIT_OPERATIONS_POSTGRES_TEST")
                    || key.equals("PATHOME_PACKAGE5_FLYWAY_TEST")
                    || key.equals("APP_FIRST_ADMIN_PROVISIONING_ENABLED")), "inherited database/startup override");
        }
        for (String key : properties.keySet()) require(!(key.startsWith("spring.")
                || key.startsWith("javax.persistence.") || key.startsWith("jakarta.persistence.")
                || key.startsWith("hibernate.") || key.startsWith("jdbc.")
                || key.startsWith("APP_FIRST_ADMIN")), "JVM startup override");
    }

    static JsonNode parse(String text, Set<String> keys) {
        try {
            JsonNode node = JSON.readTree(text);
            require(node != null && node.isObject(), "JSON object required");
            Set<String> found = new HashSet<>(); node.fieldNames().forEachRemaining(found::add);
            require(found.equals(keys), "missing or unexpected custody fields");
            return node;
        } catch (java.io.IOException exception) { throw refuse("malformed/duplicate custody JSON"); }
    }

    static JsonNode parseManifest(String text) { return parse(text, MANIFEST_KEYS); }

    static void validateManifest(JsonNode node) {
        require("127.0.0.1".equals(node.path("host").asText()) && node.path("port").isInt()
                && node.path("port").intValue() == 5432 && DATABASE.equals(node.path("database").asText())
                && "public".equals(node.path("schema").asText())
                && "public,pg_catalog".equals(node.path("searchPath").asText())
                && RUN_ID.equals(node.path("runId").asText()) && UUID.equals(node.path("runUuid").asText())
                && OWNER.equals(node.path("owner").asText())
                && SOURCES.equals(node.path("migrationManifestSha256").asText())
                && node.path("systemIdentifier").asText().matches("[0-9]{10,20}")
                && node.path("databaseOid").asText().matches("[0-9]{1,10}")
                && "dipeshpatidar".equals(node.path("custodian").asText()), "foreign/stale manifest");
    }

    static void validateReceipt(JsonNode node, String manifestHash) {
        require(UUID.equals(node.path("runUuid").asText())
                && manifestHash.equals(node.path("manifestSha256").asText())
                && SOURCES.equals(node.path("migrationManifestSha256").asText())
                && node.path("historySha256").asText().matches("[a-f0-9]{64}")
                && node.path("versionCount").isInt() && node.path("versionCount").intValue() == 63
                && node.path("target").isInt() && node.path("target").intValue() == 63
                && "SEALED".equals(node.path("state").asText()), "incomplete/foreign receipt");
    }

    static <T> T afterGuards(Runnable guards, Supplier<T> connectionFactory) {
        guards.run(); return connectionFactory.get();
    }

    private static JsonNode readJson(Path path, Set<String> keys) {
        return parse(readPrivate(path, "r--------"), keys);
    }

    private static String readPrivate(Path path, String mode) {
        requirePrivatePath(path, false, mode);
        try {
            require(Files.size(path) > 0 && Files.size(path) <= 16384, "unbounded/empty custody file");
            return Files.readString(path);
        } catch (java.io.IOException exception) { throw refuse("custody read failed"); }
    }

    private static void requirePrivatePath(Path path, boolean directory, String mode) {
        try {
            require(!Files.isSymbolicLink(path) && path.toRealPath().equals(path)
                    && (directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    && Files.getOwner(path).equals(Files.getOwner(ROOT))
                    && Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(mode)),
                    "unsafe custody path/owner/permissions");
        } catch (java.io.IOException exception) { throw refuse("custody unavailable"); }
    }

    static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String digestFile(Path path) {
        try { return digest(Files.readAllBytes(path)); }
        catch (java.io.IOException exception) { throw refuse("custody hash failed"); }
    }

    static void require(boolean condition, String message) { if (!condition) throw refuse(message); }
    static IllegalStateException refuse(String message) { return new IllegalStateException("OG V63 REFUSED: " + message); }
}
