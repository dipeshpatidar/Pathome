package com.indore.pathome.spaces.support;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OgV63GuardTest {
    private static final String VALID = """
            {"host":"127.0.0.1","port":5432,"database":"pathome_oe_test_og_v63_20261029_01",
             "schema":"public","searchPath":"public,pg_catalog","runId":"og-v63-20261029-01",
             "runUuid":"937475a5-8e9c-4ee6-9f42-c8204cfe2955","owner":"og_v63_20261029_01_owner",
             "systemIdentifier":"7439086961294534771","databaseOid":"12345","custodian":"dipeshpatidar",
             "migrationManifestSha256":"21ad552ddebe723b167b9f78ddbbc70af8da0bef6233096727caa3ee1a883092"}
            """;
    private static Map<String,String> validEnvironment() {
        return Map.of("PATHOME_OG_V63_TEST", "true", "PATHOME_OG_V63_MANIFEST",
                OgV63Target.ROOT.resolve("manifest.json").toString());
    }
    private static void refusedBeforeConnection(Runnable guard) {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> OgV63Target.afterGuards(guard, attempts::incrementAndGet));
        assertEquals(0, attempts.get(), "refused input must cause ZERO connection attempts");
    }
    @Test void positiveBindingAndFrozenSources() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        assertEquals(1, OgV63Target.afterGuards(() -> {
            OgV63Target.validateEnvironment(validEnvironment(), Map.of());
            OgV63Target.validateManifest(OgV63Target.parseManifest(VALID));
        }, attempts::incrementAndGet));
        OgV63Migration.verifySources();
    }
    @ParameterizedTest @ValueSource(strings={"pathome_db", "pathome_oe_test_integration", "pathome_n2_v63_it_20261028"})
    void protectedDatabases(String database) {
        refusedBeforeConnection(() -> OgV63Target.validateManifest(OgV63Target.parseManifest(
                VALID.replace(OgV63Target.DATABASE, database))));
    }
    @ParameterizedTest @ValueSource(strings={"localhost", "::1", "0.0.0.0", "127.0.0.2"})
    void wrongHosts(String host) {
        refusedBeforeConnection(() -> OgV63Target.validateManifest(OgV63Target.parseManifest(VALID.replace("127.0.0.1", host))));
    }
    @ParameterizedTest @ValueSource(strings={"55436", "5433", "0"})
    void wrongPorts(String port) {
        refusedBeforeConnection(() -> OgV63Target.validateManifest(OgV63Target.parseManifest(VALID.replace("5432", port))));
    }
    @ParameterizedTest @ValueSource(strings={"PGHOST","PGOPTIONS","PGSERVICE","SPRING_DATASOURCE_URL",
            "SPRING_JPA_HIBERNATE_DDL_AUTO","SPRING_FLYWAY_ENABLED","DATABASE_URL","JDBC_URL",
            "PATHOME_A1_A7_ISOLATED_TEST","PATHOME_OE_TEST_DB","APP_FIRST_ADMIN_PROVISIONING_ENABLED"})
    void inheritedOverrides(String key) {
        Map<String,String> env = new HashMap<>(validEnvironment()); env.put(key,"inherited");
        refusedBeforeConnection(() -> OgV63Target.validateEnvironment(env, Map.of()));
    }
    @ParameterizedTest @ValueSource(strings={"spring.datasource.url","spring.jpa.hibernate.ddl-auto",
            "spring.flyway.enabled","hibernate.default_schema","jdbc.url"})
    void jvmOverrides(String key) {
        refusedBeforeConnection(() -> OgV63Target.validateEnvironment(validEnvironment(),Map.of(key,"unsafe")));
    }
    @Test void missingAndDuplicateMarkers() {
        refusedBeforeConnection(() -> OgV63Target.parseManifest("{}"));
        refusedBeforeConnection(() -> OgV63Target.parseManifest(VALID.replace("{", "{\"host\":\"127.0.0.1\",")));
        refusedBeforeConnection(() -> OgV63Target.validateEnvironment(Map.of(), Map.of()));
        refusedBeforeConnection(() -> OgV63Target.validateEnvironment(Map.of("PATHOME_OG_V63_TEST","true",
                "PATHOME_OG_V63_MANIFEST","/tmp/foreign.json"), Map.of()));
    }
    @ParameterizedTest @ValueSource(strings={"public","public,pg_catalog","og-v63-20261029-01",
            "937475a5-8e9c-4ee6-9f42-c8204cfe2955","og_v63_20261029_01_owner",
            "21ad552ddebe723b167b9f78ddbbc70af8da0bef6233096727caa3ee1a883092"})
    void wrongSchemaPathRunRoleUuidOrSource(String value) {
        refusedBeforeConnection(() -> OgV63Target.validateManifest(OgV63Target.parseManifest(VALID.replace("\""+value+"\"","\"foreign\""))));
    }
    @Test void missingReceiptAndCredentialsCannotOpenConnection() {
        refusedBeforeConnection(() -> OgV63Target.requireTarget(OgV63Target.RUNTIME, true));
        refusedBeforeConnection(() -> OgV63Target.validateReceipt(OgV63Target.parse("""
                {"runUuid":"937475a5-8e9c-4ee6-9f42-c8204cfe2955","manifestSha256":"bad",
                 "migrationManifestSha256":"bad","historySha256":"bad","versionCount":62,"target":63,"state":"SEALED"}
                """,java.util.Set.of("runUuid","manifestSha256","migrationManifestSha256","historySha256","versionCount","target","state")), "expected"));
    }
    @Test void startupRequiresEveryEffectiveSettingBeforeConnection() {
        OgV63RuntimeConfiguration.validateSettings(OgV63RuntimeConfiguration.REQUIRED::get);
        for (String key : OgV63RuntimeConfiguration.REQUIRED.keySet()) {
            Map<String,String> settings = new HashMap<>(OgV63RuntimeConfiguration.REQUIRED);
            settings.put(key,"unsafe");
            refusedBeforeConnection(() -> OgV63RuntimeConfiguration.validateSettings(settings::get));
        }
    }

    @Test void liveIdentityMismatchRefusesBeforeAnyConnectionFactoryCall() {
        var manifest = OgV63Target.parseManifest(VALID);
        var valid = new OgV63Target.LiveFacts(
                OgV63Target.DATABASE, OgV63Target.RUNTIME, "public",
                "public, pg_catalog", "127.0.0.1/32", 5432, "12345",
                OgV63Target.OWNER, "7439086961294534771", true, 0, 1,
                OgV63Target.UUID, "dd561442b6135c5c9870b0c93b68fb4f76a11477845de0ee47f1ab9eaec7e4e3",
                "7439086961294534771");
        AtomicInteger attempts = new AtomicInteger();
        OgV63Target.afterGuards(() -> OgV63Target.validateLiveFacts(
                manifest, valid.markerManifestSha256(), OgV63Target.RUNTIME, valid),
                attempts::incrementAndGet);
        assertEquals(1, attempts.get());
        var variants = java.util.List.of(
                new OgV63Target.LiveFacts("other", valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), "foreign", valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), 5433,
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        "999", valid.databaseOwner(), valid.systemIdentifier(),
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), "foreign", valid.systemIdentifier(),
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), "foreign",
                        true, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        "foreign"),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        false, 0, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        true, 1, 1, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()),
                new OgV63Target.LiveFacts(valid.database(), valid.role(), valid.currentSchema(),
                        valid.searchPath(), valid.serverAddress(), valid.serverPort(),
                        valid.databaseOid(), valid.databaseOwner(), valid.systemIdentifier(),
                        true, 0, 2, valid.markerRunUuid(), valid.markerManifestSha256(),
                        valid.markerSystemIdentifier()));
        for (var variant : variants) {
            refusedBeforeConnection(() -> OgV63Target.validateLiveFacts(
                    manifest, valid.markerManifestSha256(), OgV63Target.RUNTIME, variant));
        }
    }

    @Test void missingForeignSymlinkedAndUnsafeCredentialsRefuseBeforeConnection(
            @TempDir Path root) throws Exception {
        Path realRoot = root.toRealPath();
        Path valid = realRoot.resolve("runtime.password");
        Files.writeString(valid, "a".repeat(64) + "\n");
        Files.setPosixFilePermissions(valid,
                PosixFilePermissions.fromString("rw-------"));
        assertEquals("a".repeat(64), OgV63Target.readCredential(valid));
        refusedBeforeConnection(() -> OgV63Target.readCredential(realRoot.resolve("missing")));
        Path unsafe = realRoot.resolve("unsafe.password");
        Files.writeString(unsafe, "b".repeat(64) + "\n");
        Files.setPosixFilePermissions(unsafe,
                PosixFilePermissions.fromString("rw-r-----"));
        refusedBeforeConnection(() -> OgV63Target.readCredential(unsafe));
        Path link = realRoot.resolve("link.password");
        Files.createSymbolicLink(link, valid);
        refusedBeforeConnection(() -> OgV63Target.readCredential(link));
        Path foreign = Files.createTempFile("og-v63-foreign-credential-", ".password");
        Files.writeString(foreign, "c".repeat(64) + "\n");
        Files.setPosixFilePermissions(foreign,
                PosixFilePermissions.fromString("rw-------"));
        Path replacement = realRoot.resolve("runtime.password");
        Files.delete(replacement);
        Files.createSymbolicLink(replacement, foreign);
        refusedBeforeConnection(() -> OgV63Target.readCredential(replacement));
    }

    @Test void receiptAndStartupGuardOrderingOccursBeforePoolCreation() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> OgV63Target.afterGuards(() -> {
            OgV63Target.validateEnvironment(validEnvironment(), Map.of());
            throw new IllegalStateException("receipt missing");
        }, () -> {
            attempts.incrementAndGet();
            return new HikariProbe();
        }));
        assertEquals(0, attempts.get());
        for (String setting : OgV63RuntimeConfiguration.REQUIRED.keySet()) {
            Map<String,String> invalid = new HashMap<>(OgV63RuntimeConfiguration.REQUIRED);
            invalid.put(setting, "unsafe");
            attempts.set(0);
            assertThrows(IllegalStateException.class, () -> OgV63Target.afterGuards(
                    () -> OgV63RuntimeConfiguration.validateSettings(invalid::get),
                    () -> {
                        attempts.incrementAndGet();
                        return new HikariProbe();
                    }));
            assertEquals(0, attempts.get());
        }
    }

    @Test void catalogAuditRejectsForeignOwnerDefinerNamespaceAndDependency() {
        var valid = new OgV63CatalogAudit.FunctionFact(
                "public", "guard_property", OgV63Target.MIGRATOR,
                false, false, true, true);
        assertDoesNotThrow(() -> OgV63CatalogAudit.requireContained(valid));
        for (var invalid : java.util.List.of(
                new OgV63CatalogAudit.FunctionFact(
                        "public", "guard_property", "foreign",
                        false, false, true, true),
                new OgV63CatalogAudit.FunctionFact(
                        "public", "guard_property", OgV63Target.MIGRATOR,
                        true, false, true, true),
                new OgV63CatalogAudit.FunctionFact(
                        "foreign", "guard_property", OgV63Target.MIGRATOR,
                        false, false, true, true),
                new OgV63CatalogAudit.FunctionFact(
                        "public", "ordinary_function", OgV63Target.MIGRATOR,
                        false, false, false, true))) {
            refusedBeforeConnection(() -> OgV63CatalogAudit.requireContained(invalid));
        }
        assertDoesNotThrow(() -> OgV63CatalogAudit.requireDependency("public", "public"));
        refusedBeforeConnection(() -> OgV63CatalogAudit.requireDependency("public", "foreign"));
    }

    @Test void sideEffectComparatorDetectsInjectedRowAndHashChange() {
        Map<String,String> before = new HashMap<>();
        for (String table : OgV63SideEffectObserver.TABLES) before.put(table, "0|empty");
        Map<String,String> unexpectedInsert = new HashMap<>(before);
        unexpectedInsert.put("property_visit_requests", "1|inserted");
        Map<String,String> unexpectedUpdate = new HashMap<>(before);
        unexpectedUpdate.put("visit_sessions", "0|updated");
        assertEquals(java.util.List.of("property_visit_requests"),
                OgV63SideEffectObserver.differences(before, unexpectedInsert));
        assertEquals(java.util.List.of("visit_sessions"),
                OgV63SideEffectObserver.differences(before, unexpectedUpdate));
        assertEquals(java.util.List.of(),
                OgV63SideEffectObserver.differences(before, new HashMap<>(before)));
    }

    @Test void privacyOracleRejectsADeliberatelyLeakingResponse() {
        String leaking = "{\"ownerPhone\":\"+91 90000 00001\","
                + "\"address\":\"Private address canary\"}";
        assertEquals(2, OgV63PrivacyOracle.findings(leaking).size());
        assertThrows(AssertionError.class, () -> OgV63PrivacyOracle.requireClean(leaking));
        assertDoesNotThrow(() -> OgV63PrivacyOracle.requireClean(
                "{\"title\":\"2 BHK Flat in Vijay Nagar, Indore\"}"));
    }

    private static final class HikariProbe {}
}
