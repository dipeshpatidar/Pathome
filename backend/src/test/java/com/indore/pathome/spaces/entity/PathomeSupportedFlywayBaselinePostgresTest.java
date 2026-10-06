package com.indore.pathome.spaces.entity;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.StaleObjectStateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the supported pre-Flyway version-1 legacy baseline through the current migration chain. */
@EnabledIfEnvironmentVariable(named = "PATHOME_PACKAGE5_FLYWAY_TEST", matches = "true")
class PathomeSupportedFlywayBaselinePostgresTest {
    private static final String LATEST_VERSION = "44";

    @Test
    void supportedLegacyBaselineUpgradesThroughLatestMigrationWithoutInventingOldOutcomes() throws Exception {
        String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
        String username = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
        String password = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
        String schema = "pathome_package5_flyway_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                assertEquals(0, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "preferred_tenant was introduced by V10 and must not exist in the V1 baseline");
                statement.execute("INSERT INTO users(email,full_name,role,free_visits_remaining) "
                        + "VALUES ('package5-legacy-tenant@example.test','Legacy Tenant','ROLE_TENANT',5)");
                statement.execute("INSERT INTO listings(title,address,city,sector,latitude,longitude,listing_type,status,property_type,bhk_count) "
                        + "VALUES ('Legacy listing','Legacy address','Legacy City','Central',22.0,75.0,'RENT','ACTIVE','FLAT','1BHK')");
                statement.execute("INSERT INTO rental_details(id,available_from) "
                        + "SELECT id,CURRENT_DATE FROM listings WHERE title='Legacy listing'");
                statement.execute("INSERT INTO property_media_assets(listing_id,room_tag,media_url) "
                        + "SELECT id,'GENERAL','https://example.test/legacy.jpg' FROM listings WHERE title='Legacy listing'");
                statement.execute("INSERT INTO localities(city,sector_name,created_at) "
                        + "VALUES (' Legacy City ','Unmapped Example Quarter',CURRENT_TIMESTAMP)");

                Flyway beforeV10 = flyway(url, username, password, schema).target("9").load();
                var preV10 = beforeV10.migrate();
                assertTrue(preV10.success);
                assertEquals("9", preV10.targetSchemaVersion.toString());
                assertEquals(0, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "the column must remain absent before V10 runs");

                Flyway throughBaseline32 = flyway(url, username, password, schema).target("32").load();
                var first = throughBaseline32.migrate();
                assertTrue(first.success);
                assertEquals("32", first.targetSchemaVersion.toString());
                assertEquals(1, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "V10 must add preferred_tenant to the supported legacy baseline");
                assertEquals(1, count(connection, "SELECT count(*) FROM listings WHERE title='Legacy listing' "
                        + "AND rental_mode='LONG_TERM_RENTAL' AND workflow_status='PUBLISHED'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM property_media_assets "
                        + "WHERE upload_request_id IS NULL AND room_tag='GENERAL'"));

                Flyway throughV40 = flyway(url, username, password, schema).target("40").load();
                var second = throughV40.migrate();
                assertTrue(second.success);
                assertEquals("40", second.targetSchemaVersion.toString());
                long tenantId = scalarLong(connection,
                        "SELECT id FROM users WHERE email='package5-legacy-tenant@example.test'");
                statement.execute("INSERT INTO visit_sessions(tenant_id,status,city) VALUES (" + tenantId
                        + ",'COMPLETED','Legacy City')");

                Flyway latest = flyway(url, username, password, schema).load();
                var third = latest.migrate();
                assertTrue(third.success);
                assertEquals(LATEST_VERSION, third.targetSchemaVersion.toString());
                assertDoesNotThrow(latest::validate);
                assertEquals(1, count(connection, "SELECT count(*) FROM visit_session_outcome_reports r "
                        + "JOIN visit_sessions s ON s.id=r.session_id WHERE s.status='COMPLETED' "
                        + "AND r.state='LEGACY_UNRECORDED' AND r.scope_source='LEGACY_COMPLETED'"));
                assertEquals(0, count(connection, "SELECT count(*) FROM visit_session_item_outcomes"));
                assertEquals(43, count(connection, "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE type='SQL' AND success AND version::integer BETWEEN 2 AND 44"));
                assertEquals("1", text(connection,
                        "SELECT version FROM flyway_schema_history WHERE type='BASELINE'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM visit_policy WHERE id=1"));
                assertPackage1aMigrationAndJpaBehavior(connection, url, username, password, schema);
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void assertPackage1aMigrationAndJpaBehavior(Connection connection, String url,
                                                                String username, String password,
                                                                String schema) throws Exception {
        assertEquals(3, count(connection, "SELECT count(*) FROM supported_cities"));
        assertEquals(24, count(connection, "SELECT count(*) FROM localities WHERE supported_city_id IS NOT NULL"));
        assertEquals(25, count(connection, "SELECT count(*) FROM localities"));
        assertEquals(1, count(connection, "SELECT count(*) FROM localities WHERE supported_city_id IS NULL"));
        assertEquals(" Legacy City ", text(connection, "SELECT city FROM localities "
                + "WHERE sector_name='Unmapped Example Quarter'"));
        assertEquals(" Legacy City ", text(connection, "SELECT city FROM localities "
                + "WHERE sector_name='Unmapped Example Quarter' AND supported_city_id IS NULL"));
        assertEquals(0, count(connection, "SELECT count(*) FROM localities l "
                + "JOIN supported_cities c ON c.id=l.supported_city_id "
                + "WHERE l.city <> c.display_name"));

        long indoreId = scalarLong(connection, "SELECT id FROM supported_cities WHERE code='indore'");
        long bhopalId = scalarLong(connection, "SELECT id FROM supported_cities WHERE code='bhopal'");
        long puneId = scalarLong(connection, "SELECT id FROM supported_cities WHERE code='pune'");
        assertThrows(SQLException.class, () -> execute(connection,
                "INSERT INTO supported_cities(code,display_name) VALUES ('indore','Duplicate code')"));

        execute(connection, "INSERT INTO supported_cities(code,display_name) "
                + "VALUES ('indore-central','Indore')");
        assertEquals(2, count(connection, "SELECT count(*) FROM supported_cities WHERE display_name='Indore'"));
        execute(connection, "INSERT INTO operating_teams(city_id,code,display_name) VALUES (" + indoreId
                + ",'north','North Team')");
        execute(connection, "INSERT INTO operating_teams(city_id,code,display_name) VALUES (" + bhopalId
                + ",'north','North Team')");
        assertEquals(2, count(connection, "SELECT count(*) FROM operating_teams WHERE code='north'"));
        assertThrows(SQLException.class, () -> execute(connection,
                "INSERT INTO operating_teams(city_id,code,display_name) VALUES (" + indoreId
                        + ",'north','Duplicate North Team')"));
        assertThrows(SQLException.class, () -> execute(connection,
                "INSERT INTO operating_teams(city_id,code,display_name) VALUES (9223372036854775807,'bad','Bad')"));
        assertThrows(SQLException.class, () -> execute(connection,
                "INSERT INTO localities(city,sector_name,supported_city_id) "
                        + "VALUES ('Unknown','Broken FK',9223372036854775807)"));
        execute(connection, "INSERT INTO localities(city,sector_name,supported_city_id) "
                + "VALUES ('Unknown','Unresolved Null FK',NULL)");
        assertEquals(1, count(connection, "SELECT count(*) FROM localities "
                + "WHERE sector_name='Unresolved Null FK' AND supported_city_id IS NULL"));

        verifyOptimisticVersionsAndMappings(url, username, password, schema, indoreId, bhopalId, puneId);
    }

    private static void verifyOptimisticVersionsAndMappings(String url, String username, String password,
                                                             String schema, long indoreId, long bhopalId,
                                                             long puneId) throws Exception {
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.connection.url", schemaUrl)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.default_schema", schema)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.show_sql", "false")
                .build();
        try (SessionFactory sessions = new MetadataSources(registry)
                .addAnnotatedClass(SupportedCity.class)
                .addAnnotatedClass(OperatingTeam.class)
                .addAnnotatedClass(Locality.class)
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(EmployeeProfile.class)
                .addAnnotatedClass(StaffAccessGrant.class)
                .addAnnotatedClass(OperationalAuditEvent.class)
                .buildMetadata()
                .buildSessionFactory()) {
            long teamIndoreId = persistTeam(sessions, indoreId, "south", "Indore South");
            persistTeam(sessions, puneId, "north", "Pune North");
            long localityId = persistLocality(sessions, bhopalId);
            try (Session session = sessions.openSession()) {
                Locality linked = session.find(Locality.class, localityId);
                assertEquals("Bhopal", linked.getCity());
                assertEquals(bhopalId, linked.getSupportedCity().getId());
            }
            long initialCityVersion = readCityVersion(sessions, indoreId);
            try (Session session = sessions.openSession()) {
                Transaction tx = session.beginTransaction();
                SupportedCity city = session.find(SupportedCity.class, indoreId);
                city.setActive(false);
                tx.commit();
                assertEquals(initialCityVersion + 1, city.getVersion());
            }
            try (Session session = sessions.openSession()) {
                Transaction tx = session.beginTransaction();
                SupportedCity updated = session.find(SupportedCity.class, indoreId);
                assertFalse(updated.isActive());
                updated.setActive(true);
                tx.commit();
                assertTrue(updated.isActive());
            }
            assertStaleCityUpdateRejected(sessions, indoreId);
            assertStaleTeamUpdateRejected(sessions, teamIndoreId);
            assertEquals(0, readCityVersion(sessions, bhopalId));
            assertCanonicalCityCodeCannotBeChangedThroughEntityApi();
            assertTeamCityAssociationCannotBeChangedThroughEntityApi();
            verifyStaffAccessMappingsAndVersions(sessions,
                    connectionFor(url, username, password, schema), teamIndoreId);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static long persistTeam(SessionFactory sessions, long cityId, String code, String displayName) {
        try (Session session = sessions.openSession()) {
            Transaction tx = session.beginTransaction();
            SupportedCity city = session.find(SupportedCity.class, cityId);
            OperatingTeam team = new OperatingTeam(city, code, displayName, true);
            session.persist(team);
            tx.commit();
            return team.getId();
        }
    }

    private static long persistLocality(SessionFactory sessions, long cityId) {
        try (Session session = sessions.openSession()) {
            Transaction tx = session.beginTransaction();
            SupportedCity city = session.find(SupportedCity.class, cityId);
            Locality locality = new Locality("Bhopal", "Package 1A ORM Link", null, null, null);
            locality.setSupportedCity(city);
            session.persist(locality);
            tx.commit();
            return locality.getId();
        }
    }

    private static long readCityVersion(SessionFactory sessions, long cityId) {
        try (Session session = sessions.openSession()) {
            return session.find(SupportedCity.class, cityId).getVersion();
        }
    }

    private static void assertStaleCityUpdateRejected(SessionFactory sessions, long cityId) {
        try (Session first = sessions.openSession(); Session stale = sessions.openSession()) {
            Transaction firstTx = first.beginTransaction();
            Transaction staleTx = stale.beginTransaction();
            SupportedCity current = first.find(SupportedCity.class, cityId);
            SupportedCity outdated = stale.find(SupportedCity.class, cityId);
            long originalVersion = current.getVersion();
            current.setDisplayName("Indore Updated");
            outdated.setDisplayName("Indore Stale");
            firstTx.commit();
            assertEquals(originalVersion + 1, current.getVersion());
            RuntimeException rejected = assertThrows(RuntimeException.class, staleTx::commit);
            assertTrue(hasStaleObjectCause(rejected), "stale City update must fail through optimistic locking");
            if (staleTx.isActive()) staleTx.rollback();
        }
    }

    private static void assertStaleTeamUpdateRejected(SessionFactory sessions, long teamId) {
        try (Session first = sessions.openSession(); Session stale = sessions.openSession()) {
            Transaction firstTx = first.beginTransaction();
            Transaction staleTx = stale.beginTransaction();
            OperatingTeam current = first.find(OperatingTeam.class, teamId);
            OperatingTeam outdated = stale.find(OperatingTeam.class, teamId);
            long originalVersion = current.getVersion();
            current.setDisplayName("Indore North Updated");
            outdated.setDisplayName("Indore North Stale");
            firstTx.commit();
            assertEquals(originalVersion + 1, current.getVersion());
            RuntimeException rejected = assertThrows(RuntimeException.class, staleTx::commit);
            assertTrue(hasStaleObjectCause(rejected), "stale Team update must fail through optimistic locking");
            if (staleTx.isActive()) staleTx.rollback();
        }
    }

    private static boolean hasStaleObjectCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof StaleObjectStateException
                    || cause instanceof jakarta.persistence.OptimisticLockException) return true;
        }
        return false;
    }

    private static void assertTeamCityAssociationCannotBeChangedThroughEntityApi() {
        try {
            var cityField = OperatingTeam.class.getDeclaredField("city");
            assertFalse(cityField.getAnnotation(jakarta.persistence.JoinColumn.class).updatable());
            assertThrows(NoSuchMethodException.class,
                    () -> OperatingTeam.class.getMethod("setCity", SupportedCity.class));
        } catch (NoSuchFieldException error) {
            fail(error);
        }
    }

    private static void assertCanonicalCityCodeCannotBeChangedThroughEntityApi() {
        try {
            var codeField = SupportedCity.class.getDeclaredField("code");
            assertFalse(codeField.getAnnotation(jakarta.persistence.Column.class).updatable());
            assertThrows(NoSuchMethodException.class,
                    () -> SupportedCity.class.getMethod("setCode", String.class));
        } catch (NoSuchFieldException error) {
            fail(error);
        }
    }

    private static Connection connectionFor(String url, String username, String password, String schema)
            throws SQLException {
        Connection connection = DriverManager.getConnection(url, username, password);
        connection.setSchema(schema);
        connection.setAutoCommit(true);
        return connection;
    }

    private static void verifyStaffAccessMappingsAndVersions(SessionFactory sessions, Connection connection,
                                                              long teamId) throws Exception {
        try (connection; Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO users(email, full_name, role, free_visits_remaining) "
                    + "VALUES ('package1b-staff@example.test', NULL, 'ROLE_TENANT', 0)");
            long userId = scalarLong(connection,
                    "SELECT id FROM users WHERE email='package1b-staff@example.test'");
            statement.execute("INSERT INTO employee_profiles(user_id) VALUES (" + userId + ")");
            assertEquals(1, count(connection, "SELECT count(*) FROM employee_profiles WHERE user_id=" + userId
                    + " AND staff_active=FALSE AND version=0 AND role_type IS NULL AND base_salary IS NULL"));

            long versionProfileId = scalarLong(connection,
                    "SELECT id FROM employee_profiles WHERE user_id=" + userId);
            assertEquals(0, readEmployeeVersion(sessions, versionProfileId));
            try (Session session = sessions.openSession()) {
                Transaction tx = session.beginTransaction();
                EmployeeProfile profile = session.find(EmployeeProfile.class, versionProfileId);
                profile.setStaffActive(true);
                profile.setStaffActivatedAt(java.time.Instant.now());
                tx.commit();
                assertEquals(1, profile.getVersion());
            }
            assertStaleEmployeeProfileUpdateRejected(sessions, versionProfileId);

            String grant = "INSERT INTO staff_access_grants(user_id, capability, scope_type, effective_at, "
                    + "grant_reason_code, provisioning_source) VALUES (" + userId
                    + ",'STAFF_ADMIN','GLOBAL','2025-01-01T00:00:00Z','TEST_GRANT','INITIAL_BOOTSTRAP')";
            statement.execute(grant);
            assertThrows(SQLException.class, () -> statement.execute(grant));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO staff_access_grants(user_id, "
                    + "capability, scope_type, city_id, effective_at, grant_reason_code, provisioning_source) "
                    + "VALUES (" + userId + ",'OPS_INTAKE','CITY',NULL,CURRENT_TIMESTAMP,'TEST_GRANT','ADMIN_API')"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO staff_access_grants(user_id, "
                    + "capability, scope_type, effective_at, expires_at, grant_reason_code, provisioning_source) "
                    + "VALUES (" + userId + ",'STAFF_ADMIN','GLOBAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 day',"
                    + "'TEST_GRANT','ADMIN_API')"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO staff_access_grants(user_id, "
                    + "capability, scope_type, city_id, team_id, effective_at, grant_reason_code, provisioning_source) "
                    + "VALUES (" + userId + ",'OPS_INTAKE','CITY',1," + teamId + ",CURRENT_TIMESTAMP,'TEST_GRANT','ADMIN_API')"));

            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO operational_audit_events "
                    + "(actor_kind, action_code, target_type, target_id, reason_code) "
                    + "VALUES ('USER','STAFF_ACTIVATED','USER'," + userId + ",'TEST_REASON')"));
            statement.execute("INSERT INTO operational_audit_events "
                    + "(actor_kind, operator_reference, action_code, target_type, target_id, reason_code, details) "
                    + "VALUES ('DEPLOYMENT_OPERATOR','DEPLOYMENT_CHANGE_1','INITIAL_ADMIN_BOOTSTRAPPED',"
                    + "'USER'," + userId + ",'INITIAL_PROVISIONING','{}'::jsonb)");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE operational_audit_events "
                    + "SET reason_code='CHANGED' WHERE operator_reference='DEPLOYMENT_CHANGE_1'"));
            assertThrows(SQLException.class, () -> statement.execute("UPDATE staff_access_grants "
                    + "SET capability='OPS_INTAKE' WHERE user_id=" + userId));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM staff_access_grants WHERE user_id=" + userId));
        }
    }

    private static long readEmployeeVersion(SessionFactory sessions, long profileId) {
        try (Session session = sessions.openSession()) {
            return session.find(EmployeeProfile.class, profileId).getVersion();
        }
    }

    private static void assertStaleEmployeeProfileUpdateRejected(SessionFactory sessions, long profileId) {
        try (Session currentSession = sessions.openSession(); Session staleSession = sessions.openSession()) {
            Transaction currentTx = currentSession.beginTransaction();
            Transaction staleTx = staleSession.beginTransaction();
            EmployeeProfile current = currentSession.find(EmployeeProfile.class, profileId);
            EmployeeProfile stale = staleSession.find(EmployeeProfile.class, profileId);
            current.setStaffActive(false);
            current.setStaffDeactivatedAt(java.time.Instant.now());
            stale.setStaffActive(false);
            stale.setStaffDeactivatedAt(java.time.Instant.now());
            currentTx.commit();
            assertEquals(2, current.getVersion());
            RuntimeException rejected = assertThrows(RuntimeException.class, staleTx::commit);
            assertTrue(hasStaleObjectCause(rejected), "stale staff-state update must fail optimistically");
            if (staleTx.isActive()) staleTx.rollback();
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static FluentConfiguration flyway(String url, String username, String password, String schema) {
        return Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1");
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
