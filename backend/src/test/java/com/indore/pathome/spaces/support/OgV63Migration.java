package com.indore.pathome.spaces.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Frozen SQL only. A failed chain is preserved; this entry point never repairs or cleans. */
public final class OgV63Migration {
    private OgV63Migration() {}

    static void verifySources() throws Exception {
        FreshIntegrationSchemaHarness.verifySourcePins(new FreshIntegrationTarget.SourcePins(
                FreshIntegrationTarget.LEGACY_CORE_SHA256, FreshIntegrationTarget.MIGRATIONS_SHA256,
                FreshIntegrationTarget.V55_SHA256, FreshIntegrationTarget.V56_SHA256,
                FreshIntegrationTarget.V57_SHA256));
        var resources = Arrays.stream(new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql"))
                .sorted(Comparator.comparingInt(r -> Integer.parseInt(r.getFilename().split("__")[0].substring(1))))
                .toList();
        OgV63Target.require(resources.size() == 62, "migration count differs");
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < resources.size(); index++) {
            var resource = resources.get(index);
            OgV63Target.require(resource.getFilename().startsWith("V" + (index + 2) + "__"), "migration gap");
            try (var stream = resource.getInputStream()) {
                canonical.append(OgV63Target.digest(stream.readAllBytes())).append("  ")
                        .append(resource.getFilename()).append('\n');
            }
        }
        OgV63Target.require(OgV63Target.SOURCES.equals(OgV63Target.digest(canonical.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))), "migration manifest differs");
    }

    static Flyway flyway(OgV63Target.Target target, int ceiling, Callback... callbacks) {
        OgV63Target.require(OgV63Target.MIGRATOR.equals(target.role()) && (ceiling == 62 || ceiling == 63),
                "migration role/ceiling");
        return Flyway.configure().dataSource(OgV63Target.URL, target.role(), target.password())
                .schemas("public").defaultSchema("public").createSchemas(false)
                .baselineOnMigrate(false).baselineVersion("1").cleanDisabled(true)
                .outOfOrder(false).validateOnMigrate(true).target(Integer.toString(ceiling))
                .initSql("SET search_path TO public,pg_catalog; SET statement_timeout='30s'; SET lock_timeout='2s'")
                .locations("classpath:db/migration").callbacks(callbacks).load();
    }

    static void run() throws Exception {
        verifySources();
        OgV63Target.Target target = OgV63Target.requireTarget(OgV63Target.MIGRATOR, false);
        try (var lease = java.nio.channels.FileChannel.open(OgV63Target.ROOT.resolve("run.lease"),
                java.nio.file.StandardOpenOption.WRITE); var held = lease.tryLock()) {
            OgV63Target.require(held != null, "active test lease");
            try (Connection connection = target.open()) {
                requireInitialState(connection);
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                    try (var statement = connection.createStatement()) { statement.execute("select 1/0"); }
                    throw new IllegalStateException("baseline failure control did not fail");
                } catch (java.sql.SQLException expected) {
                    OgV63Target.require("22012".equals(expected.getSQLState()), "unexpected baseline failure");
                    connection.rollback();
                }
                requireInitialState(connection);
                System.out.println("I01 baseline injected failure 22012: full rollback verified");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                connection.commit();
                connection.setAutoCommit(true);
            }
            Flyway v62 = flyway(target, 62);
            v62.baseline();
            v62.migrate();
            verifyLedger(target, 62);
            String before;
            try (Connection connection = target.open()) { before = constraint(connection); }
            Callback injection = new Callback() {
                public boolean supports(Event event, Context context) {
                    return event == Event.AFTER_EACH_MIGRATE && context.getMigrationInfo() != null
                            && "63".equals(context.getMigrationInfo().getVersion().getVersion());
                }
                public boolean canHandleInTransaction(Event event, Context context) { return true; }
                public void handle(Event event, Context context) { throw new FlywayException("OG V63 intentional transactional rollback control"); }
                public String getCallbackName() { return "og-v63-transactional-negative-control"; }
            };
            boolean failed = false;
            try { flyway(target, 63, injection).migrate(); }
            catch (FlywayException expected) {
                failed = true;
                System.out.println("I10 expected V63 callback failure: " + expected.getClass().getSimpleName());
            }
            OgV63Target.require(failed, "V63 failure control did not fail");
            verifyLedger(target, 62);
            try (Connection connection = target.open()) {
                OgV63Target.require(before.equals(constraint(connection)), "V63 DDL rollback differs");
            }
            OgV63Target.require(!Files.exists(OgV63Target.ROOT.resolve("receipt.json")), "false successful receipt");
            System.out.println("I10 V62 history and original constraint retained; no receipt; retry authorized");
            Flyway finalFlyway = flyway(target, 63);
            finalFlyway.migrate();
            finalFlyway.validate();
            verifyLedger(target, 63);
            OgV63Target.require(finalFlyway.migrate().migrationsExecuted == 0, "no-op rerun migrated");
            try (Connection connection = target.open()) {
                OgV63Target.require(constraint(connection).contains("DELIVERED"), "V63 final constraint absent");
            }
            System.out.println("I01/I10 final history 63 successful entries; V63 retry and no-op validate/migrate PASS");
        }
    }

    static void requireInitialState(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relkind in ('r','p','S','v','m')
                and not exists (select 1 from pg_depend d where d.classid='pg_class'::regclass
                    and d.objid=c.oid and d.deptype='e')
                """)) {
            OgV63Target.require(rows.next() && rows.getInt(1) == 0, "unknown baseline state");
        }
    }

    static void verifyLedger(OgV63Target.Target target, int ceiling) throws Exception {
        try (Connection connection = target.open(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select version,type,success from public.flyway_schema_history order by installed_rank")) {
            for (int version = 1; version <= ceiling; version++) {
                OgV63Target.require(rows.next() && Integer.toString(version).equals(rows.getString(1))
                        && (version == 1 ? "BASELINE" : "SQL").equals(rows.getString(2))
                        && rows.getBoolean(3), "incomplete/failed migration ledger");
            }
            OgV63Target.require(!rows.next(), "unexpected migration ledger entry");
        }
    }

    private static String constraint(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid='public.lead_phone_delivery_outbox'::regclass and conname='ck_lead_phone_outbox_state'
                """)) {
            OgV63Target.require(rows.next(), "outbox state constraint absent"); return rows.getString(1);
        }
    }
}
