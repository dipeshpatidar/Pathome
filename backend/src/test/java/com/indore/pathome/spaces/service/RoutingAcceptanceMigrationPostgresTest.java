package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.support.RoutingAcceptanceContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PATHOME_OE_TEST_ACCEPTANCE", matches = "routing-20261023")
class RoutingAcceptanceMigrationPostgresTest {
    @Test
    void cleanInstallAndV53UpgradeRemainIsolatedAndSuccessful() throws Exception {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String cleanSchema = RoutingAcceptanceContract.SCHEMA + "_clean_" + suffix;
        String upgradeSchema = RoutingAcceptanceContract.SCHEMA + "_upgrade_" + suffix;
        try {
            createOwnedSchema(contract, cleanSchema);
            migrate(contract, cleanSchema, "54");
            assertEquals("54", highestVersion(contract, cleanSchema));
            assertEquals(0, count(contract, cleanSchema,
                    "select count(*) from visit_request_routing_tasks"));
            assertEquals(1, count(contract, cleanSchema,
                    "select count(*) from automatic_visit_routing_control where id = 1 and enabled = false"));
            assertEquals(0, count(contract, cleanSchema,
                    "select count(*) from flyway_schema_history where not success"));

            createOwnedSchema(contract, upgradeSchema);
            migrate(contract, upgradeSchema, "53");
            int citiesBefore = count(contract, upgradeSchema, "select count(*) from supported_cities");
            long historicalRequestId;
            try (Connection connection = migrator(contract);
                 Statement statement = connection.createStatement()) {
                statement.execute("set search_path to \"" + upgradeSchema + "\", public");
                statement.execute("insert into supported_cities (code, display_name, active) "
                        + "values ('routing-accept-" + suffix + "', 'Routing Accept " + suffix + "', true)");
                long tenantId = insertReturningId(statement,
                        "insert into users (email, full_name, role) values "
                                + "('routing-upgrade-" + suffix + "@pathome.test', "
                                + "'Routing Upgrade', 'ROLE_TENANT') returning id");
                long listingId = insertReturningId(statement,
                        "insert into listings (title, address, city, sector, latitude, longitude, bhk_count) "
                                + "values ('Historical Request " + suffix + "', 'Synthetic', "
                                + "'Routing Upgrade', 'Historical', 22.7, 75.8, '1BHK') returning id");
                historicalRequestId = insertReturningId(statement,
                        "insert into property_visit_requests "
                                + "(tenant_id, listing_id, status, preferred_visit_timing) values ("
                                + tenantId + ", " + listingId + ", 'RECEIVED', 'Historical') returning id");
            }
            migrate(contract, upgradeSchema, "latest");
            assertEquals("54", highestVersion(contract, upgradeSchema));
            assertEquals(citiesBefore + 1, count(contract, upgradeSchema,
                    "select count(*) from supported_cities"));
            assertEquals(0, count(contract, upgradeSchema,
                    "select count(*) from visit_request_routing_tasks"));
            assertEquals(1, count(contract, upgradeSchema,
                    "select count(*) from property_visit_requests where id = " + historicalRequestId
                            + " and version = 0 and status = 'RECEIVED'"));
            assertEquals(0, count(contract, upgradeSchema,
                    "select count(*) from visit_request_routing_tasks where request_id = "
                            + historicalRequestId));
            assertEquals(0, count(contract, upgradeSchema,
                    "select count(*) from flyway_schema_history where not success"));
        } finally {
            dropOwnedSchema(contract, cleanSchema);
            dropOwnedSchema(contract, upgradeSchema);
        }
    }

    @Test
    void prohibitedRuntimeDdlAndForeignSchemaAccessAreRefused() throws Exception {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
        RoutingAcceptanceContract.Credentials runtime = contract.runtimeCredentials();
        try (Connection connection = DriverManager.getConnection(
                runtime.jdbcUrl(), runtime.username(), runtime.password());
             Statement statement = connection.createStatement()) {
            assertFalse(canExecute(statement,
                    "create table " + RoutingAcceptanceContract.SCHEMA + ".runtime_must_not_create(id int)"));
            assertFalse(canExecute(statement,
                    "create table public.runtime_must_not_write_public(id int)"));
        }
        RoutingAcceptanceContract.Credentials migrator = contract.migratorCredentials();
        try (Connection connection = DriverManager.getConnection(
                migrator.jdbcUrl(), migrator.username(), migrator.password());
             Statement statement = connection.createStatement()) {
            assertFalse(canExecute(statement, "create schema routing_foreign_schema_probe"));
        }
    }

    private static void createOwnedSchema(RoutingAcceptanceContract contract, String schema)
            throws Exception {
        try (Connection admin = admin(contract); Statement statement = admin.createStatement()) {
            statement.execute("create schema \"" + schema + "\" authorization \""
                    + RoutingAcceptanceContract.MIGRATOR_USER + "\"");
            statement.execute("revoke all on schema \"" + schema + "\" from public");
        }
    }

    private static void dropOwnedSchema(RoutingAcceptanceContract contract, String schema) {
        try (Connection admin = admin(contract); Statement statement = admin.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        } catch (Exception ignored) {
            // The assertion or migration failure remains primary; cleanup is reported by the runner.
        }
    }

    private static void migrate(RoutingAcceptanceContract contract, String schema, String target)
            throws Exception {
        if (!"latest".equals(target) && !"53".equals(target)) {
            try (Connection connection = migrator(contract);
                 Statement statement = connection.createStatement()) {
                statement.execute("set search_path to \"" + schema + "\", public");
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            }
        } else if ("53".equals(target)) {
            try (Connection connection = migrator(contract);
                 Statement statement = connection.createStatement()) {
                statement.execute("set search_path to \"" + schema + "\", public");
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            }
        }
        var configuration = Flyway.configure()
                .dataSource(contract.jdbcUrl(), contract.migratorCredentials().username(),
                        contract.migratorCredentials().password())
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
        if (!"latest".equals(target)) configuration.target(target);
        configuration.load().migrate();
    }

    private static String highestVersion(RoutingAcceptanceContract contract, String schema)
            throws Exception {
        try (Connection admin = admin(contract); Statement statement = admin.createStatement()) {
            statement.execute("set search_path to \"" + schema + "\", public");
            try (ResultSet rows = statement.executeQuery(
                    "select version from flyway_schema_history where success order by installed_rank desc limit 1")) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static int count(RoutingAcceptanceContract contract, String schema, String sql)
            throws Exception {
        try (Connection admin = admin(contract); Statement statement = admin.createStatement()) {
            statement.execute("set search_path to \"" + schema + "\", public");
            try (ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static Connection admin(RoutingAcceptanceContract contract) throws Exception {
        return DriverManager.getConnection(contract.jdbcUrl(), contract.adminCredentials().username(),
                contract.adminCredentials().password());
    }

    private static Connection migrator(RoutingAcceptanceContract contract) throws Exception {
        return DriverManager.getConnection(contract.jdbcUrl(), contract.migratorCredentials().username(),
                contract.migratorCredentials().password());
    }

    private static long insertReturningId(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static boolean canExecute(Statement statement, String sql) {
        try {
            statement.execute(sql);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }
}
