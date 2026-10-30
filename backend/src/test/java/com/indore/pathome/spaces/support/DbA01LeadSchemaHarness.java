package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** Append-only V1-to-V63 migration for the fresh OE D-B isolated Lead database. */
public final class DbA01LeadSchemaHarness {
    private static final String V61_SHA256 =
            "b1f13eb476d5dccd7671acf16983bb607d162a2f343610612e4f6ec014bf016f";
    private static final String V62_SHA256 =
            "cd64c50a592dc7f82abdee746b805d15c27ce148c860bdccab70293cafc36a45";
    private static final String V63_SHA256 =
            "0470ffe80655e9693a18fe6aa8aea8e2ee0c3948827cfd337073149087c301e8";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path V60_RECEIPT =
            Path.of(DbA01LeadTestTarget.RUN_ROOT, "migration-receipt.json");
    private static final Path RECEIPT =
            Path.of(DbA01LeadTestTarget.RUN_ROOT, "migration-receipt-v63.json");

    private DbA01LeadSchemaHarness() {}

    public static void migrate(DbA01LeadTestTarget.Target target)
            throws Exception {
        DbA01LeadTestTarget.verifyLiveDatabaseIdentity(target);
        if (Files.exists(RECEIPT)) {
            verifyLedger(target, 63);
            applyRuntimeAcls(target);
            verifyReceipt(target);
            return;
        }
        verifyV60Receipt(target);
        FreshIntegrationSchemaHarness.verifySourcePins(
                new FreshIntegrationTarget.SourcePins(
                        FreshIntegrationTarget.LEGACY_CORE_SHA256,
                        FreshIntegrationTarget.MIGRATIONS_SHA256,
                        FreshIntegrationTarget.V55_SHA256,
                        FreshIntegrationTarget.V56_SHA256,
                        FreshIntegrationTarget.V57_SHA256));
        verifyDigest(
                "db/migration/V61__lead_phone_availability_and_visit_conversion.sql",
                V61_SHA256);
        verifyDigest(
                "db/migration/V62__lead_visit_link_per_property.sql",
                V62_SHA256);
        verifyDigest(
                "db/migration/V63__lead_phone_outbox_delivered_state.sql",
                V63_SHA256);
        Flyway flyway = Flyway.configure()
                .dataSource(target.migratorUrl(), target.migratorRole(), target.migratorPassword())
                .schemas("public")
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .initSql("SET search_path TO public, it_extensions, pg_catalog")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .ignoreMigrationPatterns("*:pending")
                .target("63")
                .load();
        flyway.validate();
        flyway.migrate();
        flyway.validate();
        verifyLedger(target, 63);
        applyRuntimeAcls(target);
        try (Connection connection = target.openMigrator()) {
            verifyV63Schema(connection);
        }
        String fixtureDigest;
        try (Connection connection = target.openMigrator()) {
            fixtureDigest = DbA01LeadFixtureHarness.installAndVerify(connection, target);
        }
        verifyLedger(target, 63);
        writeReceipt(target, fixtureDigest);
    }

    private static void verifyLedger(
            DbA01LeadTestTarget.Target target,
            int ceiling
    ) throws Exception {
        try (Connection connection = target.openMigrator()) {
            if (scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where version='1' and success") != 1) {
                throw refuse("baseline version 1 is not exact");
            }
            if (scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where success and version::integer between 2 and "
                            + ceiling) != ceiling - 1) {
                throw refuse("V2-V" + ceiling + " ledger is not complete");
            }
            if (scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where version is not null and version::integer > "
                            + ceiling) != 0) {
                throw refuse("migration ceiling was exceeded");
            }
            if (scalar(connection,
                    "select count(*) from public.flyway_schema_history where not success") != 0) {
                throw refuse("migration ledger contains a failed row");
            }
        }
    }

    private static void writeReceipt(
            DbA01LeadTestTarget.Target target,
            String fixtureDigest
    )
            throws Exception {
        String payload = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("runUuid", DbA01LeadTestTarget.RUN_UUID),
                Map.entry("database", DbA01LeadTestTarget.DATABASE),
                Map.entry("migratorRole", DbA01LeadTestTarget.MIGRATOR_ROLE),
                Map.entry("baselineVersion", 1),
                Map.entry("previousReceiptSha256", sha256(V60_RECEIPT)),
                Map.entry("targetVersion", 63),
                Map.entry("successfulRows", 63),
                Map.entry("legacyCoreSha256", FreshIntegrationTarget.LEGACY_CORE_SHA256),
                Map.entry("v2ToV60ManifestSha256", FreshIntegrationTarget.V60_MIGRATIONS_SHA256),
                Map.entry("v61Sha256", V61_SHA256),
                Map.entry("v62Sha256", V62_SHA256),
                Map.entry("v63Sha256", V63_SHA256),
                Map.entry("fixtureDigest", fixtureDigest),
                Map.entry("completedAt", Instant.now().toString()))) + "\n";
        Files.createFile(RECEIPT,
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(RECEIPT, StandardOpenOption.WRITE)) {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void verifyReceipt(DbA01LeadTestTarget.Target target)
            throws Exception {
        var receipt = JSON.readTree(Files.readString(RECEIPT, StandardCharsets.UTF_8));
        if (!DbA01LeadTestTarget.RUN_UUID.equals(receipt.path("runUuid").asText())
                || !DbA01LeadTestTarget.DATABASE.equals(receipt.path("database").asText())
                || receipt.path("targetVersion").asInt() != 63
                || receipt.path("successfulRows").asInt() != 63
                || !FreshIntegrationTarget.V60_MIGRATIONS_SHA256.equals(
                        receipt.path("v2ToV60ManifestSha256").asText())
                || !V61_SHA256.equals(receipt.path("v61Sha256").asText())
                || !V62_SHA256.equals(receipt.path("v62Sha256").asText())
                || !V63_SHA256.equals(receipt.path("v63Sha256").asText())
                || !receipt.path("fixtureDigest").asText()
                        .matches("[0-9a-f]{64}")) {
            throw refuse("migration receipt does not match the child target");
        }
        verifyLedger(target, 63);
        try (Connection connection = target.openMigrator()) {
            verifyV63Schema(connection);
            DbA01LeadFixtureHarness.verifyInstalled(connection, target);
        } catch (SQLException exception) {
            throw refuse("receipted fixtures could not be verified", exception);
        }
    }

    private static void verifyV60Receipt(DbA01LeadTestTarget.Target target) throws Exception {
        if (!Files.isRegularFile(V60_RECEIPT) || Files.isSymbolicLink(V60_RECEIPT)) {
            throw refuse("the preserved V60 receipt is absent or unsafe");
        }
        var receipt = JSON.readTree(Files.readString(V60_RECEIPT, StandardCharsets.UTF_8));
        if (!DbA01LeadTestTarget.RUN_UUID.equals(receipt.path("runUuid").asText())
                || !DbA01LeadTestTarget.DATABASE.equals(receipt.path("database").asText())
                || receipt.path("targetVersion").asInt() != 60
                || receipt.path("successfulRows").asInt() != 60
                || !FreshIntegrationTarget.V60_MIGRATIONS_SHA256.equals(
                        receipt.path("v2ToV60ManifestSha256").asText())) {
            throw refuse("preserved V60 receipt does not match the child target");
        }
        verifyLedger(target, 60);
    }

    private static void applyRuntimeAcls(DbA01LeadTestTarget.Target target) throws Exception {
        try (Connection connection = target.openMigrator()) {
            if (!DbA01LeadTestTarget.DATABASE.equals(
                    scalarString(connection, "select current_database()"))
                    || !DbA01LeadTestTarget.MIGRATOR_ROLE.equals(
                    scalarString(connection, "select current_user"))) {
                throw refuse("runtime ACL target or migrator role changed");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/db-a01-fresh-runtime-acls.sql"));
        }
    }

    private static void verifyV63Schema(Connection connection) throws Exception {
        if (scalar(connection, """
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='lead_routing_queue'
                  and column_name='contact_phone_revision'
                  and data_type='bigint' and is_nullable='NO'
                  and column_default='0'
                """) != 1) {
            throw refuse("contact_phone_revision column or default differs");
        }
        if (scalar(connection, """
                select count(*) from pg_constraint
                where conrelid='public.lead_routing_queue'::regclass
                  and conname='ck_lead_contact_phone_revision_nonnegative'
                  and pg_get_constraintdef(oid) like '%contact_phone_revision >= 0%'
                """) != 1) {
            throw refuse("contact_phone_revision nonnegative constraint differs");
        }
        if (scalar(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relkind='r'
                  and c.relname in (
                    'lead_phone_challenges', 'lead_phone_delivery_outbox',
                    'lead_lessor_availability_enquiries', 'lead_visit_proposals',
                    'lead_visit_continuations', 'lead_visit_customer_responses',
                    'lead_visit_association_claims', 'lead_visit_links',
                    'lead_assistance_command_receipts')
                  and pg_get_userbyid(c.relowner)=current_user
                """) != 9) {
            throw refuse("V61 table inventory or ownership differs");
        }
        if (scalar(connection, """
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='lead_visit_links'
                  and column_name='listing_id' and data_type='bigint' and is_nullable='NO'
                """) != 1
                || scalar(connection, """
                select count(*) from pg_constraint
                where conrelid='public.lead_visit_links'::regclass
                  and conname='fk_lead_visit_link_listing'
                  and contype='f'
                """) != 1
                || scalar(connection, """
                select count(*) from pg_constraint
                where conrelid='public.lead_visit_links'::regclass
                  and conname='uk_lead_visit_link_lead_user_listing'
                  and contype='u'
                """) != 1
                || scalar(connection, """
                select count(*) from pg_constraint
                where conrelid='public.lead_visit_links'::regclass
                  and conname='uk_lead_visit_link_lead'
                """) != 0) {
            throw refuse("V62 per-property visit-link schema differs");
        }
        String outboxConstraint = scalarString(connection, """
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid='public.lead_phone_delivery_outbox'::regclass
                  and conname='ck_lead_phone_outbox_state'
                """);
        if (!outboxConstraint.contains("'DELIVERED'")
                || !outboxConstraint.contains("'ACCEPTED'")) {
            throw refuse("V63 delivered outbox state constraint differs");
        }
        if (scalar(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public'
                  and c.relname in (
                    'lead_phone_challenges', 'lead_phone_delivery_outbox',
                    'lead_lessor_availability_enquiries', 'lead_visit_proposals',
                    'lead_visit_continuations', 'lead_visit_customer_responses',
                    'lead_visit_association_claims', 'lead_visit_links',
                    'lead_assistance_command_receipts')
                  and c.relname <> 'lead_phone_challenges'
                  and (
                    has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'SELECT')
                    or has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'INSERT')
                    or has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'UPDATE')
                    or has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'DELETE')
                  )
                """) != 0) {
            throw refuse("runtime privilege unexpectedly expanded to V61-V63 tables");
        }
        if (scalar(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relname='lead_phone_challenges'
                  and has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'SELECT')
                  and not has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'INSERT')
                  and not has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'UPDATE')
                  and not has_table_privilege('oe_dbf_runtime_cbb093e7', c.oid, 'DELETE')
                """) != 1) {
            throw refuse("runtime phone-proof read privilege differs from the minimum");
        }
        if (scalar(connection, """
                select count(*) from pg_proc p
                join pg_namespace n on n.oid=p.pronamespace
                where n.nspname='it_extensions'
                  and p.proname in ('similarity','similarity_op')
                  and has_function_privilege(
                        'oe_dbf_migrator_cbb093e7', p.oid, 'EXECUTE')
                  and not has_function_privilege(
                        'oe_dbf_runtime_cbb093e7', p.oid, 'EXECUTE')
                """) != 2) {
            throw refuse("extension execution privilege differs from the wrapper-only contract");
        }
        for (String index : new String[]{
                "idx_lead_phone_outbox_due",
                "idx_lead_availability_property_window",
                "uk_lead_phone_one_pending_generation",
                "idx_lead_visit_link_lead_history"
        }) {
            if (scalar(connection, "select count(*) from pg_indexes "
                    + "where schemaname='public' and indexname='" + index + "'") != 1) {
                throw refuse("required V61-V63 index is absent: " + index);
            }
        }
    }

    private static void verifyDigest(String resource, String expected) throws Exception {
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            if (!expected.equals(actual)) {
                throw refuse(resource + " source digest differs");
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static boolean tableExists(Connection connection, String relation)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "select to_regclass(?) is not null")) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("migration ledger query returned no row");
            return rows.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("migration identity query returned no row");
            return rows.getString(1);
        }
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("REFUSED DB-A01 Lead migration: " + message);
    }

    private static IllegalStateException refuse(String message, Exception cause) {
        return new IllegalStateException("REFUSED DB-A01 Lead migration: " + message, cause);
    }
}

