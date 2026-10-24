package com.indore.pathome.spaces.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.LeadLegacyProvenance;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.LocationResolution;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.LeadPreferredLocalityRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import com.indore.pathome.spaces.support.RunOwnedSchemaDataSource;
import com.indore.pathome.spaces.support.OwnedTestSchemaRole;
import org.flywaydb.core.Flyway;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disposable HTTP/PostgreSQL witnesses for D-B ownership and pre-mutation validation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p4-crm-http-test-jwt-secret-32-bytes",
        "cloudinary.api-key=p4-crm-test-key",
        "cloudinary.api-secret=p4-crm-test-secret",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "meta.webhook.app-secret=p4-crm-meta-test-secret",
        "meta.webhook.verify-token=p4-crm-meta-verify-token",
        "meta.webhook.page-id=page-101",
        "meta.webhook.form-id=form-202",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class LeadHttpPostgresTest {
    private static final String SCHEMA_PREFIX = "p4_crm_http_test";
    private static String schema;
    private static final UUID LEGACY_SOURCE_ID = UUID.randomUUID();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static DisposableIntegrationTarget.Target target;
    private static OwnedTestSchemaRole suiteRole;
    private static HikariDataSource ownedPool;
    private static boolean schemaCreated;

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        SqlCapture sqlCapture() {
            return new SqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer sqlCaptureCustomizer(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (StatementInspector) statement -> {
                        capture.add(statement);
                        return statement;
                    });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class R05InstrumentationConfiguration {
        @Bean
        @Primary
        TestJdbcTemplate r05JdbcTemplate(DataSource dataSource) {
            return new TestJdbcTemplate(dataSource);
        }

        @Bean
        @Primary
        MutableDatabaseClock r05DatabaseClock(TestJdbcTemplate jdbcTemplate) {
            return new MutableDatabaseClock(jdbcTemplate);
        }

        @Bean
        @Primary
        UserRepository r05FaultInjectingUserRepository(
                @Qualifier("userRepository") UserRepository delegate) {
            return (UserRepository) Proxy.newProxyInstance(
                    UserRepository.class.getClassLoader(),
                    new Class<?>[]{UserRepository.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(delegate, arguments);
                        }
                        if ("existsByMobileNumberNormalized".equals(method.getName())
                                && FAIL_ACCOUNT_INDICATOR.compareAndSet(true, false)) {
                            throw new DataAccessResourceFailureException(
                                    "R05 account-indicator failure injection");
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SourceClientConfiguration {
        @Bean
        @Primary
        TestMetaLeadSourceClient sourceClient() {
            return TEST_SOURCE;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RunOwnedDataSourceConfiguration {
        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setPoolName("p4-run-owned-schema");
            OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
            config.setJdbcUrl(database.jdbcUrl());
            config.setUsername(database.username());
            config.setPassword(database.password());
            config.setMaximumPoolSize(6);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5000);
            ownedPool = new HikariDataSource(config);
            return new RunOwnedSchemaDataSource(ownedPool,
                    target.database(), schema);
        }
    }

    static final class TestMetaLeadSourceClient implements MetaLeadSourceClient {
        private final AtomicBoolean configured = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch firstCallStarted;
        private volatile CountDownLatch releaseFirstCall;
        private volatile String firstPayload;
        private volatile String laterPayload;
        private volatile RuntimeException failure;

        void configure(String first, String later, CountDownLatch started, CountDownLatch release) {
            configured.set(true);
            calls.set(0);
            firstPayload = first;
            laterPayload = later;
            firstCallStarted = started;
            releaseFirstCall = release;
        }

        void reset() {
            configured.set(false);
            calls.set(0);
            firstPayload = null;
            laterPayload = null;
            failure = null;
            firstCallStarted = null;
            releaseFirstCall = null;
        }

        void configureFailure(RuntimeException failure) {
            reset();
            configured.set(true);
            this.failure = failure;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public boolean isConfigured() {
            return configured.get();
        }

        @Override
        public String fetchLeadPayload(String pageId, String leadgenId, String formId) {
            if (!configured.get()) {
                throw new LeadDataProtectionUnavailableException(
                        "Controlled source adapter is not configured for this test");
            }
            if (calls.incrementAndGet() == 1) {
                CountDownLatch started = firstCallStarted;
                CountDownLatch release = releaseFirstCall;
                if (started != null) started.countDown();
                try {
                    if (release != null && !release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Controlled source call was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Controlled source call was interrupted", interrupted);
                }
                if (failure != null) {
                    throw failure;
                }
                return firstPayload;
            }
            if (failure != null) {
                throw failure;
            }
            return laterPayload;
        }
    }

    private static final TestMetaLeadSourceClient TEST_SOURCE = new TestMetaLeadSourceClient();
    private static final AtomicBoolean FAIL_ACCOUNT_INDICATOR = new AtomicBoolean();

    static class TestJdbcTemplate extends JdbcTemplate {
        private final AtomicBoolean failCount = new AtomicBoolean();
        private final AtomicBoolean failInsert = new AtomicBoolean();
        private final List<RealSqlFailure> realSqlFailures =
                Collections.synchronizedList(new ArrayList<>());
        private final Set<Long> captureInsertFailureActors = ConcurrentHashMap.newKeySet();
        private volatile Long failureActorId;
        private volatile Set<Long> barrierActorIds = Set.of();
        private volatile CountDownLatch counted;
        private volatile CountDownLatch releaseCounts;
        private volatile Long guardBarrierActorId;
        private volatile CountDownLatch guardReached;
        private volatile CountDownLatch releaseGuard;
        private volatile Long insertBarrierActorId;
        private volatile CountDownLatch insertReached;
        private volatile CountDownLatch releaseInsert;

        TestJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        void failCountFor(Long actorId) {
            failureActorId = actorId;
            failCount.set(true);
        }

        void failInsertFor(Long actorId) {
            failureActorId = actorId;
            failInsert.set(true);
        }

        void armCountBarrier(Set<Long> actorIds, int expectedRequests) {
            barrierActorIds = Set.copyOf(actorIds);
            counted = new CountDownLatch(expectedRequests);
            releaseCounts = new CountDownLatch(1);
        }

        void awaitCountBarrier() throws InterruptedException {
            CountDownLatch reached = counted;
            if (reached != null && !reached.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Both duplicate lookups did not reach COUNT");
            }
        }

        void releaseCountBarrier() {
            CountDownLatch release = releaseCounts;
            if (release != null) release.countDown();
        }

        void armLookupActorLockBarrier(Long actorId) {
            guardBarrierActorId = actorId;
            guardReached = new CountDownLatch(1);
            releaseGuard = new CountDownLatch(1);
        }

        void awaitLookupActorLockBarrier() throws InterruptedException {
            CountDownLatch reached = guardReached;
            if (reached != null && !reached.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("The admitted duplicate lookup did not reach its actor guard");
            }
        }

        void releaseLookupActorLockBarrier() {
            CountDownLatch release = releaseGuard;
            if (release != null) release.countDown();
        }

        void armLookupInsertBarrier(Long actorId) {
            insertBarrierActorId = actorId;
            insertReached = new CountDownLatch(1);
            releaseInsert = new CountDownLatch(1);
        }

        void awaitLookupInsertBarrier() throws InterruptedException {
            CountDownLatch reached = insertReached;
            if (reached != null && !reached.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("The duplicate lookup did not insert its admission event");
            }
        }

        void releaseLookupInsertBarrier() {
            CountDownLatch release = releaseInsert;
            if (release != null) release.countDown();
        }

        void captureRealInsertFailures(Long... actorIds) {
            captureInsertFailureActors.addAll(List.of(actorIds));
        }

        List<RealSqlFailure> realSqlFailures() {
            synchronized (realSqlFailures) {
                return List.copyOf(realSqlFailures);
            }
        }

        void reset() {
            failCount.set(false);
            failInsert.set(false);
            failureActorId = null;
            barrierActorIds = Set.of();
            counted = null;
            releaseCounts = null;
            guardBarrierActorId = null;
            guardReached = null;
            releaseGuard = null;
            insertBarrierActorId = null;
            insertReached = null;
            releaseInsert = null;
            realSqlFailures.clear();
            captureInsertFailureActors.clear();
            FAIL_ACCOUNT_INDICATOR.set(false);
        }

        private boolean matchesLookupActor(Object[] arguments) {
            if (failureActorId == null || arguments == null || arguments.length == 0) return false;
            Object actor = arguments[0];
            return actor instanceof Number number && failureActorId.equals(number.longValue());
        }

        private boolean matchesBarrierActor(Object[] arguments) {
            if (arguments == null || arguments.length == 0 || barrierActorIds.isEmpty()) return false;
            Object actor = arguments[0];
            return actor instanceof Number number && barrierActorIds.contains(number.longValue());
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            if (sql.contains("lead_lookup_events") && matchesLookupActor(arguments)
                    && failCount.compareAndSet(true, false)) {
                throw new DataAccessResourceFailureException("R05 COUNT failure injection");
            }
            T result = super.queryForObject(sql, requiredType, arguments);
            if (sql.contains("lead_lookup_events") && matchesBarrierActor(arguments)) {
                CountDownLatch reached = counted;
                CountDownLatch release = releaseCounts;
                if (reached != null && release != null) {
                    reached.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("R05 COUNT barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("R05 COUNT barrier was interrupted", interrupted);
                    }
                }
            }
            return result;
        }

        @Override
        public int update(String sql, Object... arguments) {
            Long actorId = lookupActor(arguments);
            boolean captureFailure = sql.contains("insert into lead_lookup_events")
                    && actorId != null && captureInsertFailureActors.contains(actorId);
            int backendPid = captureFailure
                    ? super.queryForObject("select pg_backend_pid()", Integer.class)
                    : -1;
            try {
                if (sql.contains("insert into lead_lookup_events")
                        && matchesLookupActor(arguments) && failInsert.compareAndSet(true, false)) {
                    return super.update(sql, arguments[0], "not-a-timestamp");
                }
                int updated = super.update(sql, arguments);
                if (sql.contains("insert into lead_lookup_events")
                        && matchesInsertBarrierActor(arguments)) {
                    CountDownLatch reached = insertReached;
                    CountDownLatch release = releaseInsert;
                    if (reached != null && release != null) {
                        insertBarrierActorId = null;
                        reached.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("Duplicate lookup insert barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Duplicate lookup insert barrier was interrupted",
                                    interrupted);
                        }
                    }
                }
                return updated;
            } catch (RuntimeException failure) {
                if (captureFailure) {
                    SqlError error = sqlError(failure);
                    realSqlFailures.add(new RealSqlFailure(actorId, backendPid,
                            error == null ? null : error.sqlState(),
                            error == null ? null : error.message()));
                }
                throw failure;
            }
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... arguments) {
            List<T> result = super.queryForList(sql, elementType, arguments);
            if (sql.contains("from employee_profiles")
                    && matchesGuardActor(arguments)) {
                CountDownLatch reached = guardReached;
                CountDownLatch release = releaseGuard;
                if (reached != null && release != null) {
                    guardBarrierActorId = null;
                    reached.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Duplicate lookup actor-lock barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Duplicate lookup actor-lock barrier was interrupted",
                                interrupted);
                    }
                }
            }
            return result;
        }

        private boolean matchesGuardActor(Object[] arguments) {
            if (guardBarrierActorId == null || arguments == null || arguments.length == 0) return false;
            Object actor = arguments[0];
            return actor instanceof Number number && guardBarrierActorId.equals(number.longValue());
        }

        private boolean matchesInsertBarrierActor(Object[] arguments) {
            if (insertBarrierActorId == null || arguments == null || arguments.length == 0) return false;
            Object actor = arguments[0];
            return actor instanceof Number number && insertBarrierActorId.equals(number.longValue());
        }

        private Long lookupActor(Object[] arguments) {
            if (arguments == null || arguments.length == 0) return null;
            Object actor = arguments[0];
            return actor instanceof Number number ? number.longValue() : null;
        }
    }

    static class MutableDatabaseClock extends DatabaseClock {
        private volatile Instant fixedNow;

        MutableDatabaseClock(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        void fixAt(Instant instant) {
            fixedNow = instant;
        }

        void useDatabaseClock() {
            fixedNow = null;
        }

        @Override
        public Instant now() {
            Instant fixed = fixedNow;
            return fixed == null ? super.now() : fixed;
        }
    }

    static final class SqlCapture {
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        void add(String statement) {
            if (statement != null && !statement.isBlank()) {
                statements.add(statement);
            }
        }

        void clear() {
            statements.clear();
        }

        List<String> snapshot() {
            synchronized (statements) {
                return List.copyOf(statements);
            }
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        target = DisposableIntegrationTarget.verifyFromEnvironment();
        suiteRole = OwnedTestSchemaRole.provision(target, SCHEMA_PREFIX);
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        schema = database.schema();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertEquals(target.database(), currentDatabase(connection));
            schemaCreated = true;
            try {
                connection.setSchema(schema);
                DataSource guardedSetup = guardedSetup();
                try (Connection guarded = guardedSetup.getConnection()) {
                    ScriptUtils.executeSqlScript(guarded,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                }
                flyway().target("52").load().migrate();
                statement.execute("insert into lead_source_events(id,provider,provider_account_id,"
                        + "external_event_id,event_kind,payload_hash,processing_state,attempts,"
                        + "available_at,received_at,updated_at,error_code) values ('" + LEGACY_SOURCE_ID
                        + "','META_LEADGEN','legacy-page','legacy-event','META_LEADGEN','"
                        + "a".repeat(64) + "','QUARANTINED',0,now(),now(),now(),"
                        + "'SOURCE_ACTIVATION_NOT_APPROVED')");
                flyway().load().migrate();
                statement.executeUpdate("update lead_source_events set provider_form_id = 'legacy-form' "
                        + "where id = '" + LEGACY_SOURCE_ID + "'::uuid");
                statement.execute("create table " + schema + ".share_event_admission_witness ("
                        + "event_id uuid primary key, inserted_at timestamptz not null)");
                statement.execute("create table " + schema + ".share_event_admission_gate ("
                        + "id integer primary key)");
                statement.execute("insert into " + schema
                        + ".share_event_admission_gate(id) values (1)");
            } catch (Exception failure) {
                if (suiteRole != null) suiteRole.close();
                throw failure;
            }
        }
        registry.add("spring.datasource.url", database::schemaUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }

    @AfterAll
    static void dropSchemaAfterSuite() throws Exception {
        if (ownedPool != null) ownedPool.close();
        if (suiteRole != null) suiteRole.close();
        schemaCreated = false;
    }

    private static Connection connect() throws Exception {
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static Connection schemaConnection() throws Exception {
        return guardedSetup().getConnection();
    }

    private static DataSource guardedSetup() {
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                database.jdbcUrl(), database.username(), database.password());
        return new RunOwnedSchemaDataSource(delegate, target.database(), schema);
    }

    private static DataSource guardedFlyway() {
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                database.jdbcUrl(), database.username(), database.password());
        return RunOwnedSchemaDataSource.forFrameworkBootstrap(
                delegate, target.database(), schema);
    }

    private static String currentDatabase(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String schemaUrl() {
        return suiteRole.credentials().schemaUrl();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(guardedFlyway())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository profiles;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private LeadPreferredLocalityRepository preferredLocalities;
    @Autowired private LeadRoutingQueueRepository leads;
    @Autowired private ListingRepository listings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestJdbcTemplate testJdbc;
    @Autowired private MutableDatabaseClock testClock;
    @Autowired private SqlCapture sqlCapture;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LeadService leadService;
    @Autowired private LeadDataProtection protection;
    @Autowired private MetaLeadInboxService metaLeadInbox;
    @LocalServerPort private int port;

    @BeforeEach
    void requireDisposableTestSchema() {
        assertEquals(schema, jdbc.queryForObject("select current_schema()", String.class),
                "The application datasource must stay inside this test's disposable schema");
    }

    @Test
    void sameTeamCoordinatorCannotReadAnotherOwnersDetailActivityOrCount() throws Exception {
        SupportedCity city = city("same-team");
        OperatingTeam team = team(city, "same-team");
        Actor owner = coordinator("same-team-owner", team);
        Actor peer = coordinator("same-team-peer", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "owner-private");

        assertEquals(200, send("GET", "/api/v1/operations/leads/" + lead.getId(),
                owner.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/leads/" + lead.getId(),
                peer.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/leads/" + lead.getId()
                + "/activities", peer.token(), null).statusCode());
        HttpResponse<String> peerResponse = send("GET", "/api/v1/operations/leads",
                peer.token(), null);
        assertEquals(200, peerResponse.statusCode(), peerResponse.body());
        assertEquals("no-store", peerResponse.headers().firstValue("Cache-Control").orElse(""));
        JsonNode peerPage = mapper.readTree(peerResponse.body());
        assertEquals(0, peerPage.path("totalElements").asInt());
        HttpResponse<String> ownerResponse = send("GET", "/api/v1/operations/leads",
                owner.token(), null);
        assertEquals(200, ownerResponse.statusCode(), ownerResponse.body());
        JsonNode ownerPage = mapper.readTree(ownerResponse.body());
        assertEquals(1, ownerPage.path("totalElements").asInt());
    }

    @Test
    void fullySpecifiedVisibleLeadFiltersReturnOnlyTheOwnerPage() throws Exception {
        SupportedCity city = city("filtered-list");
        OperatingTeam team = team(city, "filtered-list");
        Actor owner = coordinator("filtered-list-owner", team);
        Actor peer = coordinator("filtered-list-peer", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "filtered-list");
        lead.setFollowUpAt(Instant.now().plusSeconds(3600));
        lead.setFollowUpZoneId("Asia/Kolkata");
        leads.saveAndFlush(lead);
        String path = "/api/v1/operations/leads?cityId=" + city.getId()
                + "&teamId=" + team.getId()
                + "&status=NEW&owner=me&followUpBefore=2100-01-01T00:00:00Z";

        HttpResponse<String> ownerResponse = send("GET", path, owner.token(), null);
        assertEquals(200, ownerResponse.statusCode(), ownerResponse.body());
        JsonNode ownerPage = mapper.readTree(ownerResponse.body());
        assertEquals(1, ownerPage.path("totalElements").asInt());
        assertEquals(lead.getId(), ownerPage.path("items").get(0).path("id").asLong());

        HttpResponse<String> peerResponse = send("GET", path, peer.token(), null);
        assertEquals(200, peerResponse.statusCode(), peerResponse.body());
        JsonNode peerPage = mapper.readTree(peerResponse.body());
        assertEquals(0, peerPage.path("totalElements").asInt());
    }

    @Test
    void approvedQueueSearchUsesScopedNameReferenceLocalityAndExactPhoneSuffix() throws Exception {
        SupportedCity city = city("queue-search");
        OperatingTeam team = team(city, "queue-search");
        Actor owner = coordinator("queue-search-owner", team);
        Actor peer = coordinator("queue-search-peer", team);
        LeadRoutingQueue named = matchingLead(city, team, owner.user(), "Green Park Customer",
                "+919800001234", LeadStatus.NEW);
        LeadRoutingQueue localityMatch = matchingLead(city, team, owner.user(), "Other Customer",
                "+919800005678", LeadStatus.NEW);
        LeadRoutingQueue foreign = matchingLead(city, team, peer.user(), "Green Park Foreign",
                "+919800009999", LeadStatus.NEW);
        Locality greenPark = locality(city, "green-park");
        greenPark.setSectorName("Green Park Extension");
        localities.saveAndFlush(greenPark);
        preferredLocalities.saveAndFlush(new com.indore.pathome.spaces.entity.LeadPreferredLocality(
                localityMatch, greenPark));

        assertVisiblePage(search(owner, city, team, "  green   park  ", null, null, 0, 20),
                List.of(localityMatch.getId(), named.getId()), 20, 0);
        assertVisiblePage(search(owner, city, team, "LD-" + named.getId(), null, null, 0, 20),
                List.of(named.getId()), 20, 0);
        assertVisiblePage(search(owner, city, team, "Lead #" + named.getId(), null, null, 0, 20),
                List.of(named.getId()), 20, 0);
        assertVisiblePage(search(owner, city, team, "1234", null, null, 0, 20),
                List.of(named.getId()), 20, 0);
        assertVisiblePage(search(owner, city, team, "123", null, null, 0, 20),
                List.of(), 20, 0);
        assertVisiblePage(search(owner, city, team, "%", null, null, 0, 20),
                List.of(), 20, 0);
        assertVisiblePage(search(peer, city, team, "Green Park", null, null, 0, 20),
                List.of(foreign.getId()), 20, 0);
        assertEquals(400, send("GET", "/api/v1/operations/leads?q=" + "x".repeat(81),
                owner.token(), null).statusCode());
    }

    @Test
    void approvedFollowUpFiltersShareOneCapturedInstantAndRespectLocalDayBoundaries()
            throws Exception {
        SupportedCity city = city("follow-up-filter");
        OperatingTeam team = team(city, "follow-up-filter");
        Actor owner = coordinator("follow-up-filter-owner", team);
        Instant now = Instant.parse("2026-10-22T04:30:00.123456Z");
        testClock.fixAt(now);
        try {
            LeadRoutingQueue earlierToday = seedLead(city, team, owner.user(), "earlier-today",
                    LeadStatus.NEW, Instant.parse("2026-10-21T20:00:00Z"));
            LeadRoutingQueue equalNow = seedLead(city, team, owner.user(), "equal-now",
                    LeadStatus.NEW, now);
            LeadRoutingQueue laterToday = seedLead(city, team, owner.user(), "later-today",
                    LeadStatus.NEW, Instant.parse("2026-10-22T10:00:00Z"));
            LeadRoutingQueue tomorrow = seedLead(city, team, owner.user(), "tomorrow",
                    LeadStatus.NEW, Instant.parse("2026-10-23T10:00:00Z"));
            LeadRoutingQueue noFollowUp = seedLead(city, team, owner.user(), "no-follow-up",
                    LeadStatus.NEW, null);

            assertVisiblePage(search(owner, city, team, null, "OVERDUE", "Asia/Kolkata", 0, 20),
                    List.of(earlierToday.getId()), 20, 0);
            assertVisiblePage(search(owner, city, team, null, "TODAY", "Asia/Kolkata", 0, 20),
                    List.of(laterToday.getId(), equalNow.getId(), earlierToday.getId()), 20, 0);
            assertVisiblePage(search(owner, city, team, null, "NONE", "Asia/Kolkata", 0, 20),
                    List.of(noFollowUp.getId()), 20, 0);
            assertVisiblePage(search(owner, city, team, null, "TODAY", "America/St_Johns", 0, 20),
                    List.of(laterToday.getId(), equalNow.getId()), 20, 0);
            assertEquals(200, send("GET", "/api/v1/operations/leads?q=" + "x".repeat(80),
                    owner.token(), null).statusCode());
            assertTrue(tomorrow.getId() > 0);

            assertEquals(400, send("GET", "/api/v1/operations/leads?followUp=TODAY",
                    owner.token(), null).statusCode());
            assertEquals(400, send("GET", "/api/v1/operations/leads?followUp=TODAY"
                    + "&followUpZone=Not/AZone", owner.token(), null).statusCode());
        } finally {
            testClock.useDatabaseClock();
        }
    }

    @Test
    void allNullableFilterMasksExecuteScopedSelectAndForcedCount() throws Exception {
        SupportedCity cityA = city("nullable-mask-a");
        SupportedCity cityB = city("nullable-mask-b");
        OperatingTeam teamA = team(cityA, "nullable-mask-a");
        OperatingTeam teamB = team(cityB, "nullable-mask-b");
        Actor owner = staff("nullable-mask-owner",
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamA),
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamB));
        Actor peer = coordinator("nullable-mask-peer", teamA);
        Instant cutoff = Instant.parse("2026-10-23T00:00:00Z");
        List<ScopedLead> fixtures = List.of(
                scoped(seedLead(cityA, teamA, owner.user(), "mask-a-new", LeadStatus.NEW,
                        Instant.parse("2026-10-22T10:00:00Z"))),
                scoped(seedLead(cityB, teamB, owner.user(), "mask-b-new", LeadStatus.NEW,
                        Instant.parse("2026-10-22T11:00:00Z"))),
                scoped(seedLead(cityA, teamA, owner.user(), "mask-a-qualified", LeadStatus.QUALIFIED,
                        Instant.parse("2026-10-24T10:00:00Z"))),
                scoped(seedLead(cityA, teamA, owner.user(), "mask-a-null-follow-up", LeadStatus.NEW, null)));
        seedLead(cityA, teamA, peer.user(), "mask-peer", LeadStatus.NEW, null);

        List<String> countStatements = new ArrayList<>();
        List<String> selectStatements = new ArrayList<>();
        for (int mask = 0; mask < 16; mask++) {
            boolean cityPresent = (mask & 1) != 0;
            boolean teamPresent = (mask & 2) != 0;
            boolean statusPresent = (mask & 4) != 0;
            boolean cutoffPresent = (mask & 8) != 0;
            List<Long> expected = fixtures.stream()
                    .filter(row -> !cityPresent || row.city().getId().equals(cityA.getId()))
                    .filter(row -> !teamPresent || row.team().getId().equals(teamA.getId()))
                    .filter(row -> !statusPresent || row.entity().getStatus() == LeadStatus.NEW)
                    .filter(row -> !cutoffPresent || row.entity().getFollowUpAt() != null
                            && row.entity().getFollowUpAt().isBefore(cutoff))
                    .map(row -> row.entity().getId())
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();

            sqlCapture.clear();
            HttpResponse<String> response = visible(owner, cityPresent ? cityA.getId() : null,
                    teamPresent ? teamA.getId() : null, statusPresent ? "new" : null,
                    mask % 2 == 0, cutoffPresent ? cutoff : null, 0, 1);
            assertVisiblePage(response, expected, 1, 0);
            List<String> statements = sqlCapture.snapshot();
            statements.stream().filter(LeadHttpPostgresTest::isCountSql).forEach(countStatements::add);
            statements.stream().filter(LeadHttpPostgresTest::isSelectSql).forEach(selectStatements::add);
        }

        assertFalse(countStatements.isEmpty(), "At least one full-page nullable mask must execute COUNT");
        assertFalse(selectStatements.isEmpty(), "The visible queue SELECT must execute");
        assertTrue(countStatements.stream().allMatch(sql -> sql.contains("lead_routing_queue")));
        assertTrue(selectStatements.stream().allMatch(sql -> sql.contains("lead_routing_queue")));
        countStatements.forEach(sql -> System.out.println("P1_QUEUE_COUNT_SQL " + compactSql(sql)));
        selectStatements.forEach(sql -> System.out.println("P1_QUEUE_SELECT_SQL " + compactSql(sql)));

        HttpResponse<String> peerResponse = visible(peer, cityA.getId(), teamA.getId(), "NEW",
                false, cutoff, 0, 1);
        assertVisiblePage(peerResponse, List.of(), 1, 0);
        assertNoSqlStateEvidence(peerResponse);

        HttpResponse<String> allFiltersOwnerMe = visible(owner, cityA.getId(), teamA.getId(),
                "NEW", true, cutoff, 0, 20);
        assertVisiblePage(allFiltersOwnerMe, List.of(fixtures.get(0).entity().getId()), 20, 0);

        HttpResponse<String> allFiltersOwnerOmitted = visible(owner, cityA.getId(), teamA.getId(),
                "NEW", false, cutoff, 0, 20);
        assertEquals(allFiltersOwnerMe.body(), allFiltersOwnerOmitted.body());

        HttpResponse<String> mismatched = visible(owner, cityB.getId(), teamA.getId(), null,
                false, null, 0, 20);
        assertVisiblePage(mismatched, List.of(), 20, 0);
        HttpResponse<String> nonexistent = visible(owner, Long.MAX_VALUE - 1,
                Long.MAX_VALUE - 2, null, false, null, 0, 20);
        assertVisiblePage(nonexistent, List.of(), 20, 0);
        assertNoSqlStateEvidence(nonexistent);

        HighScope highScope = highScope("q09");
        Actor highIdOwner = coordinator("q09-owner", highScope.team());
        LeadRoutingQueue highIdLead = seedLead(highScope.city(), highScope.team(),
                highIdOwner.user(), "q09-high-id", LeadStatus.NEW, null);
        assertVisiblePage(visible(highIdOwner, highScope.city().getId(),
                highScope.team().getId(), null, false, null, 0, 20),
                List.of(highIdLead.getId()), 20, 0);
    }

    @Test
    void everyStoredStatusAndCurrentBlankAndOwnerParsingRemainCompatible() throws Exception {
        SupportedCity city = city("status-parser");
        OperatingTeam team = team(city, "status-parser");
        Actor owner = coordinator("status-parser-owner", team);
        Actor peer = coordinator("status-parser-peer", team);
        Map<LeadStatus, LeadRoutingQueue> byStatus = new LinkedHashMap<>();
        for (LeadStatus status : LeadStatus.values()) {
            byStatus.put(status, seedLead(city, team, owner.user(), "status-" + status,
                    status, null));
        }
        LeadRoutingQueue peerLead = seedLead(city, team, peer.user(), "status-peer",
                LeadStatus.NEW, null);

        for (LeadStatus status : LeadStatus.values()) {
            HttpResponse<String> response = visible(owner, city.getId(), team.getId(),
                    status.name().toLowerCase(Locale.ROOT), false, null, 0, 20);
            assertVisiblePage(response, List.of(byStatus.get(status).getId()), 20, 0);
        }

        HttpResponse<String> blank = send("GET", "/api/v1/operations/leads?cityId="
                + city.getId() + "&teamId=" + team.getId()
                + "&status=&owner=&followUpBefore=&page=0&size=20", owner.token(), null);
        assertVisiblePage(blank, byStatus.values().stream()
                .map(LeadRoutingQueue::getId).sorted(java.util.Comparator.reverseOrder()).toList(),
                20, 0);

        HttpResponse<String> anotherOwnerText = send("GET", "/api/v1/operations/leads?cityId=" + city.getId()
                + "&teamId=" + team.getId() + "&owner=someone-else&page=0&size=20",
                owner.token(), null);
        assertVisiblePage(anotherOwnerText, byStatus.values().stream()
                .map(LeadRoutingQueue::getId).sorted(java.util.Comparator.reverseOrder()).toList(),
                20, 0);
        HttpResponse<String> peerMe = visible(peer, city.getId(), team.getId(), null, true, null, 0, 20);
        assertVisiblePage(peerMe, List.of(peerLead.getId()), 20, 0);
    }

    @Test
    void invalidQueueInputsRemainTruthfulAndNeverBecomeEmptySuccesses() throws Exception {
        SupportedCity city = city("validation");
        OperatingTeam team = team(city, "validation");
        Actor owner = coordinator("validation-owner", team);
        seedLead(city, team, owner.user(), "validation-lead", LeadStatus.NEW, null);
        List<String> invalidPaths = List.of(
                "/api/v1/operations/leads?cityId=0",
                "/api/v1/operations/leads?cityId=-1",
                "/api/v1/operations/leads?cityId=1.5",
                "/api/v1/operations/leads?cityId=not-a-number",
                "/api/v1/operations/leads?cityId=9223372036854775808",
                "/api/v1/operations/leads?status=UNKNOWN",
                "/api/v1/operations/leads?followUpBefore=yesterday",
                "/api/v1/operations/leads?page=-1",
                "/api/v1/operations/leads?page=1.5",
                "/api/v1/operations/leads?size=0",
                "/api/v1/operations/leads?size=101");
        for (String path : invalidPaths) {
            HttpResponse<String> response = send("GET", path, owner.token(), null);
            assertEquals(400, response.statusCode(), path + " -> " + response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
            JsonNode error = mapper.readTree(response.body());
            assertTrue(error.has("timestamp"));
            assertEquals(400, error.path("status").asInt());
            assertFalse(response.body().contains("42P18"));
        }

        HttpResponse<String> unauthenticated = send("GET", "/api/v1/operations/leads", null, null);
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
        assertEquals("no-store",
                unauthenticated.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void authorizationMatrixPreservesOwnerTeamCityGlobalAndGrantUnionScopes() throws Exception {
        SupportedCity cityA = city("scope-a");
        SupportedCity cityB = city("scope-b");
        OperatingTeam teamA = team(cityA, "scope-a");
        OperatingTeam teamB = team(cityB, "scope-b");
        OperatingTeam teamC = team(cityA, "scope-c");
        OperatingTeam teamD = team(cityB, "scope-d");
        OperatingTeam teamE = team(cityA, "scope-e");

        Actor owner = coordinator("scope-owner", teamA);
        Actor peer = coordinator("scope-peer", teamA);
        LeadRoutingQueue owned = seedLead(cityA, teamA, owner.user(), "scope-owned", LeadStatus.NEW, null);
        LeadRoutingQueue peerOwned = seedLead(cityA, teamA, peer.user(), "scope-peer-owned",
                LeadStatus.NEW, null);
        LeadRoutingQueue ownerForeignTeam = seedLead(cityB, teamB, owner.user(),
                "scope-owner-foreign-team", LeadStatus.NEW, null);

        assertVisiblePage(visible(owner, cityA.getId(), teamA.getId(), null, false, null, 0, 20),
                List.of(owned.getId()), 20, 0);
        assertVisiblePage(visible(peer, cityA.getId(), teamA.getId(), null, false, null, 0, 20),
                List.of(peerOwned.getId()), 20, 0);
        assertEquals(404, send("GET", "/api/v1/operations/leads/" + owned.getId(),
                peer.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/leads/" + owned.getId() + "/activities",
                peer.token(), null).statusCode());
        assertVisiblePage(visible(owner, cityB.getId(), teamB.getId(), null, false, null, 0, 20),
                List.of(), 20, 0);

        Actor supervisor = staff("scope-supervisor",
                grantSpec(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, teamA));
        assertVisiblePage(visible(supervisor, cityA.getId(), teamA.getId(), null, false, null, 0, 20),
                List.of(peerOwned.getId(), owned.getId()), 20, 0);
        assertVisiblePage(visible(supervisor, null, null, null, false, null, 0, 20),
                List.of(peerOwned.getId(), owned.getId()), 20, 0);

        LeadRoutingQueue intakeA = seedLead(cityA, null, null, "scope-intake-a", LeadStatus.NEW, null);
        LeadRoutingQueue intakeB = seedLead(cityB, null, null, "scope-intake-b", LeadStatus.NEW, null);
        LeadRoutingQueue cityAdminA = seedLead(cityA, null, null, "scope-city-admin-a",
                LeadStatus.QUALIFIED, null);
        Actor intake = staff("scope-intake",
                grantSpec(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, cityA, null));
        Actor cityAdmin = staff("scope-city-admin",
                grantSpec(StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, cityA, null));
        assertVisiblePage(visible(intake, null, null, null, false, null, 0, 20),
                List.of(cityAdminA.getId(), intakeA.getId()), 20, 0);
        assertVisiblePage(visible(cityAdmin, null, null, null, false, null, 0, 20),
                List.of(cityAdminA.getId(), intakeA.getId()), 20, 0);
        assertVisiblePage(visible(intake, cityB.getId(), null, null, false, null, 0, 20),
                List.of(), 20, 0);

        LeadRoutingQueue legacy = seedLead(cityA, null, null, "scope-legacy", LeadStatus.STALE, null);
        legacy.setLegacyProvenance(LeadLegacyProvenance.LEGACY_UNVERIFIED);
        leads.saveAndFlush(legacy);
        LeadRoutingQueue unknownCity = seededRawLead("scope-unknown-city", LeadStatus.CLOSED);
        unknownCity.setFollowUpAt(Instant.parse("2029-01-01T00:00:00Z"));
        unknownCity.setFollowUpZoneId("Asia/Kolkata");
        leads.saveAndFlush(unknownCity);
        Actor global = staff("scope-global",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        assertEquals(403, visible(global, cityA.getId(), null, "STALE", false, null, 0, 20)
                .statusCode());
        assertEquals(403, visible(global, null, null, "CLOSED", false,
                Instant.parse("2030-01-01T00:00:00Z"), 0, 20).statusCode());
        Actor roleLabelOnly = staff("scope-role-label");
        assertEquals(403, visible(roleLabelOnly, null, null, null, false, null, 0, 20).statusCode());

        LeadRoutingQueue unionIntake = seedLead(cityA, null, null, "scope-union-intake",
                LeadStatus.NEW, null);
        Actor combined = staff("scope-combined",
                grantSpec(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, cityA, null),
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamC),
                grantSpec(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, teamE));
        LeadRoutingQueue unionCoordinated = seedLead(cityA, teamC, combined.user(),
                "scope-union-coordinate", LeadStatus.NEW, null);
        LeadRoutingQueue unionSupervised = seedLead(cityA, teamE, null, "scope-union-supervise",
                LeadStatus.NEW, null);
        LeadRoutingQueue excludedTeam = seedLead(cityB, teamD, null, "scope-union-excluded",
                LeadStatus.NEW, null);
        List<Long> unionExpected = List.of(unionCoordinated.getId(), unionSupervised.getId(),
                        unionIntake.getId(), cityAdminA.getId(), intakeA.getId())
                .stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertVisiblePage(visible(combined, cityA.getId(), null, null, false, null, 0, 20),
                unionExpected, 20, 0);
        assertFalse(unionExpected.contains(excludedTeam.getId()));
        assertFalse(unionExpected.contains(ownerForeignTeam.getId()));
    }

    @Test
    void freshRequestsHonorRevocationDeactivationOwnershipAndActorIsolation() throws Exception {
        SupportedCity city = city("fresh-authority");
        OperatingTeam team = team(city, "fresh-authority");
        Actor userA = coordinator("fresh-authority-a", team);
        Actor userB = coordinator("fresh-authority-b", team);
        LeadRoutingQueue leadA = seedLead(city, team, userA.user(), "fresh-a-owned",
                LeadStatus.NEW, null);
        LeadRoutingQueue leadB = seedLead(city, team, userB.user(), "fresh-b-owned",
                LeadStatus.NEW, null);

        assertVisiblePage(visible(userA, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadA.getId()), 20, 0);
        assertVisiblePage(visible(userB, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadB.getId()), 20, 0);
        assertVisiblePage(visible(userA, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadA.getId()), 20, 0);

        leadA.setOwner(userB.user());
        leads.saveAndFlush(leadA);
        assertVisiblePage(visible(userA, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(), 20, 0);
        assertVisiblePage(visible(userB, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadB.getId(), leadA.getId()), 20, 0);

        StaffAccessGrant userAGrant = userA.grants().get(0);
        userAGrant.revoke(userA.user(), Instant.now(), "P1_FRESH_REQUEST_TEST");
        grants.saveAndFlush(userAGrant);
        HttpResponse<String> revoked = visible(userA, city.getId(), team.getId(), null,
                false, null, 0, 20);
        assertEquals(403, revoked.statusCode(), revoked.body());
        assertEquals("no-store", revoked.headers().firstValue("Cache-Control").orElse(""));
        assertVisiblePage(visible(userB, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadB.getId(), leadA.getId()), 20, 0);

        Actor deactivated = coordinator("fresh-deactivated", team);
        LeadRoutingQueue deactivatedLead = seedLead(city, team, deactivated.user(),
                "fresh-deactivated-owned", LeadStatus.NEW, null);
        assertVisiblePage(visible(deactivated, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(deactivatedLead.getId()), 20, 0);
        EmployeeProfile profile = profiles.findByUserId(deactivated.user().getId()).orElseThrow();
        profile.setStaffActive(false);
        profile.setStaffDeactivatedAt(Instant.now());
        profiles.saveAndFlush(profile);
        assertEquals(403, visible(deactivated, city.getId(), team.getId(), null,
                false, null, 0, 20).statusCode());

        HttpResponse<String> unauthenticated = send("GET", "/api/v1/operations/leads", null, null);
        assertEquals(401, unauthenticated.statusCode());
        assertEquals("no-store",
                unauthenticated.headers().firstValue("Cache-Control").orElse(""));
        assertVisiblePage(visible(userB, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(leadB.getId(), leadA.getId()), 20, 0);
    }

    @Test
    void followUpCutoffUsesStrictPersistedInstantWithoutTimezoneDrift() throws Exception {
        SupportedCity city = city("time-order");
        OperatingTeam team = team(city, "time-order");
        Actor owner = coordinator("time-order-owner", team);
        Instant cutoff = Instant.parse("2026-10-22T06:00:00.123456Z");
        LeadRoutingQueue before = seedLead(city, team, owner.user(), "time-before",
                LeadStatus.NEW, cutoff.minusNanos(1_000));
        LeadRoutingQueue equal = seedLead(city, team, owner.user(), "time-equal",
                LeadStatus.NEW, cutoff);
        LeadRoutingQueue after = seedLead(city, team, owner.user(), "time-after",
                LeadStatus.NEW, cutoff.plusNanos(1_000));
        LeadRoutingQueue nullFollowUp = seedLead(city, team, owner.user(), "time-null",
                LeadStatus.NEW, null);

        String persistedBefore = jdbc.queryForObject(
                "select to_char(follow_up_at at time zone 'UTC', "
                        + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
                        + "from lead_routing_queue where id = ?",
                String.class, before.getId());
        String persistedEqual = jdbc.queryForObject(
                "select to_char(follow_up_at at time zone 'UTC', "
                        + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
                        + "from lead_routing_queue where id = ?",
                String.class, equal.getId());
        String persistedAfter = jdbc.queryForObject(
                "select to_char(follow_up_at at time zone 'UTC', "
                        + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
                        + "from lead_routing_queue where id = ?",
                String.class, after.getId());
        assertEquals("2026-10-22T06:00:00.123455Z", persistedBefore);
        assertEquals("2026-10-22T06:00:00.123456Z", persistedEqual);
        assertEquals("2026-10-22T06:00:00.123457Z", persistedAfter);
        assertNull(jdbc.queryForObject(
                "select to_char(follow_up_at, 'YYYY-MM-DD') "
                        + "from lead_routing_queue where id = ?",
                String.class, nullFollowUp.getId()));

        assertVisiblePage(visible(owner, city.getId(), team.getId(), null, false, null, 0, 20),
                List.of(nullFollowUp.getId(), after.getId(), equal.getId(), before.getId()), 20, 0);
        assertVisiblePage(visible(owner, city.getId(), team.getId(), null, false, cutoff, 0, 20),
                List.of(before.getId()), 20, 0);
        assertNotEquals(before.getId(), equal.getId());
        assertNotEquals(equal.getId(), after.getId());

        String utc = visible(owner, city.getId(), team.getId(), null, false,
                Instant.parse("2026-10-22T06:00:00.123456Z"), 0, 20).body();
        String india = visible(owner, city.getId(), team.getId(), null, false,
                Instant.parse("2026-10-22T11:30:00.123456+05:30"), 0, 20).body();
        assertEquals(utc, india);

        List<Long> indiaSession = serviceVisibleIds(owner, city, team, cutoff, "Asia/Kolkata");
        List<Long> utcSession = serviceVisibleIds(owner, city, team, cutoff, "UTC");
        assertEquals(List.of(before.getId()), indiaSession);
        assertEquals(indiaSession, utcSession);
    }

    @Test
    void tiedTimestampPaginationAndFullPageCountUseCurrentAuthorizedData() throws Exception {
        SupportedCity city = city("pagination");
        OperatingTeam team = team(city, "pagination");
        Actor owner = coordinator("pagination-owner", team);
        Actor peer = coordinator("pagination-peer", team);
        LocalDateTime tiedCreatedAt = LocalDateTime.of(2026, 10, 22, 6, 0);
        List<LeadRoutingQueue> created = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            LeadRoutingQueue lead = newLead(city, team, owner.user(), "pagination-" + index,
                    LeadStatus.NEW, null, tiedCreatedAt);
            lead.setContactPhoneNormalized("+91987654" + String.format("%04d", index));
            created.add(lead);
        }
        created = leads.saveAllAndFlush(created);
        LeadRoutingQueue peerLead = seedLead(city, team, peer.user(), "pagination-peer-owned",
                LeadStatus.NEW, null);
        List<Long> descending = created.stream().map(LeadRoutingQueue::getId)
                .sorted(java.util.Comparator.reverseOrder()).toList();

        HttpResponse<String> firstOne = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 1);
        assertVisiblePage(firstOne, descending, 1, 0);
        assertEquals(201, mapper.readTree(firstOne.body()).path("totalElements").asLong());

        HttpResponse<String> firstTwo = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 2);
        JsonNode twoPage = mapper.readTree(firstTwo.body());
        assertEquals(List.of(descending.get(0), descending.get(1)),
                itemIds(twoPage));

        sqlCapture.clear();
        HttpResponse<String> fullFirst = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 100);
        assertVisiblePage(fullFirst, descending, 100, 0);
        List<String> firstStatements = sqlCapture.snapshot();
        assertTrue(firstStatements.stream().anyMatch(LeadHttpPostgresTest::isCountSql),
                "Full first page must execute COUNT");
        assertTrue(firstStatements.stream().anyMatch(LeadHttpPostgresTest::isSelectSql),
                "Full first page must execute SELECT");
        firstStatements.forEach(sql -> System.out.println("P1_PAGE_SQL " + compactSql(sql)));

        sqlCapture.clear();
        HttpResponse<String> terminal = visible(owner, city.getId(), team.getId(), null,
                false, null, 1, 100);
        assertVisiblePage(terminal, descending, 100, 1);
        assertEquals(201, mapper.readTree(terminal.body()).path("totalElements").asLong());
        assertTrue(sqlCapture.snapshot().stream().anyMatch(LeadHttpPostgresTest::isCountSql),
                "Full later page must execute COUNT");

        HttpResponse<String> terminalEmpty = visible(owner, city.getId(), team.getId(), null,
                false, null, 2, 100);
        assertVisiblePage(terminalEmpty, descending, 100, 2);

        LeadRoutingQueue transferred = created.get(0);
        transferred.setOwner(peer.user());
        leads.saveAndFlush(transferred);
        HttpResponse<String> afterTransfer = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 100);
        assertEquals(200, mapper.readTree(afterTransfer.body()).path("totalElements").asLong());
        assertFalse(itemIds(mapper.readTree(afterTransfer.body())).contains(transferred.getId()));
        HttpResponse<String> peerAfterTransfer = visible(peer, city.getId(), team.getId(), null,
                false, null, 0, 20);
        assertEquals(2, mapper.readTree(peerAfterTransfer.body()).path("totalElements").asLong());
        assertTrue(itemIds(mapper.readTree(peerAfterTransfer.body())).contains(peerLead.getId()));
        assertTrue(itemIds(mapper.readTree(peerAfterTransfer.body())).contains(transferred.getId()));
    }

    @Test
    void recoveryAndAdjacentDuplicateLookupRemainTruthfulAndMutationFree() throws Exception {
        SupportedCity city = city("recovery");
        OperatingTeam team = team(city, "recovery");
        Actor owner = coordinator("recovery-owner", team);
        Actor peer = coordinator("recovery-peer", team);
        LeadRoutingQueue lead = seedLead(city, team, owner.user(), "recovery-owned",
                LeadStatus.NEW, null);
        lead.setContactPhoneNormalized("+919900001234");
        lead.setPhoneNumber("+919900001234");
        leads.saveAndFlush(lead);
        long leadCount = jdbc.queryForObject("select count(*) from lead_routing_queue", Long.class);
        long activityCount = jdbc.queryForObject("select count(*) from lead_activities", Long.class);
        long receiptCount = jdbc.queryForObject("select count(*) from lead_command_receipts", Long.class);
        long sourceCount = jdbc.queryForObject("select count(*) from lead_source_events", Long.class);

        HttpResponse<String> recovered = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 20);
        assertVisiblePage(recovered, List.of(lead.getId()), 20, 0);
        assertNoSqlStateEvidence(recovered);
        assertEquals("no-store", recovered.headers().firstValue("Cache-Control").orElse(""));
        assertFalse(recovered.body().contains("+919900001234"));
        assertEquals(200, send("GET", "/api/v1/operations/leads/" + lead.getId(),
                owner.token(), null).statusCode());
        HttpResponse<String> peerDetail = send("GET",
                "/api/v1/operations/leads/" + lead.getId(), peer.token(), null);
        assertEquals(404, peerDetail.statusCode());
        assertFalse(peerDetail.body().contains("+919900001234"));
        HttpResponse<String> peerActivity = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/activities", peer.token(), null);
        assertEquals(404, peerActivity.statusCode());
        assertFalse(peerActivity.body().contains("+919900001234"));
        HttpResponse<String> repeated = visible(owner, city.getId(), team.getId(), null,
                false, null, 0, 20);
        assertVisiblePage(repeated, List.of(lead.getId()), 20, 0);
        assertEquals(leadCount, jdbc.queryForObject(
                "select count(*) from lead_routing_queue", Long.class));
        assertEquals(activityCount, jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class));
        assertEquals(receiptCount, jdbc.queryForObject(
                "select count(*) from lead_command_receipts", Long.class));
        assertEquals(sourceCount, jdbc.queryForObject(
                "select count(*) from lead_source_events", Long.class));

    }

    @Test
    void adjacentDuplicateCandidateLookupIsBlockedByInstantBindingOutsideQueueScope() throws Exception {
        SupportedCity city = city("duplicate-r05");
        OperatingTeam team = team(city, "duplicate-r05");
        Actor owner = coordinator("duplicate-r05-owner", team);
        LeadRoutingQueue lead = seedLead(city, team, owner.user(), "duplicate-r05",
                LeadStatus.NEW, null);
        lead.setContactPhoneNormalized("+919900001234");
        lead.setPhoneNumber("+919900001234");
        leads.saveAndFlush(lead);

        HttpResponse<String> duplicate = send("POST", "/api/v1/operations/leads/duplicate-candidates",
                owner.token(), "{\"cityId\":" + city.getId()
                        + ",\"contactPhone\":\"+919900001234\"}");
        assertEquals(200, duplicate.statusCode(), duplicate.body());
        JsonNode duplicateBody = mapper.readTree(duplicate.body());
        assertTrue(duplicateBody.path("candidates").isArray());
        assertEquals(lead.getId(),
                duplicateBody.path("candidates").get(0).path("leadId").asLong());
        assertFalse(duplicate.body().contains("+919900001234"));
    }

    @Test
    void r05BindingJourneyRecordsExactDatabaseInstantAndEnforcesRateWindow() throws Exception {
        SupportedCity city = city("r05-window");
        SupportedCity secondCity = city("r05-window-second");
        OperatingTeam team = team(city, "r05-window");
        OperatingTeam secondTeam = team(secondCity, "r05-window-second");
        Actor owner = coordinator("r05-window-owner", team);
        LeadRoutingQueue lead = seedLead(city, team, owner.user(), "r05-window",
                LeadStatus.NEW, null);
        lead.setContactPhoneNormalized("+919900001234");
        lead.setPhoneNumber("+919900001234");
        leads.saveAndFlush(lead);
        Map<String, Long> unrelatedBefore = unrelatedOperationalCounts();

        Instant databaseNow = Instant.parse("2026-10-22T05:00:00.123456Z");
        Instant cutoff = databaseNow.minusSeconds(3600);
        testClock.fixAt(databaseNow);
        try {
            sqlCapture.clear();
            HttpResponse<String> duplicate = duplicate(owner, city.getId(), "+919900001234");
            assertEquals(200, duplicate.statusCode(), duplicate.body());
            assertEquals("no-store",
                    duplicate.headers().firstValue("Cache-Control").orElse(""));
            JsonNode body = mapper.readTree(duplicate.body());
            assertEquals(List.of(lead.getId()),
                    candidateIds(body));
            assertEquals("**********1234",
                    body.path("candidates").get(0).path("maskedContact").asText());
            assertFalse(duplicate.body().contains("+919900001234"));
            assertFalse(duplicate.body().contains("42P18"));
            assertEquals(1L, lookupEventCount(owner));
            assertEquals(databaseNow, latestLookupEvent(owner));
            assertEquals("2026-10-22T05:00:00.123456Z", jdbc.queryForObject(
                    "select to_char(occurred_at at time zone 'UTC', "
                            + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
                            + "from lead_lookup_events where actor_user_id = ? "
                            + "and purpose = 'DUPLICATE_CANDIDATE' order by id desc limit 1",
                    String.class, owner.user().getId()));
            assertTrue(sqlCapture.snapshot().stream().anyMatch(
                    sql -> compactSql(sql).contains("from lead_routing_queue")));

            HttpResponse<String> empty = duplicate(owner, city.getId(), "+919900009999");
            assertEquals(200, empty.statusCode(), empty.body());
            assertTrue(mapper.readTree(empty.body()).path("candidates").isEmpty());
            assertEquals(2L, lookupEventCount(owner));

            assertEquals(200, duplicate(owner, city.getId(), "+919900001234").statusCode());
            assertEquals(200, duplicate(owner, city.getId(), "+919900001234").statusCode());
            assertEquals(4L, lookupEventCount(owner),
                    "Successful repeated POSTs retain non-idempotent lookup evidence");

            Actor underLimit = coordinator("r05-under-limit", team);
            seedLookupEvents(underLimit, 59, databaseNow.minusSeconds(1));
            assertEquals(200, duplicate(underLimit, city.getId(), "+919900001234").statusCode());
            assertEquals(60L, lookupEventCount(underLimit));

            Actor atLimit = coordinator("r05-at-limit", team);
            seedLookupEvents(atLimit, 60, databaseNow.minusSeconds(1));
            sqlCapture.clear();
            HttpResponse<String> limited = duplicate(atLimit, city.getId(), "+919900009999");
            assertEquals(429, limited.statusCode(), limited.body());
            assertEquals("no-store", limited.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(60L, lookupEventCount(atLimit));
            assertTrue(sqlCapture.snapshot().stream()
                    .noneMatch(sql -> compactSql(sql).contains("from lead_routing_queue")));
            assertEquals(429, duplicate(atLimit, city.getId(), "+919900008888").statusCode(),
                    "Changing phone cannot reset the actor-wide window");
            Actor overLimit = coordinator("r05-over-limit", team);
            seedLookupEvents(overLimit, 61, databaseNow.minusSeconds(1));
            assertEquals(429, duplicate(overLimit, city.getId(), "+919900001234").statusCode());
            assertEquals(61L, lookupEventCount(overLimit));
            Actor twoCities = staff("r05-two-cities",
                    grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                            null, team),
                    grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                            null, secondTeam));
            seedLookupEvents(twoCities, 60, databaseNow.minusSeconds(1));
            assertEquals(429, duplicate(twoCities, secondCity.getId(), "+919900001234").statusCode(),
                    "Changing permitted City cannot reset the actor-wide window");
            assertEquals(60L, lookupEventCount(twoCities));

            Actor equalBoundary = coordinator("r05-equal-boundary", team);
            seedLookupEvents(equalBoundary, 1, cutoff);
            seedLookupEvents(equalBoundary, 59, databaseNow.minusSeconds(1));
            assertEquals(200, duplicate(equalBoundary, city.getId(), "+919900001234").statusCode(),
                    "An event exactly at occurred_at > cutoff must be excluded");
            assertEquals(61L, lookupEventCount(equalBoundary));

            Actor beforeBoundary = coordinator("r05-before-boundary", team);
            seedLookupEvents(beforeBoundary, 1, cutoff.minusNanos(1_000));
            seedLookupEvents(beforeBoundary, 59, databaseNow.minusSeconds(1));
            assertEquals(200, duplicate(beforeBoundary, city.getId(), "+919900001234").statusCode());
            assertEquals(61L, lookupEventCount(beforeBoundary));

            Actor afterBoundary = coordinator("r05-after-boundary", team);
            seedLookupEvents(afterBoundary, 1, cutoff.plusNanos(1_000));
            seedLookupEvents(afterBoundary, 59, databaseNow.minusSeconds(1));
            assertEquals(429, duplicate(afterBoundary, city.getId(), "+919900001234").statusCode());
            assertEquals(60L, lookupEventCount(afterBoundary));

            Actor independent = coordinator("r05-independent", team);
            assertEquals(200, duplicate(independent, city.getId(), "+919900001234").statusCode());
            assertEquals(1L, lookupEventCount(independent));
            assertEquals(unrelatedBefore, unrelatedOperationalCounts());
        } finally {
            testClock.useDatabaseClock();
        }
    }

    @Test
    void r05RealDatabaseClockAndSessionTimezonesPreserveTheSameAbsoluteInstant() throws Exception {
        SupportedCity city = city("r05-clock");
        OperatingTeam team = team(city, "r05-clock");
        Actor realClock = coordinator("r05-real-clock", team);
        LeadRoutingQueue candidate = seedLead(city, team, realClock.user(),
                "r05-real-clock", LeadStatus.NEW, null);
        candidate.setContactPhoneNormalized("+919811111111");
        leads.saveAndFlush(candidate);
        Instant before = databaseNow();
        HttpResponse<String> response = duplicate(realClock, city.getId(), "+919811111111");
        Instant after = databaseNow();
        assertEquals(200, response.statusCode(), response.body());
        Instant persisted = latestLookupEvent(realClock);
        assertNotNull(persisted);
        assertFalse(persisted.isBefore(before));
        assertFalse(persisted.isAfter(after));

        Instant fixed = Instant.parse("2026-10-22T06:00:00.654321Z");
        testClock.fixAt(fixed);
        Actor utc = coordinator("r05-utc", team);
        Actor india = coordinator("r05-india", team);
        TimeZone original = TimeZone.getDefault();
        try {
            assertEquals(fixed, duplicateInSessionZone(utc, city.getId(), "UTC"));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(fixed, duplicateInSessionZone(india, city.getId(), "Asia/Kolkata"));
        } finally {
            TimeZone.setDefault(original);
            testClock.useDatabaseClock();
        }
    }

    @Test
    void r05ScopedCandidateVisibilityMasksAccountTruthAndBoundsAtTwenty() throws Exception {
        SupportedCity cityA = city("r05-scope-a");
        SupportedCity cityB = city("r05-scope-b");
        OperatingTeam teamA = team(cityA, "r05-scope-a");
        OperatingTeam teamB = team(cityB, "r05-scope-b");
        Actor owner = coordinator("r05-scope-owner", teamA);
        Actor peer = coordinator("r05-scope-peer", teamA);
        Actor supervisor = staff("r05-scope-supervisor",
                grantSpec(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, teamA));
        Actor intake = staff("r05-scope-intake",
                grantSpec(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, cityA, null));
        Actor cityAdmin = staff("r05-scope-city-admin",
                grantSpec(StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, cityA, null));
        Actor global = staff("r05-scope-global",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        String phone = "+919822222222";

        LeadRoutingQueue owned = matchingLead(cityA, teamA, owner.user(),
                "r05-scope-owned", phone, LeadStatus.NEW);
        LeadRoutingQueue peerOwned = matchingLead(cityA, teamA, peer.user(),
                "r05-scope-peer-owned", phone, LeadStatus.NEW);
        LeadRoutingQueue foreignTeamOwned = matchingLead(cityB, teamB, owner.user(),
                "r05-scope-foreign-team", phone, LeadStatus.NEW);
        LeadRoutingQueue intakeA = matchingLead(cityA, null, null,
                "r05-scope-intake-a", phone, LeadStatus.NEW);
        matchingLead(cityB, null, null, "r05-scope-intake-b", phone, LeadStatus.NEW);

        User existingAccount = new User();
        existingAccount.setEmail("r05-account-" + UUID.randomUUID() + "@example.test");
        existingAccount.setFullName("Existing account");
        existingAccount.setPhoneNumber(phone);
        existingAccount.setMobileNumberNormalized(phone);
        existingAccount.setMobileVerifiedAt(OffsetDateTime.now());
        existingAccount.setRole(Role.ROLE_TENANT);
        users.saveAndFlush(existingAccount);

        HttpResponse<String> ownerResponse = duplicate(owner, cityA.getId(), "0091 98222 22222");
        assertEquals(200, ownerResponse.statusCode(), ownerResponse.body());
        JsonNode ownerBody = mapper.readTree(ownerResponse.body());
        assertEquals(List.of(owned.getId()), candidateIds(ownerBody));
        assertTrue(ownerBody.path("existingAccountMatchAvailable").asBoolean());
        assertTrue(ownerBody.path("candidates").get(0).path("currentlyOwnedByActor").asBoolean());
        assertEquals(8, ownerBody.path("candidates").get(0).size());
        assertFalse(ownerBody.toString().contains(phone));
        assertEquals("no-store",
                ownerResponse.headers().firstValue("Cache-Control").orElse(""));

        HttpResponse<String> supervisorResponse = duplicate(supervisor, cityA.getId(), phone);
        assertEquals(200, supervisorResponse.statusCode(), supervisorResponse.body());
        assertEquals(List.of(peerOwned.getId(), owned.getId()),
                candidateIds(mapper.readTree(supervisorResponse.body())));

        assertEquals(List.of(intakeA.getId()),
                candidateIds(mapper.readTree(duplicate(intake, cityA.getId(), phone).body())));
        assertEquals(List.of(intakeA.getId()),
                candidateIds(mapper.readTree(duplicate(cityAdmin, cityA.getId(), phone).body())));
        List<Long> globalExpected = List.of(
                        owned.getId(), peerOwned.getId(), intakeA.getId())
                .stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertEquals(globalExpected,
                candidateIds(mapper.readTree(duplicate(global, cityA.getId(), phone).body())));

        long ownerEvents = lookupEventCount(owner);
        HttpResponse<String> foreignCity = duplicate(owner, cityB.getId(), phone);
        assertEquals(403, foreignCity.statusCode(), foreignCity.body());
        assertEquals("no-store",
                foreignCity.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(ownerEvents, lookupEventCount(owner));
        assertFalse(foreignCity.body().contains(phone));
        assertFalse(foreignCity.body().contains(foreignTeamOwned.getCustomerName()));

        HttpResponse<String> empty = duplicate(owner, cityA.getId(), "+919833333333");
        assertEquals(200, empty.statusCode(), empty.body());
        assertTrue(mapper.readTree(empty.body()).path("candidates").isEmpty());

        Actor bounded = coordinator("r05-bounded", teamA);
        List<Long> boundedIds = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            LeadRoutingQueue row = matchingLead(cityA, teamA, bounded.user(),
                    "r05-bounded-" + index, "+919844444444", LeadStatus.NEW);
            boundedIds.add(row.getId());
        }
        List<Long> expected = boundedIds.stream()
                .sorted(java.util.Comparator.reverseOrder()).limit(20).toList();
        HttpResponse<String> boundedResponse = duplicate(bounded, cityA.getId(), "+919844444444");
        assertEquals(200, boundedResponse.statusCode(), boundedResponse.body());
        assertEquals(expected, candidateIds(mapper.readTree(boundedResponse.body())));
        assertFalse(candidateIds(mapper.readTree(boundedResponse.body()))
                .contains(boundedIds.stream().min(Long::compareTo).orElseThrow()));
    }

    @Test
    void expiredInactiveAndMixedAuthorityCloseTheS08EvidenceCondition() throws Exception {
        SupportedCity expiredCity = city("s08-expired");
        OperatingTeam expiredTeam = team(expiredCity, "s08-expired");
        Actor expiredOnly = staff("s08-expired-only",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        null, expiredTeam, -120));
        LeadRoutingQueue expiredLead = seedLead(expiredCity, expiredTeam, expiredOnly.user(),
                "s08-expired-private", LeadStatus.NEW, null);
        assertEquals(403, visible(expiredOnly, expiredCity.getId(), expiredTeam.getId(),
                null, false, null, 0, 20).statusCode());
        HttpResponse<String> expiredLookup = duplicate(expiredOnly, expiredCity.getId(),
                expiredLead.getContactPhoneNormalized());
        assertEquals(403, expiredLookup.statusCode(), expiredLookup.body());
        assertEquals("no-store",
                expiredLookup.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(0L, lookupEventCount(expiredOnly));
        assertFalse(expiredLookup.body().contains(expiredLead.getCustomerName()));
        assertFalse(expiredLookup.body().contains(expiredLead.getContactPhoneNormalized()));

        SupportedCity inactiveCity = city("s08-inactive");
        OperatingTeam inactiveTeam = team(inactiveCity, "s08-inactive");
        Actor inactiveTeamActor = coordinator("s08-inactive-team", inactiveTeam);
        LeadRoutingQueue inactiveTeamLead = seedLead(inactiveCity, inactiveTeam,
                inactiveTeamActor.user(), "s08-inactive-team-private", LeadStatus.NEW, null);
        assertVisiblePage(visible(inactiveTeamActor, inactiveCity.getId(), inactiveTeam.getId(),
                null, false, null, 0, 20), List.of(inactiveTeamLead.getId()), 20, 0);
        OperatingTeam inactiveTeamCurrent = teams.findById(inactiveTeam.getId()).orElseThrow();
        inactiveTeamCurrent.setActive(false);
        teams.saveAndFlush(inactiveTeamCurrent);
        assertEquals(403, visible(inactiveTeamActor, inactiveCity.getId(), inactiveTeam.getId(),
                null, false, null, 0, 20).statusCode());
        assertEquals(403, duplicate(inactiveTeamActor, inactiveCity.getId(),
                inactiveTeamLead.getContactPhoneNormalized()).statusCode());
        assertEquals(0L, lookupEventCount(inactiveTeamActor));
        OperatingTeam reactivatedTeam = teams.findById(inactiveTeam.getId()).orElseThrow();
        reactivatedTeam.setActive(true);
        teams.saveAndFlush(reactivatedTeam);
        assertVisiblePage(visible(inactiveTeamActor, inactiveCity.getId(), inactiveTeam.getId(),
                null, false, null, 0, 20), List.of(inactiveTeamLead.getId()), 20, 0);

        Actor inactiveCityActor = staff("s08-inactive-city",
                grantSpec(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, inactiveCity, null));
        LeadRoutingQueue inactiveCityLead = seedLead(inactiveCity, null, null,
                "s08-inactive-city-private", LeadStatus.NEW, null);
        assertVisiblePage(visible(inactiveCityActor, inactiveCity.getId(), null,
                null, false, null, 0, 20), List.of(inactiveCityLead.getId()), 20, 0);
        SupportedCity inactiveCityCurrent = cities.findById(inactiveCity.getId()).orElseThrow();
        inactiveCityCurrent.setActive(false);
        cities.saveAndFlush(inactiveCityCurrent);
        assertEquals(403, visible(inactiveCityActor, inactiveCity.getId(), null,
                null, false, null, 0, 20).statusCode());
        assertEquals(403, duplicate(inactiveCityActor, inactiveCity.getId(),
                inactiveCityLead.getContactPhoneNormalized()).statusCode());
        assertEquals(0L, lookupEventCount(inactiveCityActor));
        SupportedCity reactivatedCity = cities.findById(inactiveCity.getId()).orElseThrow();
        reactivatedCity.setActive(true);
        cities.saveAndFlush(reactivatedCity);
        assertVisiblePage(visible(inactiveCityActor, inactiveCity.getId(), null,
                null, false, null, 0, 20), List.of(inactiveCityLead.getId()), 20, 0);

        SupportedCity mixedCity = city("s08-mixed");
        OperatingTeam removedTeam = team(mixedCity, "s08-mixed-removed");
        OperatingTeam retainedTeam = team(mixedCity, "s08-mixed-retained");
        Actor mixed = staff("s08-mixed",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        null, removedTeam, -120),
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        null, retainedTeam));
        LeadRoutingQueue removedLead = seedLead(mixedCity, removedTeam, mixed.user(),
                "s08-mixed-removed-private", LeadStatus.NEW, null);
        LeadRoutingQueue retainedLead = seedLead(mixedCity, retainedTeam, mixed.user(),
                "s08-mixed-retained", LeadStatus.NEW, null);
        assertVisiblePage(visible(mixed, mixedCity.getId(), null,
                null, false, null, 0, 20), List.of(retainedLead.getId()), 20, 0);
        List<Long> mixedCandidates = candidateIds(mapper.readTree(duplicate(mixed,
                mixedCity.getId(), retainedLead.getContactPhoneNormalized()).body()));
        assertTrue(mixedCandidates.contains(retainedLead.getId()));
        assertFalse(mixedCandidates.contains(removedLead.getId()));

        Actor validControl = coordinator("s08-valid-control", retainedTeam);
        LeadRoutingQueue validLead = seedLead(mixedCity, retainedTeam, validControl.user(),
                "s08-valid-control", LeadStatus.NEW, null);
        assertVisiblePage(visible(validControl, mixedCity.getId(), retainedTeam.getId(),
                null, false, null, 0, 20), List.of(validLead.getId()), 20, 0);

        Actor revokedBetween = coordinator("s08-revoked-between", retainedTeam);
        LeadRoutingQueue revokedBetweenLead = matchingLead(mixedCity, retainedTeam,
                revokedBetween.user(), "s08-revoked-between", "+919899999999", LeadStatus.NEW);
        assertEquals(200, duplicate(revokedBetween, mixedCity.getId(),
                revokedBetweenLead.getContactPhoneNormalized()).statusCode());
        assertEquals(1L, lookupEventCount(revokedBetween));
        StaffAccessGrant betweenGrant = revokedBetween.grants().get(0);
        betweenGrant.revoke(revokedBetween.user(), Instant.now(), "S08_REQUEST_BOUNDARY");
        grants.saveAndFlush(betweenGrant);
        assertEquals(403, duplicate(revokedBetween, mixedCity.getId(),
                revokedBetweenLead.getContactPhoneNormalized()).statusCode());
        assertEquals(1L, lookupEventCount(revokedBetween));
    }

    @Test
    void r05InvalidInputsAndActorSwitchingRemainTruthfulAndIsolated() throws Exception {
        SupportedCity city = city("r05-input");
        OperatingTeam team = team(city, "r05-input");
        Actor actorA = coordinator("r05-input-a", team);
        Actor actorB = coordinator("r05-input-b", team);
        LeadRoutingQueue leadA = matchingLead(city, team, actorA.user(),
                "r05-input-a-owned", "+919855555555", LeadStatus.NEW);
        LeadRoutingQueue leadB = matchingLead(city, team, actorB.user(),
                "r05-input-b-owned", "+919855555555", LeadStatus.NEW);

        List<String> invalidBodies = List.of(
                "{\"contactPhone\":\"+919855555555\"}",
                "{\"cityId\":0,\"contactPhone\":\"+919855555555\"}",
                "{\"cityId\":-1,\"contactPhone\":\"+919855555555\"}",
                "{\"cityId\":\"not-a-number\",\"contactPhone\":\"+919855555555\"}",
                "{\"cityId\":9223372036854775808,\"contactPhone\":\"+919855555555\"}",
                "{\"cityId\":" + city.getId() + "}",
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":null}",
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":\"  \"}",
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":\"123\"}",
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":\"+919855555555 x12\"}",
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":\"not-a-phone\"}");
        int expectedEvents = 0;
        for (String invalidBody : invalidBodies) {
            HttpResponse<String> response = send("POST",
                    "/api/v1/operations/leads/duplicate-candidates",
                    actorA.token(), invalidBody);
            assertEquals(400, response.statusCode(), invalidBody + " -> " + response.body());
            assertEquals("no-store",
                    response.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(response.body().contains("+919855555555"));
            assertEquals(expectedEvents, lookupEventCount(actorA));
        }

        HttpResponse<String> unknownCity = duplicate(actorA, Long.MAX_VALUE - 50,
                "+919855555555");
        assertEquals(403, unknownCity.statusCode(), unknownCity.body());
        assertEquals(0L, lookupEventCount(actorA));

        HttpResponse<String> fractionalCity = send("POST",
                "/api/v1/operations/leads/duplicate-candidates", actorA.token(),
                "{\"cityId\":1.5,\"contactPhone\":\"+919855555555\"}");
        // Existing Jackson coercion truncates 1.5 to 1. This is recorded as a pre-existing input-policy
        // limitation, not silently changed inside the JDBC correction.
        assertEquals(403, fractionalCity.statusCode(), fractionalCity.body());
        assertEquals(0L, lookupEventCount(actorA));

        HttpResponse<String> unauthenticated = send("POST",
                "/api/v1/operations/leads/duplicate-candidates", null,
                "{\"cityId\":" + city.getId() + ",\"contactPhone\":\"+919855555555\"}");
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
        assertEquals("no-store",
                unauthenticated.headers().firstValue("Cache-Control").orElse(""));

        Actor roleLabelOnly = staff("r05-input-role-label");
        assertEquals(403, duplicate(roleLabelOnly, city.getId(), "+919855555555").statusCode());

        assertEquals(List.of(leadA.getId()), candidateIds(mapper.readTree(
                duplicate(actorA, city.getId(), "+919855555555").body())));
        assertEquals(List.of(leadB.getId()), candidateIds(mapper.readTree(
                duplicate(actorB, city.getId(), "+919855555555").body())));
        assertEquals(List.of(leadA.getId()), candidateIds(mapper.readTree(
                duplicate(actorA, city.getId(), "+919855555555").body())));
        assertEquals(2L, lookupEventCount(actorA));
        assertEquals(1L, lookupEventCount(actorB));
    }

    @Test
    void r05FailureInjectionRollsBackEvidenceAndSafeRetriesRemainUsable() throws Exception {
        SupportedCity city = city("r05-faults");
        OperatingTeam team = team(city, "r05-faults");
        try {
            Actor countFailure = coordinator("r05-count-failure", team);
            LeadRoutingQueue countCandidate = matchingLead(city, team, countFailure.user(),
                    "r05-count-candidate", "+919866666661", LeadStatus.NEW);
            testJdbc.failCountFor(countFailure.user().getId());
            HttpResponse<String> countResponse = duplicate(countFailure, city.getId(),
                    countCandidate.getContactPhoneNormalized());
            assertEquals(500, countResponse.statusCode(), countResponse.body());
            assertEquals("no-store",
                    countResponse.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(countResponse.body().contains(countCandidate.getCustomerName()));
            assertFalse(countResponse.body().contains(countCandidate.getContactPhoneNormalized()));
            assertEquals(0L, lookupEventCount(countFailure));
            testJdbc.reset();
            assertEquals(200, duplicate(countFailure, city.getId(),
                    countCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(countFailure));

            Actor insertFailure = coordinator("r05-insert-failure", team);
            LeadRoutingQueue insertCandidate = matchingLead(city, team, insertFailure.user(),
                    "r05-insert-candidate", "+919866666662", LeadStatus.NEW);
            testJdbc.failInsertFor(insertFailure.user().getId());
            HttpResponse<String> insertResponse = duplicate(insertFailure, city.getId(),
                    insertCandidate.getContactPhoneNormalized());
            assertEquals(500, insertResponse.statusCode(), insertResponse.body());
            assertEquals("no-store",
                    insertResponse.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(0L, lookupEventCount(insertFailure));
            testJdbc.reset();
            assertEquals(200, duplicate(insertFailure, city.getId(),
                    insertCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(insertFailure));

            Actor candidateFailure = coordinator("r05-candidate-failure", team);
            LeadRoutingQueue candidateQueryCandidate = matchingLead(city, team,
                    candidateFailure.user(), "r05-candidate-query", "+919866666663",
                    LeadStatus.NEW);
            String originalTable = "lead_routing_queue";
            String failedTable = "lead_routing_queue_r05_candidate_failure";
            try {
                jdbc.execute("alter table " + originalTable + " rename to " + failedTable);
                HttpResponse<String> candidateFailureResponse = duplicate(candidateFailure,
                        city.getId(), candidateQueryCandidate.getContactPhoneNormalized());
                assertEquals(500, candidateFailureResponse.statusCode(),
                        candidateFailureResponse.body());
                assertEquals("no-store", candidateFailureResponse.headers()
                        .firstValue("Cache-Control").orElse(""));
                assertEquals(0L, lookupEventCount(candidateFailure));
            } finally {
                jdbc.execute("alter table " + failedTable + " rename to " + originalTable);
            }
            assertEquals(200, duplicate(candidateFailure, city.getId(),
                    candidateQueryCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(candidateFailure));

            Actor accountFailure = coordinator("r05-account-failure", team);
            LeadRoutingQueue accountCandidate = matchingLead(city, team, accountFailure.user(),
                    "r05-account-candidate", "+919866666664", LeadStatus.NEW);
            FAIL_ACCOUNT_INDICATOR.set(true);
            HttpResponse<String> accountFailureResponse = duplicate(accountFailure,
                    city.getId(), accountCandidate.getContactPhoneNormalized());
            assertEquals(500, accountFailureResponse.statusCode(),
                    accountFailureResponse.body());
            assertEquals("no-store", accountFailureResponse.headers()
                    .firstValue("Cache-Control").orElse(""));
            assertEquals(0L, lookupEventCount(accountFailure));
            FAIL_ACCOUNT_INDICATOR.set(false);
            assertEquals(200, duplicate(accountFailure, city.getId(),
                    accountCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(accountFailure));

        } finally {
            testJdbc.reset();
        }
    }

    @Test
    void c05LostHttpAcknowledgementLeavesDurableEvidenceBeforeNonIdempotentRetry()
            throws Exception {
        SupportedCity city = city("c05-lost-ack");
        OperatingTeam team = team(city, "c05-lost-ack");
        Actor actor = coordinator("c05-lost-ack-actor", team);
        Actor unrelatedActor = coordinator("c05-lost-ack-unrelated", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-lost-ack-candidate", "+919877777799", LeadStatus.NEW);
        matchingLead(city, team, unrelatedActor.user(), "c05-lost-ack-unrelated-candidate",
                "+919877777798", LeadStatus.NEW);
        Map<String, Long> unrelatedBefore = unrelatedOperationalCounts();

        LostHttpResult lost = duplicateWithoutClientAcknowledgement(actor, city.getId(),
                candidate.getContactPhoneNormalized());

        assertEquals(-1, lost.clientRead(),
                "The client must observe EOF before any successful HTTP byte is forwarded");
        assertTrue(lost.serverStatusLine().contains(" 200 "), lost.serverResponse());
        assertFalse(lost.serverResponse().contains(candidate.getContactPhoneNormalized()),
                "The committed response sent into the transport must remain masked");
        assertTrue(lost.serverResponse().contains("\"leadId\":" + candidate.getId()));
        assertEquals(1L, lookupEventCount(actor),
                "The first lookup must already be durable when its acknowledgement is lost");
        assertEquals(0L, lookupEventCount(unrelatedActor));

        HttpResponse<String> retry = duplicate(actor, city.getId(),
                candidate.getContactPhoneNormalized());
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals("no-store", retry.headers().firstValue("Cache-Control").orElse(""));
        assertFalse(retry.body().contains(candidate.getContactPhoneNormalized()));
        assertEquals(List.of(candidate.getId()), candidateIds(mapper.readTree(retry.body())));
        assertEquals(2L, lookupEventCount(actor),
                "A fresh retry must create a second event under the existing non-idempotent contract");
        assertEquals(0L, lookupEventCount(unrelatedActor));

        Map<String, Object> attribution = jdbc.queryForMap(
                "select count(*) as event_count, min(actor_user_id) as min_actor, "
                        + "max(actor_user_id) as max_actor, "
                        + "count(*) filter (where purpose = 'DUPLICATE_CANDIDATE') as purpose_count "
                        + "from lead_lookup_events where actor_user_id = ?",
                actor.user().getId());
        assertEquals(2L, ((Number) attribution.get("event_count")).longValue());
        assertEquals(actor.user().getId(), ((Number) attribution.get("min_actor")).longValue());
        assertEquals(actor.user().getId(), ((Number) attribution.get("max_actor")).longValue());
        assertEquals(2L, ((Number) attribution.get("purpose_count")).longValue());
        assertEquals(unrelatedBefore, unrelatedOperationalCounts());

        System.out.println("Q24_LOST_ACK server_status=" + lost.serverStatusLine().trim()
                + " client_bytes=" + lost.clientRead()
                + " committed_after_loss=" + 1
                + " committed_after_retry=" + lookupEventCount(actor));
    }

    @Test
    void c05SameActorConcurrentAdmissionSerializesAndNeverExceedsSixty() throws Exception {
        SupportedCity city = city("r05-concurrency");
        OperatingTeam team = team(city, "r05-concurrency");
        Instant recent = Instant.now().minusSeconds(10);
        try {
            Actor sameActor = coordinator("r05-concurrent-same", team);
            LeadRoutingQueue sameCandidate = matchingLead(city, team, sameActor.user(),
                    "r05-concurrent-same-candidate", "+919877777771", LeadStatus.NEW);
            seedLookupEvents(sameActor, 59, recent);
            testJdbc.armLookupActorLockBarrier(sameActor.user().getId());
            CompletableFuture<HttpResponse<String>> first = duplicateAsync(sameActor,
                    city.getId(), sameCandidate.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();
            CompletableFuture<HttpResponse<String>> second = duplicateAsync(sameActor,
                    city.getId(), sameCandidate.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "The second same-actor lookup did not wait on the actor guard");
            assertFalse(second.isDone(), "The second same-actor request reached COUNT while the first held its guard");
            assertTrue(activeDuplicateLookupBackends() >= 2,
                    "Expected distinct PostgreSQL backends for the admitted and waiting lookups");
            testJdbc.releaseLookupActorLockBarrier();
            HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
            assertEquals(200, firstResponse.statusCode(), firstResponse.body());
            assertEquals(429, secondResponse.statusCode(), secondResponse.body());
            assertEquals("no-store",
                    secondResponse.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(60L, lookupEventCount(sameActor),
                    "Per-actor serialization must preserve the strict 60-event ceiling");
            testJdbc.reset();

            Actor actorOne = coordinator("r05-concurrent-one", team);
            Actor actorTwo = coordinator("r05-concurrent-two", team);
            LeadRoutingQueue candidateOne = matchingLead(city, team, actorOne.user(),
                    "r05-concurrent-one-candidate", "+919877777772", LeadStatus.NEW);
            LeadRoutingQueue candidateTwo = matchingLead(city, team, actorTwo.user(),
                    "r05-concurrent-two-candidate", "+919877777773", LeadStatus.NEW);
            seedLookupEvents(actorOne, 59, recent);
            seedLookupEvents(actorTwo, 59, recent);
            CompletableFuture<HttpResponse<String>> one = duplicateAsync(actorOne,
                    city.getId(), candidateOne.getContactPhoneNormalized());
            CompletableFuture<HttpResponse<String>> two = duplicateAsync(actorTwo,
                    city.getId(), candidateTwo.getContactPhoneNormalized());
            assertEquals(200, one.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(200, two.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(60L, lookupEventCount(actorOne));
            assertEquals(60L, lookupEventCount(actorTwo));
        } finally {
            testJdbc.releaseCountBarrier();
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05MultipleConcurrentAdmissionsUseOnlyAvailableActorCapacity() throws Exception {
        SupportedCity city = city("c05-multiple");
        OperatingTeam team = team(city, "c05-multiple");
        Actor actor = coordinator("c05-multiple-actor", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-multiple-candidate", "+919877777774", LeadStatus.NEW);
        seedLookupEvents(actor, 58, Instant.now().minusSeconds(10));

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            requests.add(duplicateAsync(actor, city.getId(),
                    candidate.getContactPhoneNormalized()));
        }
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> request : requests) {
            statuses.add(request.get(20, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(2L, statuses.stream().filter(status -> status == 200).count(),
                statuses.toString());
        assertEquals(2L, statuses.stream().filter(status -> status == 429).count(),
                statuses.toString());
        assertEquals(60L, lookupEventCount(actor));
    }

    @Test
    void c05DifferentActorsInTheSameScopeProgressWithoutMutualExclusion() throws Exception {
        SupportedCity city = city("c05-independent");
        OperatingTeam team = team(city, "c05-independent");
        Actor actorA = coordinator("c05-independent-a", team);
        Actor actorB = coordinator("c05-independent-b", team);
        LeadRoutingQueue candidateA = matchingLead(city, team, actorA.user(),
                "c05-independent-a", "+919877777775", LeadStatus.NEW);
        LeadRoutingQueue candidateB = matchingLead(city, team, actorB.user(),
                "c05-independent-b", "+919877777776", LeadStatus.NEW);
        try {
            testJdbc.armLookupActorLockBarrier(actorA.user().getId());
            CompletableFuture<HttpResponse<String>> held = duplicateAsync(actorA,
                    city.getId(), candidateA.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();

            HttpResponse<String> independent = duplicate(actorB, city.getId(),
                    candidateB.getContactPhoneNormalized());
            assertEquals(200, independent.statusCode(), independent.body());
            assertEquals(1L, lookupEventCount(actorB));
            assertFalse(held.isDone());

            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, held.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(1L, lookupEventCount(actorA));
        } finally {
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05ActorQuotaIsSharedAcrossConcurrentCitiesAndPhones() throws Exception {
        SupportedCity firstCity = city("c05-quota-a");
        SupportedCity secondCity = city("c05-quota-b");
        OperatingTeam firstTeam = team(firstCity, "c05-quota-a");
        OperatingTeam secondTeam = team(secondCity, "c05-quota-b");
        Actor actor = staff("c05-quota-actor",
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, firstTeam),
                grantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, secondTeam));
        LeadRoutingQueue firstCandidate = matchingLead(firstCity, firstTeam, actor.user(),
                "c05-quota-first", "+919877777777", LeadStatus.NEW);
        LeadRoutingQueue secondCandidate = matchingLead(secondCity, secondTeam, actor.user(),
                "c05-quota-second", "+919877777778", LeadStatus.NEW);
        seedLookupEvents(actor, 59, Instant.now().minusSeconds(10));

        CompletableFuture<HttpResponse<String>> first = duplicateAsync(actor,
                firstCity.getId(), firstCandidate.getContactPhoneNormalized());
        CompletableFuture<HttpResponse<String>> second = duplicateAsync(actor,
                secondCity.getId(), secondCandidate.getContactPhoneNormalized());
        List<Integer> statuses = List.of(
                first.get(20, TimeUnit.SECONDS).statusCode(),
                second.get(20, TimeUnit.SECONDS).statusCode());
        assertEquals(1L, statuses.stream().filter(status -> status == 200).count(),
                statuses.toString());
        assertEquals(1L, statuses.stream().filter(status -> status == 429).count(),
                statuses.toString());
        assertEquals(60L, lookupEventCount(actor));
    }

    @Test
    void c05AuthorityRevocationAndExpiryWinningTheWaitAreRecheckedFreshly() throws Exception {
        SupportedCity city = city("c05-authority-wins");
        OperatingTeam team = team(city, "c05-authority-wins");
        Actor revoked = coordinator("c05-authority-revoked", team);
        LeadRoutingQueue revokedCandidate = matchingLead(city, team, revoked.user(),
                "c05-authority-revoked", "+919877777779", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from employee_profiles where user_id = "
                        + revoked.user().getId() + " for update").close();
                statement.executeUpdate("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoke_reason_code = 'C05_TEST_REVOKED' where id = "
                        + revoked.grants().get(0).getId());
            }
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(revoked,
                    city.getId(), revokedCandidate.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Authority wait was not observed before revocation committed");
            writer.commit();
            HttpResponse<String> response = waiting.get(20, TimeUnit.SECONDS);
            assertEquals(403, response.statusCode(), response.body());
            assertEquals("no-store",
                    response.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(0L, lookupEventCount(revoked));
            assertFalse(response.body().contains(revokedCandidate.getContactPhoneNormalized()));
        }

        Actor expired = staff("c05-authority-expired",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        null, team, 2));
        LeadRoutingQueue expiredCandidate = matchingLead(city, team, expired.user(),
                "c05-authority-expired", "+919877777780", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from employee_profiles where user_id = "
                        + expired.user().getId() + " for update").close();
            }
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(expired,
                    city.getId(), expiredCandidate.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Grant-expiry wait was not observed before the writer committed");
            Thread.sleep(2_100);
            writer.commit();
            HttpResponse<String> response = waiting.get(20, TimeUnit.SECONDS);
            assertEquals(403, response.statusCode(), response.body());
            assertEquals(0L, lookupEventCount(expired));
        }
    }

    @Test
    void c05AdmittedLookupBlocksRevocationAndThenFreshLookupDenies() throws Exception {
        SupportedCity city = city("c05-lookup-wins");
        OperatingTeam team = team(city, "c05-lookup-wins");
        Actor actor = coordinator("c05-lookup-wins-actor", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-lookup-wins-candidate", "+919877777781", LeadStatus.NEW);
        try {
            testJdbc.armLookupActorLockBarrier(actor.user().getId());
            CompletableFuture<HttpResponse<String>> admitted = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();

            CompletableFuture<Void> revocation = CompletableFuture.runAsync(() -> {
                try (Connection writer = schemaConnection()) {
                    writer.setAutoCommit(false);
                    try (Statement statement = writer.createStatement()) {
                        statement.executeQuery("select id from employee_profiles where user_id = "
                                + actor.user().getId() + " for update").close();
                        statement.executeUpdate("update staff_access_grants "
                                + "set revoked_at = clock_timestamp(), "
                                + "revoke_reason_code = 'C05_LOOKUP_WON' where id = "
                                + actor.grants().get(0).getId());
                    }
                    writer.commit();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Revocation writer did not wait for the admitted lookup");
            assertFalse(revocation.isDone());
            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, admitted.get(20, TimeUnit.SECONDS).statusCode());
            revocation.get(20, TimeUnit.SECONDS);
            assertEquals(1L, lookupEventCount(actor));

            assertEquals(403, duplicate(actor, city.getId(),
                    candidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(actor));
        } finally {
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05CityDeactivationHonorsBothWriterAndLookupWinningOrders() throws Exception {
        SupportedCity writerWinsCity = city("c05-city-writer");
        OperatingTeam writerWinsTeam = team(writerWinsCity, "c05-city-writer");
        Actor writerWinsActor = coordinator("c05-city-writer-actor", writerWinsTeam);
        LeadRoutingQueue writerWinsLead = matchingLead(writerWinsCity, writerWinsTeam,
                writerWinsActor.user(), "c05-city-writer-lead", "+919877777782", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from supported_cities where id = "
                        + writerWinsCity.getId() + " for update").close();
                statement.executeUpdate("update supported_cities set active = false where id = "
                        + writerWinsCity.getId());
            }
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(writerWinsActor,
                    writerWinsCity.getId(), writerWinsLead.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Lookup did not wait for deactivation writer");
            writer.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(0L, lookupEventCount(writerWinsActor));
        }

        SupportedCity lookupWinsCity = city("c05-city-lookup");
        OperatingTeam lookupWinsTeam = team(lookupWinsCity, "c05-city-lookup");
        Actor lookupWinsActor = coordinator("c05-city-lookup-actor", lookupWinsTeam);
        LeadRoutingQueue lookupWinsLead = matchingLead(lookupWinsCity, lookupWinsTeam,
                lookupWinsActor.user(), "c05-city-lookup-lead", "+919877777783", LeadStatus.NEW);
        try {
            testJdbc.armLookupActorLockBarrier(lookupWinsActor.user().getId());
            CompletableFuture<HttpResponse<String>> admitted = duplicateAsync(lookupWinsActor,
                    lookupWinsCity.getId(), lookupWinsLead.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(() -> {
                try (Connection writer = schemaConnection()) {
                    writer.setAutoCommit(false);
                    try (Statement statement = writer.createStatement()) {
                        statement.executeUpdate("update supported_cities set active = false where id = "
                                + lookupWinsCity.getId());
                    }
                    writer.commit();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "City deactivation writer did not wait for the lookup guard");
            assertFalse(deactivation.isDone());
            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, admitted.get(20, TimeUnit.SECONDS).statusCode());
            deactivation.get(20, TimeUnit.SECONDS);
            assertEquals(403, duplicate(lookupWinsActor, lookupWinsCity.getId(),
                    lookupWinsLead.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(lookupWinsActor));
        } finally {
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05TeamDeactivationHonorsSharedGuardConflictAndFreshDenial() throws Exception {
        SupportedCity city = city("c05-team-deactivation");
        OperatingTeam writerWinsTeam = team(city, "c05-team-writer");
        Actor writerWinsActor = coordinator("c05-team-writer-actor", writerWinsTeam);
        LeadRoutingQueue writerWinsLead = matchingLead(city, writerWinsTeam,
                writerWinsActor.user(), "c05-team-writer-lead", "+919877777791", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from operating_teams where id = "
                        + writerWinsTeam.getId() + " for update").close();
                statement.executeUpdate("update operating_teams set active = false where id = "
                        + writerWinsTeam.getId());
            }
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(writerWinsActor,
                    city.getId(), writerWinsLead.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Lookup did not wait for Team deactivation writer");
            writer.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(0L, lookupEventCount(writerWinsActor));
        }

        OperatingTeam lookupWinsTeam = team(city, "c05-team-lookup");
        Actor lookupWinsActor = coordinator("c05-team-lookup-actor", lookupWinsTeam);
        LeadRoutingQueue lookupWinsLead = matchingLead(city, lookupWinsTeam,
                lookupWinsActor.user(), "c05-team-lookup-lead", "+919877777792", LeadStatus.NEW);
        try {
            testJdbc.armLookupActorLockBarrier(lookupWinsActor.user().getId());
            CompletableFuture<HttpResponse<String>> admitted = duplicateAsync(lookupWinsActor,
                    city.getId(), lookupWinsLead.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(() -> {
                try (Connection writer = schemaConnection()) {
                    writer.setAutoCommit(false);
                    try (Statement statement = writer.createStatement()) {
                        statement.executeUpdate("update operating_teams set active = false where id = "
                                + lookupWinsTeam.getId());
                    }
                    writer.commit();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Team deactivation writer did not wait for the lookup guard");
            assertFalse(deactivation.isDone());
            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, admitted.get(20, TimeUnit.SECONDS).statusCode());
            deactivation.get(20, TimeUnit.SECONDS);
            assertEquals(403, duplicate(lookupWinsActor, city.getId(),
                    lookupWinsLead.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(lookupWinsActor));
        } finally {
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05GlobalDiagnosticsPreserveCanonicalAndUnknownTargetBehavior() throws Exception {
        Actor global = staff("c05-global-diagnostic",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        assertEquals(1, grants.findEffectiveForUser(global.user().getId()).size(),
                "the global diagnostic actor must have one current effective grant");
        HttpResponse<String> unknown = duplicate(global, Long.MAX_VALUE - 100, "+919877777784");
        assertEquals(200, unknown.statusCode(), unknown.body());
        assertTrue(mapper.readTree(unknown.body()).path("candidates").isEmpty());
        assertEquals("no-store", unknown.headers().firstValue("Cache-Control").orElse(""));

        SupportedCity inactiveCity = city("c05-global-inactive");
        OperatingTeam inactiveTeam = team(inactiveCity, "c05-global-inactive");
        LeadRoutingQueue privateLead = matchingLead(inactiveCity, inactiveTeam, null,
                "c05-global-private", "+919877777785", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("update operating_teams set active = false where id = "
                        + inactiveTeam.getId());
                statement.executeUpdate("update supported_cities set active = false where id = "
                        + inactiveCity.getId());
            }
            writer.commit();
        }
        HttpResponse<String> inactive = duplicate(global, inactiveCity.getId(),
                privateLead.getContactPhoneNormalized());
        assertEquals(200, inactive.statusCode(), inactive.body());
        assertEquals(List.of(privateLead.getId()),
                candidateIds(mapper.readTree(inactive.body())));
        assertEquals(2L, lookupEventCount(global));

        Actor revokedGlobal = staff("c05-global-revoked",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        LeadRoutingQueue globalCandidate = matchingLead(inactiveCity, inactiveTeam, null,
                "c05-global-revoked", "+919877777793", LeadStatus.NEW);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from employee_profiles where user_id = "
                        + revokedGlobal.user().getId() + " for update").close();
                statement.executeUpdate("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoked_by_user_id = " + revokedGlobal.user().getId()
                        + ", revoke_reason_code = 'C05_GLOBAL_REVOKED' where id = "
                        + revokedGlobal.grants().get(0).getId());
            }
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(revokedGlobal,
                    inactiveCity.getId(), globalCandidate.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Global authority wait was not observed");
            writer.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(0L, lookupEventCount(revokedGlobal));
        }
    }

    @Test
    void c05RollbackReleasesActorCapacityForAWaitingRequest() throws Exception {
        SupportedCity city = city("c05-rollback-waiter");
        OperatingTeam team = team(city, "c05-rollback-waiter");
        Actor actor = coordinator("c05-rollback-waiter-actor", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-rollback-waiter-candidate", "+919877777786", LeadStatus.NEW);
        try {
            testJdbc.armLookupInsertBarrier(actor.user().getId());
            FAIL_ACCOUNT_INDICATOR.set(true);
            CompletableFuture<HttpResponse<String>> failing = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            testJdbc.awaitLookupInsertBarrier();
            CompletableFuture<HttpResponse<String>> waiting = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 5,
                    "Waiting request did not block behind the inserted-but-uncommitted admission");
            testJdbc.releaseLookupInsertBarrier();
            HttpResponse<String> failed = failing.get(20, TimeUnit.SECONDS);
            assertEquals(500, failed.statusCode(), failed.body());
            HttpResponse<String> recovered = waiting.get(20, TimeUnit.SECONDS);
            assertEquals(200, recovered.statusCode(), recovered.body());
            assertEquals(1L, lookupEventCount(actor),
                    "Only the successful waiting lookup may leave durable evidence");
        } finally {
            testJdbc.releaseLookupInsertBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05OwnershipChangedBeforeCandidateSelectExcludesTheFormerOwner() throws Exception {
        SupportedCity city = city("c05-ownership-race");
        OperatingTeam team = team(city, "c05-ownership-race");
        Actor actor = coordinator("c05-ownership-race-actor", team);
        Actor peer = coordinator("c05-ownership-race-peer", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-ownership-race-candidate", "+919877777794", LeadStatus.NEW);
        try {
            testJdbc.armLookupInsertBarrier(actor.user().getId());
            CompletableFuture<HttpResponse<String>> lookup = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            testJdbc.awaitLookupInsertBarrier();
            try (Connection writer = schemaConnection()) {
                writer.setAutoCommit(false);
                try (Statement statement = writer.createStatement()) {
                    statement.executeUpdate("update lead_routing_queue set owner_user_id = "
                            + peer.user().getId() + " where id = " + candidate.getId());
                }
                writer.commit();
            }
            testJdbc.releaseLookupInsertBarrier();
            HttpResponse<String> response = lookup.get(20, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(candidateIds(mapper.readTree(response.body())).isEmpty(),
                    "A committed ownership transfer before candidate SELECT must exclude the former owner");
            assertEquals(1L, lookupEventCount(actor));
        } finally {
            testJdbc.releaseLookupInsertBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05ContentionTimesOutCleanlyWithoutPoisoningThePool() throws Exception {
        SupportedCity city = city("c05-timeout");
        OperatingTeam team = team(city, "c05-timeout");
        Actor actor = coordinator("c05-timeout-actor", team);
        Actor independent = coordinator("c05-timeout-independent", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-timeout-candidate", "+919877777787", LeadStatus.NEW);
        LeadRoutingQueue independentCandidate = matchingLead(city, team, independent.user(),
                "c05-timeout-independent", "+919877777788", LeadStatus.NEW);
        try {
            testJdbc.armLookupActorLockBarrier(actor.user().getId());
            CompletableFuture<HttpResponse<String>> admitted = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();
            CompletableFuture<HttpResponse<String>> timedOut = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            HttpResponse<String> timeoutResponse = timedOut.get(12, TimeUnit.SECONDS);
            assertEquals(503, timeoutResponse.statusCode(), timeoutResponse.body());
            assertEquals("no-store",
                    timeoutResponse.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(timeoutResponse.body().contains("55P03"));
            assertFalse(timeoutResponse.body().contains("employee_profiles"));
            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, admitted.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(1L, lookupEventCount(actor));
            assertEquals(200, duplicate(independent, city.getId(),
                    independentCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(independent));
        } finally {
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void c05RealPostgresDeadlockCancellationAndTimeoutAbortTheLookupTransaction()
            throws Exception {
        SupportedCity city = city("c05-real-sqlstates");
        OperatingTeam team = team(city, "c05-real-sqlstates");
        Actor deadlockFirst = coordinator("c05-real-deadlock-first", team);
        Actor deadlockSecond = coordinator("c05-real-deadlock-second", team);
        LeadRoutingQueue firstCandidate = matchingLead(city, team, deadlockFirst.user(),
                "c05-real-deadlock-first-candidate", "+919877777781", LeadStatus.NEW);
        LeadRoutingQueue secondCandidate = matchingLead(city, team, deadlockSecond.user(),
                "c05-real-deadlock-second-candidate", "+919877777782", LeadStatus.NEW);
        int firstKey = ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
        int secondKey = ThreadLocalRandom.current().nextInt(1_000_000_001, Integer.MAX_VALUE - 1);
        try {
            installDeadlockInversion(deadlockFirst, firstKey, deadlockSecond, secondKey);
            testJdbc.captureRealInsertFailures(deadlockFirst.user().getId(),
                    deadlockSecond.user().getId());
            CompletableFuture<HttpResponse<String>> first = duplicateAsync(deadlockFirst,
                    city.getId(), firstCandidate.getContactPhoneNormalized());
            Thread.sleep(100);
            CompletableFuture<HttpResponse<String>> second = duplicateAsync(deadlockSecond,
                    city.getId(), secondCandidate.getContactPhoneNormalized());
            HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(200, 500),
                    Set.copyOf(List.of(firstResponse.statusCode(), secondResponse.statusCode())),
                    firstResponse.body() + " | " + secondResponse.body());

            RealSqlFailure deadlockFailure = singleRealSqlFailure();
            assertEquals("40P01", deadlockFailure.sqlState());
            assertTrue(deadlockFailure.message().contains("deadlock detected"),
                    deadlockFailure.message());
            assertTrue(deadlockFailure.backendPid() > 0);
            Actor deadlockVictim = deadlockFailure.actorId().equals(deadlockFirst.user().getId())
                    ? deadlockFirst : deadlockSecond;
            HttpResponse<String> deadlockResponse = deadlockVictim == deadlockFirst
                    ? firstResponse : secondResponse;
            assertEquals(500, deadlockResponse.statusCode(), deadlockResponse.body());
            assertFalse(deadlockResponse.body().contains("40P01"));
            assertEquals(0L, lookupEventCount(deadlockVictim),
                    "The AFTER INSERT event must be rolled back with the server-aborted transaction");
            Actor deadlockWinner = deadlockVictim == deadlockFirst ? deadlockSecond : deadlockFirst;
            assertEquals(1L, lookupEventCount(deadlockWinner));
            assertEquals(0L, advisoryLockHolderCount(firstKey, secondKey));
            System.out.println("Q22_REAL_DEADLOCK backend_pid=" + deadlockFailure.backendPid()
                    + " sqlstate=" + deadlockFailure.sqlState()
                    + " message=" + deadlockFailure.message()
                    + " victim_events=" + lookupEventCount(deadlockVictim)
                    + " winner_events=" + lookupEventCount(deadlockWinner));

            dropC05LookupHook();
            testJdbc.reset();
            assertEquals(200, duplicate(deadlockVictim, city.getId(),
                    deadlockVictim == deadlockFirst
                            ? firstCandidate.getContactPhoneNormalized()
                            : secondCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(deadlockVictim),
                    "A fresh request must recover after the deadlock released the guards");

            Actor canceledActor = coordinator("c05-real-cancelled", team);
            LeadRoutingQueue canceledCandidate = matchingLead(city, team, canceledActor.user(),
                    "c05-real-cancelled-candidate", "+919877777783", LeadStatus.NEW);
            int cancellationKey = ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);
            installLookupAdvisoryBlock(canceledActor, cancellationKey, null);
            testJdbc.captureRealInsertFailures(canceledActor.user().getId());
            CompletableFuture<HttpResponse<String>> canceled = duplicateAsync(canceledActor,
                    city.getId(), canceledCandidate.getContactPhoneNormalized());
            long canceledBackend = awaitAdvisoryHolder(cancellationKey, canceled);
            assertTrue(jdbc.queryForObject("select pg_cancel_backend(?)", Boolean.class,
                    (int) canceledBackend));
            HttpResponse<String> cancellation = canceled.get(20, TimeUnit.SECONDS);
            assertEquals(500, cancellation.statusCode(), cancellation.body());
            RealSqlFailure cancellationFailure = singleRealSqlFailure();
            assertEquals("57014", cancellationFailure.sqlState());
            assertTrue(cancellationFailure.message().contains("user request"),
                    cancellationFailure.message());
            assertFalse(cancellationFailure.message().toLowerCase(Locale.ROOT)
                    .contains("timeout"), cancellationFailure.message());
            assertEquals((int) canceledBackend, cancellationFailure.backendPid());
            assertEquals(0L, lookupEventCount(canceledActor),
                    "The inserted event must roll back on non-timeout cancellation");
            assertEquals(0L, advisoryLockHolderCount(cancellationKey));
            System.out.println("Q22_REAL_CANCELLATION backend_pid=" + canceledBackend
                    + " sqlstate=" + cancellationFailure.sqlState()
                    + " message=" + cancellationFailure.message()
                    + " events=" + lookupEventCount(canceledActor));

            dropC05LookupHook();
            testJdbc.reset();
            assertEquals(200, duplicate(canceledActor, city.getId(),
                    canceledCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(canceledActor));

            Actor timedOutActor = coordinator("c05-real-timeout", team);
            LeadRoutingQueue timedOutCandidate = matchingLead(city, team, timedOutActor.user(),
                    "c05-real-timeout-candidate", "+919877777784", LeadStatus.NEW);
            installLookupAdvisoryBlock(timedOutActor, 0, 12);
            testJdbc.captureRealInsertFailures(timedOutActor.user().getId());
            HttpResponse<String> timedOut = duplicate(timedOutActor, city.getId(),
                    timedOutCandidate.getContactPhoneNormalized());
            assertEquals(503, timedOut.statusCode(), timedOut.body());
            assertEquals("no-store",
                    timedOut.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(timedOut.body().contains("57014"));
            RealSqlFailure timeoutFailure = singleRealSqlFailure();
            assertEquals("57014", timeoutFailure.sqlState());
            assertTrue(timeoutFailure.message().toLowerCase(Locale.ROOT).contains("timeout"),
                    timeoutFailure.message());
            assertEquals(0L, lookupEventCount(timedOutActor),
                    "The inserted event must roll back on the configured statement timeout");
            System.out.println("Q22_REAL_STATEMENT_TIMEOUT backend_pid="
                    + timeoutFailure.backendPid() + " sqlstate=" + timeoutFailure.sqlState()
                    + " message=" + timeoutFailure.message()
                    + " events=" + lookupEventCount(timedOutActor));

            dropC05LookupHook();
            testJdbc.reset();
            assertEquals(200, duplicate(timedOutActor, city.getId(),
                    timedOutCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(timedOutActor));

            Actor deferredActor = coordinator("c05-real-deferred-commit", team);
            LeadRoutingQueue deferredCandidate = matchingLead(city, team, deferredActor.user(),
                    "c05-real-deferred-commit-candidate", "+919877777785", LeadStatus.NEW);
            jdbc.execute("create or replace function c05_reject_lookup_commit() returns trigger "
                    + "language plpgsql as $c05$ begin "
                    + "raise exception 'C05 injected deferred commit failure'; end $c05$");
            jdbc.execute("create constraint trigger c05_reject_lookup_commit "
                    + "after insert on lead_lookup_events deferrable initially deferred "
                    + "for each row execute function c05_reject_lookup_commit()");
            try {
                HttpResponse<String> commitFailure = duplicate(deferredActor, city.getId(),
                        deferredCandidate.getContactPhoneNormalized());
                assertEquals(500, commitFailure.statusCode(), commitFailure.body());
                assertEquals(0L, lookupEventCount(deferredActor));
            } finally {
                dropC05DeferredCommitHook();
            }
            assertEquals(200, duplicate(deferredActor, city.getId(),
                    deferredCandidate.getContactPhoneNormalized()).statusCode());
            assertEquals(1L, lookupEventCount(deferredActor));
        } finally {
            dropC05LookupHook();
            dropC05DeferredCommitHook();
            testJdbc.reset();
        }
    }

    @Test
    void c05DatabaseClockIsReadAfterGuardAcquisition() throws Exception {
        SupportedCity city = city("c05-fresh-clock");
        OperatingTeam team = team(city, "c05-fresh-clock");
        Actor actor = coordinator("c05-fresh-clock-actor", team);
        LeadRoutingQueue candidate = matchingLead(city, team, actor.user(),
                "c05-fresh-clock-candidate", "+919877777790", LeadStatus.NEW);
        Instant beforeWait = Instant.parse("2026-10-22T05:00:00.111111Z");
        Instant afterWait = Instant.parse("2026-10-22T05:00:01.222222Z");
        try {
            testClock.fixAt(beforeWait);
            testJdbc.armLookupActorLockBarrier(actor.user().getId());
            CompletableFuture<HttpResponse<String>> admitted = duplicateAsync(actor,
                    city.getId(), candidate.getContactPhoneNormalized());
            testJdbc.awaitLookupActorLockBarrier();
            testClock.fixAt(afterWait);
            testJdbc.releaseLookupActorLockBarrier();
            assertEquals(200, admitted.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(afterWait, latestLookupEvent(actor));
        } finally {
            testClock.useDatabaseClock();
            testJdbc.releaseLookupActorLockBarrier();
            testJdbc.reset();
        }
    }

    @Test
    void v52LegacySourceRowsRemainExplicitlyVersionedThroughV53Upgrade() {
        metaLeadInbox.acceptMetaLead("legacy-page", "legacy-event", "legacy-form",
                "b".repeat(64), "EVENT_CANONICAL_V2");
        assertEquals("LEGACY_ENVELOPE_V1", jdbc.queryForObject(
                "select fingerprint_version from lead_source_events where id = ?",
                String.class, LEGACY_SOURCE_ID));
        assertEquals("LEGACY_FINGERPRINT_RECONCILIATION_REQUIRED", jdbc.queryForObject(
                "select error_code from lead_source_events where id = ?",
                String.class, LEGACY_SOURCE_ID));
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "select processing_state from lead_source_events where id = ?",
                String.class, LEGACY_SOURCE_ID));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_source_events where id = ? "
                        + "and provider_account_id = 'legacy-page' and external_event_id = 'legacy-event' "
                        + "and provider_form_id = 'legacy-form' and payload_hash = ?",
                Long.class, LEGACY_SOURCE_ID, "a".repeat(64)));
    }

    @Test
    void fractionalLocalityIdIsRejectedBeforeAnyLeadOrChildMutation() throws Exception {
        SupportedCity city = city("fractional");
        OperatingTeam team = team(city, "fractional");
        Actor owner = coordinator("fractional-owner", team);
        locality(city, "fractional");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "fractional-patch");
        long version = lead.getVersion();

        HttpResponse<String> response = send("PATCH", "/api/v1/operations/leads/" + lead.getId(),
                owner.token(), "{\"expectedVersion\":" + version
                        + ",\"changes\":{\"preferredLocalityIds\":[1.9]}}");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(version, leads.findById(lead.getId()).orElseThrow().getVersion());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_preferred_localities where lead_id = ?",
                Long.class, lead.getId()));
    }

    @Test
    void contactConsentIsEncryptedVersionBoundAndOwnerScoped() throws Exception {
        SupportedCity city = city("contact-consent");
        OperatingTeam team = team(city, "contact-consent");
        Actor owner = coordinator("contact-consent-owner", team);
        Actor peer = coordinator("contact-consent-peer", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "contact-consent");
        String command = "{\"expectedLeadVersion\":" + lead.getVersion()
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\""
                + ",\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";

        HttpResponse<String> recorded = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), command);
        assertEquals(201, recorded.statusCode(), recorded.body());
        JsonNode preference = mapper.readTree(recorded.body());
        assertEquals(1L, preference.path("revision").asLong());
        assertEquals("********3210", preference.path("maskedDestination").asText());
        assertEquals("GRANTED", preference.path("consentStatus").asText());
        assertEquals("PROPERTY_SHARING", preference.path("consentPurpose").asText());
        assertEquals("property-sharing-v1", preference.path("consentWordingVersion").asText());
        assertEquals("CUSTOMER_VERBAL", preference.path("evidenceMethod").asText());
        long leadVersion = preference.path("leadVersion").asLong();
        assertTrue(leadVersion > lead.getVersion());

        String ciphertext = jdbc.queryForObject(
                "select destination_ciphertext from lead_contact_preferences "
                        + "where lead_id = ? and revision = 1",
                String.class, lead.getId());
        String iv = jdbc.queryForObject(
                "select destination_iv from lead_contact_preferences "
                        + "where lead_id = ? and revision = 1",
                String.class, lead.getId());
        assertTrue(ciphertext != null && ciphertext.length() >= 16);
        assertTrue(iv != null && iv.length() >= 12);
        assertTrue(!ciphertext.contains("9876543210"));
        assertEquals("primary", jdbc.queryForObject(
                "select destination_key_version from lead_contact_preferences "
                        + "where lead_id = ? and revision = 1",
                String.class, lead.getId()));

        HttpResponse<String> history = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), null);
        assertEquals(200, history.statusCode(), history.body());
        assertTrue(history.body().contains("********3210"));
        assertTrue(!history.body().contains("+919876543210"));

        HttpResponse<String> reveal = send("GET",
                "/api/v1/operations/leads/" + lead.getId()
                        + "/contact-preferences/1/destination",
                owner.token(), null);
        assertEquals(200, reveal.statusCode(), reveal.body());
        assertEquals("+919876543210", mapper.readTree(reveal.body()).path("fullDestination").asText());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from operational_audit_events "
                        + "where action_code = 'LEAD_DESTINATION_REVEALED' and target_id = ?",
                Long.class, lead.getId()));

        assertEquals(404, send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                peer.token(), null).statusCode());

        String stale = "{\"expectedLeadVersion\":" + leadVersion
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\""
                + ",\"destinationChoice\":\"NONE\",\"consentStatus\":\"REVOKED\"}";
        HttpResponse<String> staleResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), stale);
        assertEquals(409, staleResponse.statusCode(), staleResponse.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_contact_preferences where lead_id = ?",
                Long.class, lead.getId()));
    }

    @Test
    void currentPublicShortlistAndManualShareAreOrderedIdempotentAndTruthful() throws Exception {
        SupportedCity city = city("share-lifecycle");
        OperatingTeam team = team(city, "share-lifecycle");
        Actor owner = coordinator("share-lifecycle-owner", team);
        Locality locality = locality(city, "share-lifecycle");
        RentalDetails first = publicListing(locality, "First synthetic home");
        RentalDetails second = publicListing(locality, "Second synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-lifecycle");

        String consent = "{\"expectedLeadVersion\":" + lead.getVersion()
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\""
                + ",\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), consent);
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode consentView = mapper.readTree(consentResponse.body());
        long preferenceRevision = consentView.path("revision").asLong();
        long leadVersion = consentView.path("leadVersion").asLong();

        UUID shortlistOperation = UUID.randomUUID();
        String shortlistBody = "{\"expectedLeadVersion\":" + leadVersion
                + ",\"listingIds\":[" + first.getId() + "," + second.getId() + "]}";
        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists",
                owner.token(), shortlistBody, "Idempotency-Key", shortlistOperation.toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        long shortlistId = shortlist.path("id").asLong();
        long shortlistVersion = shortlist.path("version").asLong();
        leadVersion = shortlist.path("leadVersion").asLong();
        assertEquals(first.getId(), shortlist.path("listingIds").get(0).asLong());
        assertEquals(second.getId(), shortlist.path("listingIds").get(1).asLong());

        HttpResponse<String> replay = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists",
                owner.token(), shortlistBody, "Idempotency-Key", shortlistOperation.toString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(shortlistId, mapper.readTree(replay.body()).path("id").asLong());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shortlists where lead_id = ?", Long.class, lead.getId()));

        UUID shareOperation = UUID.randomUUID();
        String shareBody = "{\"operationId\":\"" + shareOperation + "\""
                + ",\"expectedLeadVersion\":" + leadVersion
                + ",\"shortlistId\":" + shortlistId
                + ",\"expectedShortlistVersion\":" + shortlistVersion
                + ",\"contactPreferenceRevision\":" + preferenceRevision
                + ",\"mode\":\"COPY_LINKS\"}";
        HttpResponse<String> shareResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), shareBody);
        assertEquals(201, shareResponse.statusCode(), shareResponse.body());
        JsonNode share = mapper.readTree(shareResponse.body());
        UUID shareId = UUID.fromString(share.path("id").asText());
        assertEquals("https://pathome.test/property/" + first.getId(),
                share.path("publicLinks").get(0).asText());
        assertEquals("https://pathome.test/property/" + second.getId(),
                share.path("publicLinks").get(1).asText());
        assertEquals("********3210", share.path("maskedDestination").asText());
        assertTrue(!share.path("previewText").asText().contains("+919876543210"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? and activity_type = 'SHARE_PREPARED'",
                Long.class, lead.getId()));

        HttpResponse<String> shareReplay = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), shareBody);
        assertEquals(201, shareReplay.statusCode(), shareReplay.body());
        assertEquals(shareId, UUID.fromString(mapper.readTree(shareReplay.body()).path("id").asText()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? and activity_type = 'SHARE_PREPARED'",
                Long.class, lead.getId()));

        UUID eventId = UUID.randomUUID();
        String eventBody = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"CLIPBOARD_COPIED\"}";
        HttpResponse<String> event = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares/" + shareId + "/events",
                owner.token(), eventBody);
        assertEquals(200, event.statusCode(), event.body());
        HttpResponse<String> eventReplay = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares/" + shareId + "/events",
                owner.token(), eventBody);
        assertEquals(200, eventReplay.statusCode(), eventReplay.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?", Long.class, eventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY' and note = 'CLIPBOARD_COPIED'",
                Long.class, lead.getId()));
        assertTrue(jdbc.queryForObject(
                "select count(*) from lead_share_events where event_type like '%DELIVERED%' or event_type like '%READ%'",
                Long.class) == 0L);
    }

    @Test
    void finalHandoffEventRevalidatesConsentDestinationLifecycleScopeAndPublicTruth()
            throws Exception {
        SupportedCity city = city("final-handoff-gate");
        OperatingTeam team = team(city, "final-handoff-gate");
        Actor owner = coordinator("final-handoff-owner", team);
        Actor peer = coordinator("final-handoff-peer", team);
        Locality locality = locality(city, "final-handoff-gate");
        RentalDetails property = publicListing(locality, "Final handoff synthetic home");
        RentalDetails unavailableProperty =
                publicListing(locality, "Final handoff unavailable synthetic home");
        RentalDetails lockedProperty =
                publicListing(locality, "Final handoff locked synthetic home");

        PreparedHandoff valid = prepareFinalHandoff(owner, city, team, property, "valid handoff");
        UUID validEvent = UUID.randomUUID();
        String validBody = "{\"eventId\":\"" + validEvent
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        HttpResponse<String> recorded = send("POST",
                "/api/v1/operations/leads/" + valid.lead().getId() + "/shares/"
                        + valid.shareId() + "/events",
                owner.token(), validBody);
        assertEquals(200, recorded.statusCode(), recorded.body());
        HttpResponse<String> replay = send("POST",
                "/api/v1/operations/leads/" + valid.lead().getId() + "/shares/"
                        + valid.shareId() + "/events",
                owner.token(), validBody);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, validEvent));

        PreparedHandoff revoked = prepareFinalHandoff(
                owner, city, team, property, "revoked consent");
        HttpResponse<String> withdraw = send("POST",
                "/api/v1/operations/leads/" + revoked.lead().getId()
                        + "/contact-preferences",
                owner.token(), contactBody(revoked.shortlist().path("leadVersion").asLong(),
                        revoked.preference().path("revision").asLong(),
                        "NONE", null, null, "REVOKED"));
        assertEquals(201, withdraw.statusCode(), withdraw.body());
        assertEquals(409, finalHandoffAttempt(owner, revoked).statusCode());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, revoked.shareId()));

        PreparedHandoff disputed = prepareFinalHandoff(
                owner, city, team, property, "disputed consent");
        HttpResponse<String> dispute = send("POST",
                "/api/v1/operations/leads/" + disputed.lead().getId()
                        + "/contact-preferences",
                owner.token(), contactBody(disputed.shortlist().path("leadVersion").asLong(),
                        disputed.preference().path("revision").asLong(),
                        "NONE", null, null, "DISPUTED"));
        assertEquals(201, dispute.statusCode(), dispute.body());
        assertEquals(409, finalHandoffAttempt(owner, disputed).statusCode());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, disputed.shareId()));

        PreparedHandoff destination = prepareFinalHandoff(
                owner, city, team, property, "changed destination");
        HttpResponse<String> changedDestination = send("POST",
                "/api/v1/operations/leads/" + destination.lead().getId()
                        + "/contact-preferences",
                owner.token(), contactBody(destination.shortlist().path("leadVersion").asLong(),
                        destination.preference().path("revision").asLong(),
                        "DIFFERENT", "+1", "4155550123", "GRANTED"));
        assertEquals(201, changedDestination.statusCode(), changedDestination.body());
        assertEquals(409, finalHandoffAttempt(owner, destination).statusCode());

        PreparedHandoff reassigned = prepareFinalHandoff(
                owner, city, team, property, "reassigned lead");
        jdbc.update("update lead_routing_queue set owner_user_id = ? where id = ?",
                peer.user().getId(), reassigned.lead().getId());
        assertEquals(404, finalHandoffAttempt(owner, reassigned).statusCode());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, reassigned.shareId()));

        PreparedHandoff closed = prepareFinalHandoff(
                owner, city, team, property, "closed lead");
        jdbc.update("update lead_routing_queue set status = 'CLOSED', version = version + 1 "
                + "where id = ?", closed.lead().getId());
        assertEquals(409, finalHandoffAttempt(owner, closed).statusCode());

        PreparedHandoff changedShortlist = prepareFinalHandoff(
                owner, city, team, property, "changed shortlist");
        jdbc.update("update lead_shortlists set version = version + 1 where id = ?",
                changedShortlist.shortlist().path("id").asLong());
        assertEquals(409, finalHandoffAttempt(owner, changedShortlist).statusCode());

        PreparedHandoff unavailable = prepareFinalHandoff(
                owner, city, team, unavailableProperty, "unpublished property");
        unavailableProperty.setStatus(ListingStatus.CLOSED);
        listings.saveAndFlush(unavailableProperty);
        assertEquals(409, finalHandoffAttempt(owner, unavailable).statusCode());

        PreparedHandoff locked = prepareFinalHandoff(
                owner, city, team, lockedProperty, "locked property facts");
        try (Connection locker = schemaConnection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.executeQuery("select id from listings where id = "
                        + lockedProperty.getId() + " for update").close();
                CompletableFuture<HttpResponse<String>> waiting =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return finalHandoffAttempt(owner, locked);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedFinalListingLockCount() >= 1, 10,
                        "Final handoff did not wait for the selected listing row");
                assertFalse(waiting.isDone());
                statement.executeUpdate("update listings set title = title || ' changed', "
                        + "version = version + 1 where id = " + lockedProperty.getId());
                locker.commit();
                assertEquals(409, waiting.get(20, TimeUnit.SECONDS).statusCode());
            }
        }

        PreparedHandoff revokedGrant = prepareFinalHandoff(
                owner, city, team, property, "revoked staff grant");
        StaffAccessGrant ownerGrant = owner.grants().get(0);
        ownerGrant.revoke(owner.user(), Instant.now(), "P1_FINAL_HANDOFF_REVOKED");
        grants.saveAndFlush(ownerGrant);
        assertTrue(Set.of(403, 404).contains(
                finalHandoffAttempt(owner, revokedGrant).statusCode()));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id in (?, ?, ?, ?)",
                Long.class, revoked.shareId(), disputed.shareId(), destination.shareId(),
                revokedGrant.shareId()));
    }

    @Test
    void grantExpiryDuringFinalListingLockWaitDeniesBothGuardedEvents() throws Exception {
        SupportedCity city = city("share-grant-expiry-wait");
        OperatingTeam team = team(city, "share-grant-expiry-wait");
        Locality locality = locality(city, "share-grant-expiry-wait");

        for (String type : List.of("HANDOFF_ATTEMPTED", "CLIPBOARD_COPIED")) {
            Actor owner = staff("share-grant-expiry-" + type.toLowerCase(Locale.ROOT),
                    expiringGrantSpec(StaffCapability.OPS_COORDINATE,
                            StaffScopeType.TEAM, null, team, 10));
            RentalDetails property = publicListing(locality,
                    "Grant expiry " + type + " synthetic home");
            PreparedHandoff prepared = prepareFinalHandoff(
                    owner, city, team, property, "grant expiry " + type);
            Instant expiresAt = owner.grants().get(0).getExpiresAt();
            assertTrue(expiresAt.isAfter(databaseNow()),
                    type + " grant expired before the lock-wait witness began");
            UUID eventId = UUID.randomUUID();
            String body = "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\"}";
            long auditBefore = jdbc.queryForObject(
                    "select count(*) from operational_audit_events "
                            + "where target_type = 'LEAD' and target_id = ?",
                    Long.class, prepared.lead().getId());
            long receiptsBefore = jdbc.queryForObject(
                    "select count(*) from lead_command_receipts where actor_user_id = ?",
                    Long.class, owner.user().getId());
            long blockedBefore = blockedFinalListingLockCount();

            try (Connection locker = schemaConnection()) {
                locker.setAutoCommit(false);
                try (Statement statement = locker.createStatement()) {
                    statement.executeQuery("select id from listings where id = "
                            + property.getId() + " for update").close();
                    CompletableFuture<HttpResponse<String>> waiting =
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return send("POST",
                                            "/api/v1/operations/leads/" + prepared.lead().getId()
                                                    + "/shares/" + prepared.shareId() + "/events",
                                            owner.token(), body);
                                } catch (Exception exception) {
                                    throw new CompletionException(exception);
                                }
                            });
                    awaitCondition(() -> blockedFinalListingLockCount() > blockedBefore, 10,
                            type + " did not wait for the final listing lock");
                    assertFalse(waiting.isDone(),
                            type + " was admitted before the listing lock was released");
                    awaitCondition(() -> !databaseNow().isBefore(expiresAt), 10,
                            type + " grant did not reach its database-time expiry boundary");
                    locker.commit();
                    HttpResponse<String> denied = waiting.get(20, TimeUnit.SECONDS);
                    assertTrue(Set.of(403, 404).contains(denied.statusCode()), denied.body());
                    assertEquals("no-store",
                            denied.headers().firstValue("Cache-Control").orElse(""));
                }
            }

            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_share_events where id = ?",
                    Long.class, eventId));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_activities where lead_id = ? "
                            + "and activity_type = 'SHARE_ACTIVITY'",
                    Long.class, prepared.lead().getId()));
            assertEquals(auditBefore, jdbc.queryForObject(
                    "select count(*) from operational_audit_events "
                            + "where target_type = 'LEAD' and target_id = ?",
                    Long.class, prepared.lead().getId()));
            assertEquals(receiptsBefore, jdbc.queryForObject(
                    "select count(*) from lead_command_receipts where actor_user_id = ?",
                    Long.class, owner.user().getId()));
        }
    }

    @Test
    void secondEffectiveGrantPermitsAdmissionWhenTheFirstExpiresDuringTheWait()
            throws Exception {
        SupportedCity city = city("share-second-grant");
        OperatingTeam team = team(city, "share-second-grant");
        Locality locality = locality(city, "share-second-grant");
        Actor owner = staff("share-second-grant-owner",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team, 10),
                grantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team));
        RentalDetails property = publicListing(locality, "Second grant synthetic home");
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team, property, "second grant remains");
        Instant firstExpiry = owner.grants().get(0).getExpiresAt();
        assertTrue(firstExpiry.isAfter(databaseNow()));
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long blockedBefore = blockedFinalListingLockCount();

        try (Connection locker = schemaConnection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.executeQuery("select id from listings where id = "
                        + property.getId() + " for update").close();
                CompletableFuture<HttpResponse<String>> waiting =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), body);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedFinalListingLockCount() > blockedBefore, 10,
                        "Second-grant handoff did not wait for the final listing lock");
                assertFalse(waiting.isDone());
                awaitCondition(() -> !databaseNow().isBefore(firstExpiry), 10,
                        "First grant did not expire during the listing wait");
                locker.commit();
                HttpResponse<String> admitted = waiting.get(20, TimeUnit.SECONDS);
                assertEquals(200, admitted.statusCode(), admitted.body());
            }
        }

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
    }

    @Test
    void databaseTimeEqualityBoundaryUsesStrictLessThanAndCurrentRepositoryPredicate()
            throws Exception {
        SupportedCity city = city("share-exact-expiry-boundary");
        OperatingTeam team = team(city, "share-exact-expiry-boundary");
        Instant boundary = databaseNow();
        Actor multiple = staffAt("share-exact-expiry-boundary-multiple",
                new TimedGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team,
                        boundary.minusSeconds(1), boundary),
                new TimedGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team,
                        boundary.minusSeconds(2), boundary.plusSeconds(3600)));
        Long boundaryGrantId = multiple.grants().get(0).getId();

        String operator = Boolean.getBoolean("pathome.test.expiry-boundary-non-strict")
                ? "<=" : "<";
        assertTrue(boundaryGrantQualifies(boundaryGrantId,
                boundary.minusNanos(1_000), operator),
                "A grant one nanosecond before expiry must qualify");
        assertFalse(boundaryGrantQualifies(boundaryGrantId, boundary, operator),
                "At equality the strict production predicate must classify the grant as expired");
        assertFalse(boundaryGrantQualifies(boundaryGrantId,
                boundary.plusNanos(1_000), operator),
                "A grant one nanosecond after expiry must not qualify");
        assertTrue(boundaryGrantQualifies(boundaryGrantId, boundary, "<="),
                "The isolated non-strict control must distinguish equality from the accepted strict rule");
        assertTrue(boundaryUserQualifies(multiple.user().getId(), boundary, operator),
                "An independently effective grant must preserve authorization at the first grant's equality boundary");
        assertEquals(1L, boundaryUserGrantCount(multiple.user().getId(), boundary, operator),
                "Only the independently effective grant should remain at equality");

        Instant repositoryNow = databaseNow();
        Actor repositoryActor = staffAt("share-exact-repository",
                new TimedGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team,
                        repositoryNow.minusSeconds(1), repositoryNow.plusSeconds(3600)));
        Actor expiredRepositoryActor = staffAt("share-exact-expired-repository",
                new TimedGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team,
                        repositoryNow.minusSeconds(2), repositoryNow.minusSeconds(1)));
        sqlCapture.clear();
        assertTrue(grants.findEffectiveForUser(repositoryActor.user().getId()).stream()
                        .anyMatch(grant -> grant.getId().equals(repositoryActor.grants().get(0).getId())),
                "The real effective-grant repository must return current authority before expiry");
        List<String> captured = sqlCapture.snapshot();
        assertTrue(captured.stream()
                        .map(statement -> statement.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                        .anyMatch(statement -> statement.contains(
                                "g.expires_at is null or clock.db_now < g.expires_at")),
                "The real repository execution must contain the strict database-clock predicate");
        assertTrue(grants.findEffectiveForUser(expiredRepositoryActor.user().getId()).isEmpty(),
                "The real repository must reject authority after expiry");

        System.out.println("G10_EVIDENCE_C database_time_singleton=true"
                + " before_qualifies=true equal_qualifies="
                + boundaryGrantQualifies(boundaryGrantId, boundary, "<")
                + " after_qualifies="
                + boundaryGrantQualifies(boundaryGrantId, boundary.plusNanos(1_000), "<")
                + " strict_operator=" + operator
                + " non_strict_equality_qualifies="
                + boundaryGrantQualifies(boundaryGrantId, boundary, "<=")
                + " multiple_grant_preserves=true");
    }

    @Test
    void grantExpiryDuringShortlistLockWaitDeniesTheNewEvent() throws Exception {
        SupportedCity city = city("share-shortlist-expiry");
        OperatingTeam team = team(city, "share-shortlist-expiry");
        Locality locality = locality(city, "share-shortlist-expiry");
        Actor owner = staff("share-shortlist-expiry-owner",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team, 10));
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Shortlist expiry synthetic home"),
                "shortlist expiry");
        Instant expiresAt = owner.grants().get(0).getExpiresAt();
        assertTrue(expiresAt.isAfter(databaseNow()));
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"CLIPBOARD_COPIED\"}";
        long blockedBefore = blockedFinalShortlistLockCount();

        try (Connection locker = schemaConnection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.executeQuery("select id from lead_shortlists where id = "
                        + prepared.shortlist().path("id").asLong() + " for update").close();
                CompletableFuture<HttpResponse<String>> waiting =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), body);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedFinalShortlistLockCount() > blockedBefore, 10,
                        "Clipboard event did not wait for the final shortlist lock");
                awaitCondition(() -> !databaseNow().isBefore(expiresAt), 10,
                        "Grant did not expire during the shortlist wait");
                locker.commit();
                HttpResponse<String> denied = waiting.get(20, TimeUnit.SECONDS);
                assertTrue(Set.of(403, 404).contains(denied.statusCode()), denied.body());
            }
        }

        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
    }

    @Test
    void grantExpiryAfterValidAdmissionDoesNotRemoveTheCommittedEvent() throws Exception {
        SupportedCity city = city("share-expiry-after-admission");
        OperatingTeam team = team(city, "share-expiry-after-admission");
        Locality locality = locality(city, "share-expiry-after-admission");
        Actor owner = staff("share-expiry-after-admission-owner",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team, 10));
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Admission before expiry synthetic home"),
                "admission before expiry");
        Instant expiresAt = owner.grants().get(0).getExpiresAt();
        assertTrue(expiresAt.isAfter(databaseNow()));
        UUID eventId = UUID.randomUUID();

        HttpResponse<String> recorded = send("POST",
                "/api/v1/operations/leads/" + prepared.lead().getId()
                        + "/shares/" + prepared.shareId() + "/events",
                owner.token(), "{\"eventId\":\"" + eventId
                        + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
        assertEquals(200, recorded.statusCode(), recorded.body());
        awaitCondition(() -> !databaseNow().isBefore(expiresAt), 15,
                "Grant did not expire after the admitted event");

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
    }

    @Test
    void admittedHandoffRemainsCommittedWhenItsGrantExpiresBeforeTransactionCommit()
            throws Exception {
        SupportedCity city = city("share-admission-before-expiry");
        OperatingTeam team = team(city, "share-admission-before-expiry");
        Locality locality = locality(city, "share-admission-before-expiry");
        Actor owner = staff("share-admission-before-expiry-owner",
                expiringGrantSpec(StaffCapability.OPS_COORDINATE,
                        StaffScopeType.TEAM, null, team, 10));
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Admission boundary synthetic home"),
                "admission boundary");
        Instant expiresAt = owner.grants().get(0).getExpiresAt();
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long activityBefore = jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId());
        long auditBefore = jdbc.queryForObject(
                "select count(*) from operational_audit_events "
                        + "where target_type = 'LEAD' and target_id = ?",
                Long.class, prepared.lead().getId());
        long receiptBefore = jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ?",
                Long.class, owner.user().getId());
        long blockedBefore = blockedShareAdmissionInsertCount();
        ShareAdmissionHold hold = installShareAdmissionHold(false);

        try (Connection blocker = schemaConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeQuery("select id from " + schema
                        + ".share_event_admission_gate where id = 1 for update").close();
                CompletableFuture<HttpResponse<String>> admitted =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), body);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedShareAdmissionInsertCount() > blockedBefore, 10,
                        "The final event transaction did not reach its post-admission hold");
                assertFalse(admitted.isDone(),
                        "The event committed before the post-admission witness was released");
                Instant admissionObservedAt = databaseNow();
                assertTrue(admissionObservedAt.isBefore(expiresAt),
                        "The definitive admission was not observed before grant expiry");
                awaitCondition(() -> !databaseNow().isBefore(expiresAt), 15,
                        "The grant did not expire while admission was held");
                blocker.commit();

                HttpResponse<String> committed = admitted.get(20, TimeUnit.SECONDS);
                assertEquals(200, committed.statusCode(), committed.body());
                Instant responseObservedAt = databaseNow();
                assertFalse(responseObservedAt.isBefore(expiresAt),
                        "The admitted transaction returned before the grant expiry boundary");
            }
        } finally {
            dropShareAdmissionHold(hold);
        }

        Instant persistedAt = jdbc.queryForObject(
                "select inserted_at from " + schema + ".share_event_admission_witness "
                        + "where event_id = ?",
                (rows, row) -> rows.getTimestamp(1).toInstant(), eventId);
        assertNotNull(persistedAt);
        assertFalse(persistedAt.isBefore(expiresAt),
                "The event row was not persisted after the held transaction crossed expiry");
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(activityBefore + 1, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
        assertEquals(auditBefore, jdbc.queryForObject(
                "select count(*) from operational_audit_events "
                        + "where target_type = 'LEAD' and target_id = ?",
                Long.class, prepared.lead().getId()));
        assertEquals(receiptBefore, jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ?",
                Long.class, owner.user().getId()));
        assertEquals(blockedBefore, blockedShareAdmissionInsertCount());
        System.out.println("G10_EVIDENCE_A admission_before_expiry=true"
                + " insert_after_expiry=true event_count=1 activity_count=1"
                + " audit_delta=0 receipt_delta=0");
    }

    @Test
    void revocationAfterDefinitiveAdmissionWaitsThenFreshAuthorityIsDenied()
            throws Exception {
        SupportedCity city = city("share-revocation-post-admission");
        OperatingTeam team = team(city, "share-revocation-post-admission");
        Locality locality = locality(city, "share-revocation-post-admission");
        Actor owner = coordinator("share-revocation-post-admission-owner", team);
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Post-admission revocation synthetic home"),
                "post-admission revocation");
        UUID admittedEventId = UUID.randomUUID();
        String admittedBody = "{\"eventId\":\"" + admittedEventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long grantWriteBefore = blockedStaffGrantWriteCount();
        long blockedBefore = blockedShareAdmissionInsertCount();
        ShareAdmissionHold hold = installShareAdmissionHold(false);

        try (Connection blocker = schemaConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeQuery("select id from " + schema
                        + ".share_event_admission_gate where id = 1 for update").close();
                CompletableFuture<HttpResponse<String>> admitted =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), admittedBody);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedShareAdmissionInsertCount() > blockedBefore, 10,
                        "The final handoff did not reach definitive post-admission hold");
                assertFalse(admitted.isDone());

                CompletableFuture<Integer> revocation = CompletableFuture.supplyAsync(() ->
                        jdbc.update("update staff_access_grants "
                                        + "set revoked_at = clock_timestamp(), revoked_by_user_id = ?, "
                                        + "revoke_reason_code = 'P1_REVOKE_POST_ADMISSION' where id = ?",
                                owner.user().getId(), owner.grants().get(0).getId()));
                awaitCondition(() -> blockedStaffGrantWriteCount() > grantWriteBefore, 10,
                        "Revocation did not wait behind the admitted transaction's grant guard");
                assertFalse(revocation.isDone(),
                        "Revocation became authoritative before admitted persistence committed");

                blocker.commit();
                HttpResponse<String> recorded = admitted.get(20, TimeUnit.SECONDS);
                assertEquals(200, recorded.statusCode(), recorded.body());
                assertEquals(1, revocation.get(20, TimeUnit.SECONDS));
            }
        } finally {
            dropShareAdmissionHold(hold);
        }

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, admittedEventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
        assertNotNull(jdbc.queryForObject(
                "select revoked_at from staff_access_grants where id = ?",
                Timestamp.class, owner.grants().get(0).getId()));

        UUID deniedEventId = UUID.randomUUID();
        HttpResponse<String> denied = send("POST",
                "/api/v1/operations/leads/" + prepared.lead().getId()
                        + "/shares/" + prepared.shareId() + "/events",
                owner.token(), "{\"eventId\":\"" + deniedEventId
                        + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
        assertTrue(Set.of(403, 404).contains(denied.statusCode()), denied.body());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, deniedEventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
        assertEquals(blockedBefore, blockedShareAdmissionInsertCount());
        System.out.println("G10_EVIDENCE_B admission_held=true revocation_waited=true"
                + " admitted_event_count=1 fresh_event_denied=true deadlock=false");
    }

    @Test
    void grantRevocationWinningBeforeAdmissionLeavesNoShareEventOrActivity() throws Exception {
        SupportedCity city = city("share-revocation-before");
        OperatingTeam team = team(city, "share-revocation-before");
        Locality locality = locality(city, "share-revocation-before");
        Actor owner = coordinator("share-revocation-before-owner", team);
        RentalDetails property = publicListing(locality, "Revocation before synthetic home");
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team, property, "revocation before admission");
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long blockedBefore = blockedStaffGrantLockCount();

        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from staff_access_grants where id = "
                        + owner.grants().get(0).getId() + " for update").close();
                CompletableFuture<HttpResponse<String>> waiting =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), body);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedStaffGrantLockCount() > blockedBefore, 10,
                        "Handoff did not wait for the revocation-winning grant lock");
                statement.executeUpdate("update staff_access_grants "
                        + "set revoked_at = clock_timestamp(), revoked_by_user_id = "
                        + owner.user().getId() + ", revoke_reason_code = 'P1_REVOKE_BEFORE_ADMISSION' "
                        + "where id = " + owner.grants().get(0).getId());
                writer.commit();
                HttpResponse<String> denied = waiting.get(20, TimeUnit.SECONDS);
                assertTrue(Set.of(403, 404).contains(denied.statusCode()), denied.body());
            }
        }

        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
    }

    @Test
    void grantRevocationWaitsForAHandoffAdmittedBeforeTheListingLockWait()
            throws Exception {
        SupportedCity city = city("share-revocation-after");
        OperatingTeam team = team(city, "share-revocation-after");
        Locality locality = locality(city, "share-revocation-after");
        Actor owner = coordinator("share-revocation-after-owner", team);
        RentalDetails property = publicListing(locality, "Revocation after synthetic home");
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team, property, "revocation after admission");
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long blockedBefore = blockedFinalListingLockCount();
        long blockedWritesBefore = blockedStaffGrantWriteCount();

        try (Connection locker = schemaConnection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.executeQuery("select id from listings where id = "
                        + property.getId() + " for update").close();
                CompletableFuture<HttpResponse<String>> admitted =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return send("POST",
                                        "/api/v1/operations/leads/" + prepared.lead().getId()
                                                + "/shares/" + prepared.shareId() + "/events",
                                        owner.token(), body);
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });
                awaitCondition(() -> blockedFinalListingLockCount() > blockedBefore, 10,
                        "Handoff did not pass its grant guard before the listing wait");
                CompletableFuture<Integer> revocation = CompletableFuture.supplyAsync(() ->
                        jdbc.update("update staff_access_grants "
                                        + "set revoked_at = clock_timestamp(), revoked_by_user_id = ?, "
                                        + "revoke_reason_code = 'P1_REVOKE_AFTER_ADMISSION' where id = ?",
                                owner.user().getId(), owner.grants().get(0).getId()));
                awaitCondition(() -> blockedStaffGrantWriteCount() > blockedWritesBefore, 10,
                        "Revocation did not wait for the admitted handoff's grant guard");
                assertFalse(revocation.isDone(),
                        "Revocation committed before the admitted handoff transaction");
                locker.commit();
                HttpResponse<String> recorded = admitted.get(20, TimeUnit.SECONDS);
                assertEquals(200, recorded.statusCode(), recorded.body());
                assertEquals(1, revocation.get(20, TimeUnit.SECONDS));
            }
        }

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
        assertNotNull(jdbc.queryForObject(
                "select revoked_at from staff_access_grants where id = ?",
                Timestamp.class, owner.grants().get(0).getId()));
    }

    @Test
    void inactiveProfileOrTeamAtAdmissionLeavesNoShareEvent() throws Exception {
        SupportedCity profileCity = city("share-inactive-profile");
        OperatingTeam profileTeam = team(profileCity, "share-inactive-profile");
        Locality profileLocality = locality(profileCity, "share-inactive-profile");
        Actor profileOwner = coordinator("share-inactive-profile-owner", profileTeam);
        PreparedHandoff profileShare = prepareFinalHandoff(
                profileOwner, profileCity, profileTeam,
                publicListing(profileLocality, "Inactive profile synthetic home"),
                "inactive profile");
        jdbc.update("update employee_profiles set staff_active = false, "
                        + "staff_deactivated_at = clock_timestamp(), version = version + 1 "
                        + "where user_id = ?",
                profileOwner.user().getId());
        UUID profileEvent = UUID.randomUUID();
        HttpResponse<String> profileDenied = send("POST",
                "/api/v1/operations/leads/" + profileShare.lead().getId()
                        + "/shares/" + profileShare.shareId() + "/events",
                profileOwner.token(), "{\"eventId\":\"" + profileEvent
                        + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
        assertTrue(Set.of(403, 404).contains(profileDenied.statusCode()), profileDenied.body());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, profileEvent));

        SupportedCity teamCity = city("share-inactive-team");
        OperatingTeam inactiveTeam = team(teamCity, "share-inactive-team");
        Locality teamLocality = locality(teamCity, "share-inactive-team");
        Actor teamOwner = coordinator("share-inactive-team-owner", inactiveTeam);
        PreparedHandoff teamShare = prepareFinalHandoff(
                teamOwner, teamCity, inactiveTeam,
                publicListing(teamLocality, "Inactive team synthetic home"),
                "inactive team");
        jdbc.update("update operating_teams set active = false where id = ?",
                inactiveTeam.getId());
        UUID teamEvent = UUID.randomUUID();
        HttpResponse<String> teamDenied = send("POST",
                "/api/v1/operations/leads/" + teamShare.lead().getId()
                        + "/shares/" + teamShare.shareId() + "/events",
                teamOwner.token(), "{\"eventId\":\"" + teamEvent
                        + "\",\"type\":\"CLIPBOARD_COPIED\"}");
        assertTrue(Set.of(403, 404).contains(teamDenied.statusCode()), teamDenied.body());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, teamEvent));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id in (?, ?) "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, profileShare.lead().getId(), teamShare.lead().getId()));
    }

    @Test
    void activityFailureRollsBackTheNewShareEvent() throws Exception {
        SupportedCity city = city("share-event-rollback");
        OperatingTeam team = team(city, "share-event-rollback");
        Locality locality = locality(city, "share-event-rollback");
        Actor owner = coordinator("share-event-rollback-owner", team);
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Share rollback synthetic home"),
                "share rollback");
        UUID eventId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "fail_share_activity_" + suffix;
        String trigger = "trg_" + function;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql "
                + "as $rollback$ begin "
                + "if new.activity_type = 'SHARE_ACTIVITY' then "
                + "raise exception 'Synthetic share activity failure'; end if; "
                + "return new; end $rollback$");
        jdbc.execute("create trigger " + trigger + " before insert on lead_activities "
                + "for each row execute function " + function + "()");
        try {
            HttpResponse<String> failed = send("POST",
                    "/api/v1/operations/leads/" + prepared.lead().getId()
                            + "/shares/" + prepared.shareId() + "/events",
                    owner.token(), "{\"eventId\":\"" + eventId
                            + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
            assertEquals(500, failed.statusCode(), failed.body());
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_activities");
            jdbc.execute("drop function if exists " + function + "()");
        }

        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where id = ?",
                Long.class, eventId));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_PREPARED'",
                Long.class, prepared.lead().getId()));
    }

    @Test
    void shareAdmissionLockTimeoutRollsBackAndExactRetryCommitsOnce() throws Exception {
        SupportedCity city = city("share-lock-timeout");
        OperatingTeam team = team(city, "share-lock-timeout");
        Locality locality = locality(city, "share-lock-timeout");
        Actor owner = coordinator("share-lock-timeout-owner", team);
        PreparedHandoff prepared = prepareFinalHandoff(
                owner, city, team,
                publicListing(locality, "Share timeout synthetic home"),
                "share timeout");
        UUID eventId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId
                + "\",\"type\":\"HANDOFF_ATTEMPTED\"}";
        long activityBefore = jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'SHARE_ACTIVITY'",
                Long.class, prepared.lead().getId());
        long auditBefore = jdbc.queryForObject(
                "select count(*) from operational_audit_events "
                        + "where target_type = 'LEAD' and target_id = ?",
                Long.class, prepared.lead().getId());
        long receiptBefore = jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ?",
                Long.class, owner.user().getId());
        long blockedBefore = blockedShareAdmissionInsertCount();
        ShareAdmissionHold hold = installShareAdmissionHold(true);

        try {
            try (Connection blocker = schemaConnection()) {
                blocker.setAutoCommit(false);
                try (Statement statement = blocker.createStatement()) {
                    statement.executeQuery("select id from " + schema
                            + ".share_event_admission_gate where id = 1 for update").close();
                    HttpResponse<String> timedOut = send("POST",
                            "/api/v1/operations/leads/" + prepared.lead().getId()
                                    + "/shares/" + prepared.shareId() + "/events",
                            owner.token(), body);
                    assertEquals(500, timedOut.statusCode(), timedOut.body());
                    assertFalse(timedOut.body().contains("55P03"), timedOut.body());
                    assertFalse(timedOut.body().contains("lead_share_events"), timedOut.body());
                    assertEquals(0L, jdbc.queryForObject(
                            "select count(*) from lead_share_events where id = ?",
                            Long.class, eventId));
                    assertEquals(activityBefore, jdbc.queryForObject(
                            "select count(*) from lead_activities where lead_id = ? "
                                    + "and activity_type = 'SHARE_ACTIVITY'",
                            Long.class, prepared.lead().getId()));
                    assertEquals(auditBefore, jdbc.queryForObject(
                            "select count(*) from operational_audit_events "
                                    + "where target_type = 'LEAD' and target_id = ?",
                            Long.class, prepared.lead().getId()));
                    assertEquals(receiptBefore, jdbc.queryForObject(
                            "select count(*) from lead_command_receipts where actor_user_id = ?",
                            Long.class, owner.user().getId()));
                    assertEquals(0L, jdbc.queryForObject(
                            "select count(*) from " + schema
                                    + ".share_event_admission_witness where event_id = ?",
                            Long.class, eventId));
                    blocker.commit();
                }
            }

            HttpResponse<String> retried = send("POST",
                    "/api/v1/operations/leads/" + prepared.lead().getId()
                            + "/shares/" + prepared.shareId() + "/events",
                    owner.token(), body);
            assertEquals(200, retried.statusCode(), retried.body());
            assertEquals(1L, jdbc.queryForObject(
                    "select count(*) from lead_share_events where id = ?",
                    Long.class, eventId));
            assertEquals(activityBefore + 1, jdbc.queryForObject(
                    "select count(*) from lead_activities where lead_id = ? "
                            + "and activity_type = 'SHARE_ACTIVITY'",
                    Long.class, prepared.lead().getId()));
            assertEquals(1L, jdbc.queryForObject(
                    "select count(*) from " + schema
                            + ".share_event_admission_witness where event_id = ?",
                    Long.class, eventId));
            assertEquals(blockedBefore, blockedShareAdmissionInsertCount());
            System.out.println("G10_EVIDENCE_D lock_timeout_sqlstate=55P03"
                    + " timeout_http=500 rollback=true exact_retry_http=200"
                    + " event_count=1 activity_count=1");
        } finally {
            dropShareAdmissionHold(hold);
        }
    }

    @Test
    void shareHistoryIsScopedPaginatedImmutableAndReadOnly() throws Exception {
        SupportedCity city = city("share-history");
        OperatingTeam team = team(city, "share-history");
        Actor owner = coordinator("share-history-owner", team);
        Actor peer = coordinator("share-history-peer", team);
        Locality locality = locality(city, "share-history");
        RentalDetails first = publicListing(locality, "History first synthetic home");
        RentalDetails second = publicListing(locality, "History second synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-history");

        String consent = "{\"expectedLeadVersion\":" + lead.getVersion()
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\""
                + ",\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), consent);
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode consentView = mapper.readTree(consentResponse.body());
        long preferenceRevision = consentView.path("revision").asLong();
        long leadVersion = consentView.path("leadVersion").asLong();

        UUID shortlistOperation = UUID.randomUUID();
        String shortlistBody = "{\"expectedLeadVersion\":" + leadVersion
                + ",\"listingIds\":[" + first.getId() + "," + second.getId() + "]}";
        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists",
                owner.token(), shortlistBody, "Idempotency-Key", shortlistOperation.toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        long shortlistId = shortlist.path("id").asLong();
        long shortlistVersion = shortlist.path("version").asLong();
        leadVersion = shortlist.path("leadVersion").asLong();

        UUID olderOperationId = UUID.randomUUID();
        UUID newerOperationId = UUID.randomUUID();
        List<UUID> preparedShareIds = new ArrayList<>();
        for (UUID operationId : List.of(olderOperationId, newerOperationId)) {
            HttpResponse<String> prepared = send("POST",
                    "/api/v1/operations/leads/" + lead.getId() + "/shares",
                    owner.token(), shareBody(operationId, leadVersion, shortlistId,
                            shortlistVersion, preferenceRevision));
            assertEquals(201, prepared.statusCode(), prepared.body());
            preparedShareIds.add(UUID.fromString(
                    mapper.readTree(prepared.body()).path("id").asText()));
        }
        UUID olderShareId = preparedShareIds.get(0);
        UUID newerShareId = preparedShareIds.get(1);
        jdbc.update("update lead_shares set recorded_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(2))), olderShareId);
        jdbc.update("update lead_shares set recorded_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), newerShareId);

        UUID eventId = UUID.randomUUID();
        HttpResponse<String> eventResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares/" + newerShareId
                        + "/events",
                owner.token(), "{\"eventId\":\"" + eventId
                        + "\",\"type\":\"CLIPBOARD_COPIED\"}");
        assertEquals(200, eventResponse.statusCode(), eventResponse.body());

        long shareCount = jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId());
        long eventCount = jdbc.queryForObject(
                "select count(*) from lead_share_events", Long.class);
        long activityCount = jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ?", Long.class, lead.getId());
        String immutableSnapshot = jdbc.queryForObject(
                "select row_to_json(share)::text from lead_shares share where share.id = ?",
                String.class, newerShareId);

        HttpResponse<String> firstPage = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/shares?page=0&size=1",
                owner.token(), null);
        assertEquals(200, firstPage.statusCode(), firstPage.body());
        assertTrue(firstPage.headers().firstValue("Cache-Control").orElse("")
                .toLowerCase(Locale.ROOT).contains("no-store"));
        JsonNode page = mapper.readTree(firstPage.body());
        assertEquals(2L, page.path("totalElements").asLong());
        assertEquals(2, page.path("totalPages").asInt());
        assertEquals(1, page.path("items").size());
        JsonNode newest = page.path("items").get(0);
        assertEquals(newerShareId.toString(), newest.path("id").asText());
        assertEquals("********3210", newest.path("maskedDestination").asText());
        assertEquals("COPY_LINKS", newest.path("mode").asText());
        assertTrue(newest.path("currentFactsMatch").asBoolean());
        assertTrue(newest.path("currentConsentMatches").asBoolean());
        assertTrue(newest.path("currentDestinationMatches").asBoolean());
        assertTrue(newest.path("currentAuthorityMatches").asBoolean());
        assertTrue(newest.path("actionReady").asBoolean());
        assertEquals(1, newest.path("events").size());
        assertEquals("CLIPBOARD_COPIED", newest.path("events").get(0).path("type").asText());
        assertTrue(!firstPage.body().contains("+919876543210"));

        HttpResponse<String> secondPage = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/shares?page=1&size=1",
                owner.token(), null);
        assertEquals(200, secondPage.statusCode(), secondPage.body());
        JsonNode older = mapper.readTree(secondPage.body()).path("items").get(0);
        assertEquals(olderShareId.toString(), older.path("id").asText());
        assertEquals(0, older.path("events").size());

        HttpResponse<String> malformed = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/shares?page=0&size=21",
                owner.token(), null);
        assertEquals(400, malformed.statusCode(), malformed.body());

        HttpResponse<String> concealed = send("GET",
                "/api/v1/operations/leads/" + lead.getId() + "/shares?page=0&size=10",
                peer.token(), null);
        assertEquals(404, concealed.statusCode(), concealed.body());
        assertTrue(!concealed.body().contains("History first synthetic home"));
        assertTrue(!concealed.body().contains("+919876543210"));

        assertEquals(shareCount, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
        assertEquals(eventCount, jdbc.queryForObject(
                "select count(*) from lead_share_events", Long.class));
        assertEquals(activityCount, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ?", Long.class, lead.getId()));
        assertEquals(immutableSnapshot, jdbc.queryForObject(
                "select row_to_json(share)::text from lead_shares share where share.id = ?",
                String.class, newerShareId));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PATHOME_LEAD_BROWSER_TEST", matches = "true")
    void genuineBrowserLeadJourneyUsesSpringPostgresAndProductionBuild() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(
                "crm-browser-g10-" + UUID.randomUUID().toString().substring(0, 8),
                "Bhopal Municipal Central Residential Corridor", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                "crm-browser-g10-team-" + UUID.randomUUID().toString().substring(0, 8),
                "North Bhopal Residential Coordination Team", true));
        Actor owner = coordinator("browser-g10-owner", team);
        Actor peer = coordinator("browser-g10-peer", team);
        Actor supervisor = staff("browser-g10-supervisor",
                grantSpec(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team));
        Actor admin = staff("browser-g10-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        Locality locality = locality(city, "browser-g10");
        for (int index = 1; index <= 7; index++) {
            publicListing(locality, "G10 genuine public home supporting result " + index);
        }
        RentalDetails publicProperty = publicListing(locality, "G10 genuine public home");

        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path repository = workingDirectory.getFileName().toString().equals("backend")
                ? workingDirectory.getParent() : workingDirectory;
        Path script = repository.resolve(
                "frontend-web/browser-verification/operations-leads/run.mjs");
        Path evidence = repository.resolve(
                "tools/oe-integration-env/evidence/"
                        + "p1-whatsapp-final-activation-20261022/genuine-browser");
        assertTrue(script.toFile().isFile(), "production browser verification script is missing");
        Files.createDirectories(evidence);
        Path browserLog = evidence.resolve("browser-run.log");

        ProcessBuilder builder = new ProcessBuilder("node", script.toString());
        builder.directory(repository.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(browserLog.toFile());
        builder.environment().put("PATHOME_BROWSER_BACKEND_PORT", String.valueOf(port));
        builder.environment().put("PATHOME_BROWSER_OWNER_TOKEN", owner.token());
        builder.environment().put("PATHOME_BROWSER_OWNER_ID", String.valueOf(owner.user().getId()));
        builder.environment().put("PATHOME_BROWSER_PEER_TOKEN", peer.token());
        builder.environment().put("PATHOME_BROWSER_PEER_ID", String.valueOf(peer.user().getId()));
        builder.environment().put("PATHOME_BROWSER_SUPERVISOR_TOKEN", supervisor.token());
        builder.environment().put("PATHOME_BROWSER_SUPERVISOR_ID",
                String.valueOf(supervisor.user().getId()));
        builder.environment().put("PATHOME_BROWSER_ADMIN_TOKEN", admin.token());
        builder.environment().put("PATHOME_BROWSER_ADMIN_ID", String.valueOf(admin.user().getId()));
        builder.environment().put("PATHOME_BROWSER_EXPECTED_PROPERTY_ID",
                String.valueOf(publicProperty.getId()));
        builder.environment().put("PATHOME_BROWSER_EVIDENCE_DIR", evidence.toString());

        Process process = builder.start();
        boolean exited = process.waitFor(180, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(exited, "genuine browser verification exceeded the bounded 180-second window");
        String browserOutput = Files.readString(browserLog);
        assertEquals(0, process.exitValue(),
                "genuine browser verification failed:\n" + browserOutput);
    }

    @Test
    void exactShareReplayRejectsEveryChangedVisibleFactAndFreshPrepareUsesCurrentTruth()
            throws Exception {
        SupportedCity city = city("share-replay-current");
        OperatingTeam team = team(city, "share-replay-current");
        Actor owner = coordinator("share-replay-current-owner", team);
        Locality locality = locality(city, "share-replay-current");
        SupportedCity foreignCity = city("share-replay-foreign");
        Locality foreignLocality = locality(foreignCity, "share-replay-foreign");
        RentalDetails property = publicListing(locality, "Replay baseline synthetic home");
        String originalTitle = property.getTitle();
        String originalSector = property.getSector();
        String originalBhk = property.getBhkCount();
        BigDecimal originalRent = property.getMonthlyRent();
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-replay-current");

        HttpResponse<String> consent = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(lead.getVersion(), 0L, "SAME", null, null, "GRANTED"));
        assertEquals(201, consent.statusCode(), consent.body());
        JsonNode preference = mapper.readTree(consent.body());
        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(preference.path("leadVersion").asLong(), List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        UUID operationId = UUID.randomUUID();
        String shareCommand = shareBody(operationId, shortlist.path("leadVersion").asLong(),
                shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                preference.path("revision").asLong());

        HttpResponse<String> prepared = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), shareCommand);
        assertEquals(201, prepared.statusCode(), prepared.body());
        JsonNode historical = mapper.readTree(prepared.body());
        UUID shareId = UUID.fromString(historical.path("id").asText());
        String historicalPreview = historical.path("previewText").asText();
        String historicalDestination = historical.path("maskedDestination").asText();

        HttpResponse<String> unchanged = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), shareCommand);
        assertEquals(201, unchanged.statusCode(), unchanged.body());
        assertEquals(shareId, UUID.fromString(mapper.readTree(unchanged.body()).path("id").asText()));

        jdbc.update("update listings set title = ? where id = ?", "Changed title", property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update listings set title = ? where id = ?", originalTitle, property.getId());

        jdbc.update("update rental_details set monthly_rent = ? where id = ?",
                new BigDecimal("22999.00"), property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update rental_details set monthly_rent = ? where id = ?",
                originalRent, property.getId());

        jdbc.update("update listings set sector = ? where id = ?", "Changed locality",
                property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update listings set sector = ? where id = ?", originalSector, property.getId());

        jdbc.update("update listings set bhk_count = ? where id = ?", "3 BHK", property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update listings set bhk_count = ? where id = ?", originalBhk, property.getId());

        jdbc.update("update listings set status = 'CLOSED' where id = ?", property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update listings set status = 'ACTIVE' where id = ?", property.getId());

        jdbc.update("update listings set canonical_locality_id = ? where id = ?",
                foreignLocality.getId(), property.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        jdbc.update("update listings set canonical_locality_id = ? where id = ?",
                locality.getId(), property.getId());

        assertEquals(historicalPreview, jdbc.queryForObject(
                "select preview_text from lead_shares where id = ?", String.class, shareId));
        assertEquals(historicalDestination, jdbc.queryForObject(
                "select masked_destination from lead_shares where id = ?", String.class, shareId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));

        String currentTitle = "Fresh current synthetic title";
        jdbc.update("update listings set title = ? where id = ?", currentTitle, property.getId());
        UUID freshOperation = UUID.randomUUID();
        HttpResponse<String> fresh = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                shareBody(freshOperation, shortlist.path("leadVersion").asLong(),
                        shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                        preference.path("revision").asLong()));
        assertEquals(201, fresh.statusCode(), fresh.body());
        assertTrue(mapper.readTree(fresh.body()).path("previewText").asText().contains(currentTitle));
        assertNotEquals(shareId,
                UUID.fromString(mapper.readTree(fresh.body()).path("id").asText()));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
        assertEquals(historicalPreview, jdbc.queryForObject(
                "select preview_text from lead_shares where id = ?", String.class, shareId));

        jdbc.update("update lead_routing_queue set status = 'CLOSED' where id = ?", lead.getId());
        assertShareReplayConflict(owner, lead, shareCommand);
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));

        jdbc.update("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoked_by_user_id = ?, revoke_reason_code = 'F1_REPLAY_GRANT_LOSS' where id = ?",
                owner.user().getId(), owner.grants().get(0).getId());
        HttpResponse<String> unauthorizedReplay = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), shareCommand);
        assertEquals(403, unauthorizedReplay.statusCode(), unauthorizedReplay.body());
        assertFalse(unauthorizedReplay.body().contains(historicalDestination));
        assertFalse(unauthorizedReplay.body().contains("+919876543210"));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
    }

    @Test
    void shortlistBoundsDuplicatesAndCurrentPublicTruthAreEnforced() throws Exception {
        SupportedCity city = city("shortlist-bounds");
        OperatingTeam team = team(city, "shortlist-bounds");
        Actor owner = coordinator("shortlist-bounds-owner", team);
        Locality locality = locality(city, "shortlist-bounds");
        List<RentalDetails> properties = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            properties.add(publicListing(locality, "Current synthetic home " + index));
        }
        LeadRoutingQueue lead = lead(city, team, owner.user(), "shortlist-bounds");
        List<Long> twenty = properties.subList(0, 20).stream().map(RentalDetails::getId).toList();
        List<Long> twentyOne = properties.stream().map(RentalDetails::getId).toList();

        HttpResponse<String> tooMany = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(lead.getVersion(), twentyOne),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(400, tooMany.statusCode(), tooMany.body());

        HttpResponse<String> duplicate = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(lead.getVersion(), List.of(twenty.get(0), twenty.get(0))),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(400, duplicate.statusCode(), duplicate.body());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_shortlists where lead_id = ?", Long.class, lead.getId()));

        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(lead.getVersion(), twenty),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode shortlist = mapper.readTree(created.body());
        long shortlistId = shortlist.path("id").asLong();
        long shortlistVersion = shortlist.path("version").asLong();
        long leadVersion = shortlist.path("leadVersion").asLong();
        assertEquals(20L, jdbc.queryForObject(
                "select count(*) from lead_shortlist_items where shortlist_id = ?",
                Long.class, shortlistId));

        HttpResponse<String> overflowUpdate = send("PUT",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists/" + shortlistId,
                owner.token(), shortlistBody(leadVersion, shortlistVersion, twentyOne));
        assertEquals(400, overflowUpdate.statusCode(), overflowUpdate.body());
        assertEquals(20L, jdbc.queryForObject(
                "select count(*) from lead_shortlist_items where shortlist_id = ?",
                Long.class, shortlistId));
        assertEquals(shortlistVersion, jdbc.queryForObject(
                "select version from lead_shortlists where id = ?", Long.class, shortlistId));

        String consent = "{\"expectedLeadVersion\":" + leadVersion
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\""
                + ",\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), consent);
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode preference = mapper.readTree(consentResponse.body());
        long preferenceRevision = preference.path("revision").asLong();
        leadVersion = preference.path("leadVersion").asLong();

        RentalDetails first = properties.get(0);
        jdbc.update("update listings set status = 'CLOSED' where id = ?", first.getId());
        UUID shareOperation = UUID.randomUUID();
        HttpResponse<String> unavailable = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                shareBody(shareOperation, leadVersion, shortlistId, shortlistVersion, preferenceRevision));
        assertEquals(409, unavailable.statusCode(), unavailable.body());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));

        String currentTitle = "Current public title " + UUID.randomUUID();
        jdbc.update("update rental_details set monthly_rent = 21999.00 where id = ?", first.getId());
        jdbc.update("update listings set status = 'ACTIVE', title = ? where id = ?",
                currentTitle, first.getId());
        HttpResponse<String> current = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                shareBody(shareOperation, leadVersion, shortlistId, shortlistVersion, preferenceRevision));
        assertEquals(201, current.statusCode(), current.body());
        JsonNode share = mapper.readTree(current.body());
        assertEquals(20, share.path("publicLinks").size());
        assertTrue(share.path("previewText").asText().contains(currentTitle));
        assertTrue(share.path("previewText").asText().contains("INR 21999.00 / month"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
    }

    @Test
    void shortlistAllowsAnIntentionalEmptyDraft() throws Exception {
        SupportedCity city = city("shortlist-empty");
        OperatingTeam team = team(city, "shortlist-empty");
        Actor owner = coordinator("shortlist-empty-owner", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "shortlist-empty");

        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(lead.getVersion(), List.of()),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode shortlist = mapper.readTree(created.body());
        assertEquals(0, shortlist.path("listingIds").size());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_shortlist_items where shortlist_id = ?",
                Long.class, shortlist.path("id").asLong()));
    }

    @Test
    void concurrentShortlistUpdatesAtTheSameVersionHaveExactlyOneOrderedWinner()
            throws Exception {
        SupportedCity city = city("shortlist-race");
        OperatingTeam team = team(city, "shortlist-race");
        Actor owner = coordinator("shortlist-race-owner", team);
        Locality locality = locality(city, "shortlist-race");
        RentalDetails first = publicListing(locality, "Race first synthetic home");
        RentalDetails second = publicListing(locality, "Race second synthetic home");
        RentalDetails replacement = publicListing(locality, "Race replacement synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "shortlist-race");

        HttpResponse<String> consent = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(lead.getVersion(), 0L, "SAME", null, null, "GRANTED"));
        assertEquals(201, consent.statusCode(), consent.body());
        JsonNode preference = mapper.readTree(consent.body());
        HttpResponse<String> created = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(preference.path("leadVersion").asLong(),
                        List.of(first.getId(), second.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode shortlist = mapper.readTree(created.body());
        long shortlistId = shortlist.path("id").asLong();
        long shortlistVersion = shortlist.path("version").asLong();
        long leadVersion = shortlist.path("leadVersion").asLong();

        UUID originalShareOperation = UUID.randomUUID();
        String originalShareCommand = shareBody(originalShareOperation, leadVersion, shortlistId,
                shortlistVersion, preference.path("revision").asLong());
        HttpResponse<String> share = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                originalShareCommand);
        assertEquals(201, share.statusCode(), share.body());
        UUID shareId = UUID.fromString(mapper.readTree(share.body()).path("id").asText());
        String sharePreview = mapper.readTree(share.body()).path("previewText").asText();

        String firstUpdate = shortlistBody(leadVersion, shortlistVersion,
                List.of(replacement.getId(), first.getId()));
        String secondUpdate = shortlistBody(leadVersion, shortlistVersion,
                List.of(second.getId(), replacement.getId()));
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> firstWriter = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("PUT", "/api/v1/operations/leads/" + lead.getId()
                                + "/shortlists/" + shortlistId,
                        owner.token(), firstUpdate);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<HttpResponse<String>> secondWriter = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("PUT", "/api/v1/operations/leads/" + lead.getId()
                                + "/shortlists/" + shortlistId,
                        owner.token(), secondUpdate);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        start.countDown();
        HttpResponse<String> firstResponse = firstWriter.get(20, TimeUnit.SECONDS);
        HttpResponse<String> secondResponse = secondWriter.get(20, TimeUnit.SECONDS);
        List<Integer> outcomes = List.of(firstResponse.statusCode(), secondResponse.statusCode())
                .stream().sorted().toList();
        assertEquals(List.of(200, 409), outcomes,
                firstResponse.body() + " | " + secondResponse.body());

        HttpResponse<String> winner = firstResponse.statusCode() == 200
                ? firstResponse : secondResponse;
        List<String> winningIds = new ArrayList<>();
        mapper.readTree(winner.body()).path("listingIds")
                .forEach(value -> winningIds.add(Long.toString(value.asLong())));
        assertEquals(winningIds, jdbc.queryForList(
                "select listing_id::text from lead_shortlist_items where shortlist_id = ? "
                        + "order by position", String.class, shortlistId));
        assertEquals(leadVersion + 1, jdbc.queryForObject(
                "select version from lead_routing_queue where id = ?", Long.class, lead.getId()));
        assertEquals(shortlistVersion + 1, jdbc.queryForObject(
                "select version from lead_shortlists where id = ?", Long.class, shortlistId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'PROPERTY_SHORTLIST_UPDATED'",
                Long.class, lead.getId()));
        assertEquals(sharePreview, jdbc.queryForObject(
                "select preview_text from lead_shares where id = ?", String.class, shareId));
        assertEquals(0L, jdbc.queryForObject(
                "select shortlist_version from lead_shares where id = ?", Long.class, shareId));
        assertShareReplayConflict(owner, lead, originalShareCommand);
    }

    @Test
    void sharePreparationRollsBackWhenTheActivityWriteFails() throws Exception {
        SupportedCity city = city("share-rollback");
        OperatingTeam team = team(city, "share-rollback");
        Actor owner = coordinator("share-rollback-owner", team);
        Locality locality = locality(city, "share-rollback");
        RentalDetails property = publicListing(locality, "Rollback synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-rollback");

        String consent = "{\"expectedLeadVersion\":" + lead.getVersion()
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\","
                + "\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), consent);
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode preference = mapper.readTree(consentResponse.body());
        long leadVersion = preference.path("leadVersion").asLong();

        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(leadVersion, List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "g09_fail_share_activity_" + suffix;
        String trigger = "trg_" + function;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $g09$ "
                + "begin if new.lead_id = " + lead.getId()
                + " and new.activity_type = 'SHARE_PREPARED' then "
                + "raise exception 'G09 activity persistence failure'; end if; return new; end $g09$");
        jdbc.execute("create trigger " + trigger + " before insert on lead_activities "
                + "for each row execute function " + function + "()");
        UUID operationId = UUID.randomUUID();
        try {
            HttpResponse<String> failed = send("POST",
                    "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                    shareBody(operationId, shortlist.path("leadVersion").asLong(),
                            shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                            preference.path("revision").asLong()));
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals("no-store", failed.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_activities where lead_id = ? "
                            + "and activity_type = 'SHARE_PREPARED'", Long.class, lead.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_command_receipts where operation_id = ? "
                            + "and action = 'LEAD_SHARE_PREPARE'", Long.class, operationId));
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_activities");
            jdbc.execute("drop function if exists " + function + "()");
        }
    }

    @Test
    void shareReceiptCommitFailureLeavesNoPartialHistoryAndExactRetryRecovers() throws Exception {
        SupportedCity city = city("share-commit-failure");
        OperatingTeam team = team(city, "share-commit-failure");
        Actor owner = coordinator("share-commit-failure-owner", team);
        Locality locality = locality(city, "share-commit-failure");
        RentalDetails property = publicListing(locality, "Commit boundary synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-commit-failure");

        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(lead.getVersion(), 0L, "SAME", null, null, "GRANTED"));
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode preference = mapper.readTree(consentResponse.body());

        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(preference.path("leadVersion").asLong(), List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());

        String historicalBody = shareBody(UUID.randomUUID(),
                shortlist.path("leadVersion").asLong(), shortlist.path("id").asLong(),
                shortlist.path("version").asLong(), preference.path("revision").asLong());
        HttpResponse<String> historicalResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), historicalBody);
        assertEquals(201, historicalResponse.statusCode(), historicalResponse.body());
        UUID historicalShareId = UUID.fromString(
                mapper.readTree(historicalResponse.body()).path("id").asText());

        G09ShareCommitSnapshot baseline = shareCommitSnapshot(owner, lead.getId(),
                shortlist.path("id").asLong(), historicalShareId);
        UUID operationId = UUID.randomUUID();
        String command = shareBody(operationId, shortlist.path("leadVersion").asLong(),
                shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                preference.path("revision").asLong());
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sequence = "g09_receipt_hook_calls_" + suffix;
        String function = "g09_reject_share_receipt_" + suffix;
        String trigger = "trg_" + function;

        try {
            jdbc.execute("create sequence " + sequence);
            jdbc.execute("create function " + function + "() returns trigger language plpgsql as $g09$ "
                    + "begin if new.actor_user_id = " + owner.user().getId()
                    + " and new.operation_id = '" + operationId + "'::uuid"
                    + " and new.action = 'LEAD_SHARE_PREPARE'"
                    + " and new.result_reference is not null then "
                    + "perform nextval('" + sequence + "'); "
                    + "raise exception 'G09 share receipt commit failure'; "
                    + "end if; return new; end $g09$");
            jdbc.execute("create constraint trigger " + trigger
                    + " after insert on lead_command_receipts deferrable initially deferred "
                    + "for each row execute function " + function + "()");

            String definition = jdbc.queryForObject(
                    "select pg_get_triggerdef(oid) from pg_trigger where tgname = ?",
                    String.class, trigger);
            assertTrue(definition.contains("AFTER INSERT ON"), definition);
            assertTrue(definition.contains("lead_command_receipts"), definition);
            assertTrue(definition.contains("DEFERRABLE INITIALLY DEFERRED"), definition);

            HttpResponse<String> failed = send("POST",
                    "/api/v1/operations/leads/" + lead.getId() + "/shares",
                    owner.token(), command);
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals("no-store",
                    failed.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(failed.body().contains("+919876543210"), failed.body());
            assertFalse(failed.body().contains("G09 share receipt"), failed.body());
            assertNoSqlStateEvidence(failed);

            assertEquals(true, jdbc.queryForObject(
                    "select is_called from " + sequence, Boolean.class),
                    "The deferred receipt trigger must have fired at commit");
            assertEquals(1L, jdbc.queryForObject(
                    "select last_value from " + sequence, Long.class));
            G09ShareCommitSnapshot rolledBack = shareCommitSnapshot(owner, lead.getId(),
                    shortlist.path("id").asLong(), historicalShareId);
            assertEquals(baseline, rolledBack);
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_command_receipts "
                            + "where actor_user_id = ? and operation_id = ? "
                            + "and action = 'LEAD_SHARE_PREPARE'",
                    Long.class, owner.user().getId(), operationId));
            System.out.println("G09_EVIDENCE phase=A http=" + failed.statusCode()
                    + " cache=" + failed.headers().firstValue("Cache-Control").orElse("")
                    + " deferred_hook_fired=true baseline=" + baseline
                    + " after=" + rolledBack);
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_command_receipts");
            jdbc.execute("drop function if exists " + function + "()");
            jdbc.execute("drop sequence if exists " + sequence);
        }

        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from pg_trigger where tgname = ? and not tgisinternal",
                Long.class, trigger));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from pg_class relation "
                        + "join pg_namespace namespace on namespace.oid = relation.relnamespace "
                        + "where namespace.nspname = current_schema() and relation.relname = ?",
                Long.class, sequence));

        HttpResponse<String> recovered = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), command);
        assertEquals(201, recovered.statusCode(), recovered.body());
        assertFalse(recovered.body().contains("+919876543210"), recovered.body());
        UUID shareId = UUID.fromString(mapper.readTree(recovered.body()).path("id").asText());
        assertNotEquals(historicalShareId, shareId);

        G09ShareCommitSnapshot committed = shareCommitSnapshot(owner, lead.getId(),
                shortlist.path("id").asLong(), historicalShareId);
        assertEquals(baseline.leadVersion(), committed.leadVersion());
        assertEquals(baseline.shortlistVersion(), committed.shortlistVersion());
        assertEquals(baseline.shareCount() + 1, committed.shareCount());
        assertEquals(baseline.shareActivityCount() + 1, committed.shareActivityCount());
        assertEquals(baseline.shareAuditCount() + 1, committed.shareAuditCount());
        assertEquals(baseline.shareReceiptCount() + 1, committed.shareReceiptCount());
        assertEquals(baseline.historicalShareSnapshot(), committed.historicalShareSnapshot());
        assertEquals(0L, committed.shareEventCount());
        assertEquals(baseline.notificationOutboxCount(), committed.notificationOutboxCount());
        System.out.println("G09_EVIDENCE phase=B retry_http=" + recovered.statusCode()
                + " recovered_share=" + shareId + " committed=" + committed);

        HttpResponse<String> replay = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), command);
        assertEquals(201, replay.statusCode(), replay.body());
        assertFalse(replay.body().contains("+919876543210"), replay.body());
        assertEquals(shareId,
                UUID.fromString(mapper.readTree(replay.body()).path("id").asText()));
        G09ShareCommitSnapshot replaySnapshot = shareCommitSnapshot(owner, lead.getId(),
                shortlist.path("id").asLong(), historicalShareId);
        assertEquals(committed, replaySnapshot);
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, shareId));
        System.out.println("G09_EVIDENCE replay_http=" + replay.statusCode()
                + " same_share=true after_replay=" + replaySnapshot);
    }

    @Test
    void sharePreparationRecoversFromLostAcknowledgementWithoutDuplicateHistory() throws Exception {
        SupportedCity city = city("share-lost-ack");
        OperatingTeam team = team(city, "share-lost-ack");
        Actor owner = coordinator("share-lost-ack-owner", team);
        Actor peer = coordinator("share-lost-ack-peer", team);
        Locality locality = locality(city, "share-lost-ack");
        RentalDetails property = publicListing(locality, "Lost acknowledgement synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "share-lost-ack");
        String consent = "{\"expectedLeadVersion\":" + lead.getVersion()
                + ",\"expectedPreferenceRevision\":0,\"channel\":\"WHATSAPP\","
                + "\"destinationChoice\":\"SAME\",\"consentStatus\":\"GRANTED\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
        HttpResponse<String> consentResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), consent);
        assertEquals(201, consentResponse.statusCode(), consentResponse.body());
        JsonNode preference = mapper.readTree(consentResponse.body());
        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(preference.path("leadVersion").asLong(), List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        UUID operationId = UUID.randomUUID();
        String body = shareBody(operationId, shortlist.path("leadVersion").asLong(),
                shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                preference.path("revision").asLong());

        LostHttpResult lost = sendWithoutClientAcknowledgement("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), body, null, null);
        assertEquals(-1, lost.clientRead());
        assertTrue(lost.serverStatusLine().contains(" 201 "), lost.serverStatusLine());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));

        HttpResponse<String> receipt = send("GET",
                "/api/v1/operations/lead-commands/" + operationId + "?action=LEAD_SHARE_PREPARE",
                owner.token(), null);
        assertEquals(200, receipt.statusCode(), receipt.body());
        assertEquals("RECORDED", mapper.readTree(receipt.body()).path("state").asText());

        HttpResponse<String> retry = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(), body);
        assertEquals(201, retry.statusCode(), retry.body());
        UUID shareId = UUID.fromString(mapper.readTree(retry.body()).path("id").asText());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? and activity_type = 'SHARE_PREPARED'",
                Long.class, lead.getId()));

        String changed = body.replace("COPY_LINKS", "MANUAL_BUSINESS_HANDOFF");
        HttpResponse<String> conflict = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(), changed);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_shares where lead_id = ?", Long.class, lead.getId()));

        jdbc.update("update lead_routing_queue set owner_user_id = ? where id = ?",
                peer.user().getId(), lead.getId());
        HttpResponse<String> formerOwnerEvent = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares/" + shareId + "/events",
                owner.token(), "{\"eventId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"CLIPBOARD_COPIED\"}");
        assertEquals(404, formerOwnerEvent.statusCode(), formerOwnerEvent.body());

        for (String eventType : List.of("CLIPBOARD_COPIED", "OPERATOR_REPORTED_MANUAL_SEND")) {
            HttpResponse<String> event = send("POST",
                    "/api/v1/operations/leads/" + lead.getId() + "/shares/" + shareId + "/events",
                    peer.token(), "{\"eventId\":\"" + UUID.randomUUID()
                            + "\",\"type\":\"" + eventType + "\"}");
            assertEquals(200, event.statusCode(), event.body());
        }
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_share_events where share_id = ?",
                Long.class, shareId));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_share_events where event_type like '%DELIVERED%' "
                        + "or event_type like '%READ%'", Long.class));
    }

    @Test
    void contactDestinationRevisionsAndNonePreserveConsentTruth() throws Exception {
        SupportedCity city = city("contact-choices");
        OperatingTeam team = team(city, "contact-choices");
        Actor owner = coordinator("contact-choices-owner", team);
        Locality locality = locality(city, "contact-choices");
        RentalDetails property = publicListing(locality, "Consent synthetic home");
        LeadRoutingQueue lead = lead(city, team, owner.user(), "contact-choices");

        HttpResponse<String> same = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(lead.getVersion(), 0L, "SAME", null, null,
                        "GRANTED"));
        assertEquals(201, same.statusCode(), same.body());
        JsonNode first = mapper.readTree(same.body());
        long firstRevision = first.path("revision").asLong();
        long leadVersion = first.path("leadVersion").asLong();

        HttpResponse<String> different = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(leadVersion, firstRevision, "DIFFERENT",
                        "+91", "9123456789", "GRANTED"));
        assertEquals(201, different.statusCode(), different.body());
        JsonNode second = mapper.readTree(different.body());
        assertEquals("********6789", second.path("maskedDestination").asText());
        leadVersion = second.path("leadVersion").asLong();
        long secondRevision = second.path("revision").asLong();

        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists", owner.token(),
                shortlistBody(leadVersion, List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        leadVersion = shortlist.path("leadVersion").asLong();

        HttpResponse<String> staleConsentShare = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                shareBody(UUID.randomUUID(), shortlist.path("leadVersion").asLong(),
                        shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                        firstRevision));
        assertEquals(409, staleConsentShare.statusCode(), staleConsentShare.body());

        HttpResponse<String> invalid = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(leadVersion, secondRevision, "DIFFERENT",
                        "+91", "0123", "GRANTED"));
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_contact_preferences where lead_id = ?",
                Long.class, lead.getId()));

        HttpResponse<String> none = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(leadVersion, secondRevision, "NONE", null, null,
                        "GRANTED"));
        assertEquals(201, none.statusCode(), none.body());
        JsonNode third = mapper.readTree(none.body());
        assertEquals("NONE", third.path("destinationChoice").asText());
        assertTrue(third.path("maskedDestination").isNull());
        leadVersion = third.path("leadVersion").asLong();
        long thirdRevision = third.path("revision").asLong();

        HttpResponse<String> copyFallback = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", owner.token(),
                shareBody(UUID.randomUUID(), leadVersion, shortlist.path("id").asLong(),
                        shortlist.path("version").asLong(), thirdRevision));
        assertEquals(201, copyFallback.statusCode(), copyFallback.body());
        assertTrue(mapper.readTree(copyFallback.body()).path("maskedDestination").isNull());

        String manualBody = shareBody(UUID.randomUUID(), leadVersion,
                shortlist.path("id").asLong(), shortlist.path("version").asLong(), thirdRevision)
                .replace("COPY_LINKS", "MANUAL_BUSINESS_HANDOFF");
        HttpResponse<String> manual = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), manualBody);
        assertEquals(409, manual.statusCode(), manual.body());
    }

    @Test
    void consentIdempotencyLockReplaysOnceAndConflictsOnChangedPayload() throws Exception {
        SupportedCity city = city("consent-idempotency");
        OperatingTeam team = team(city, "consent-idempotency");
        Actor owner = coordinator("consent-idempotency-owner", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "consent-idempotency");
        UUID operationId = UUID.randomUUID();
        String body = contactBody(lead.getVersion(), 0L, "DIFFERENT", "+91",
                "9123456789", "GRANTED");
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/operations/leads/" + lead.getId()
                                + "/contact-preferences", owner.token(), body,
                        "Idempotency-Key", operationId.toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/operations/leads/" + lead.getId()
                                + "/contact-preferences", owner.token(), body,
                        "Idempotency-Key", operationId.toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        start.countDown();
        HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
        HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
        assertEquals(201, firstResponse.statusCode(), firstResponse.body());
        assertEquals(201, secondResponse.statusCode(), secondResponse.body());
        JsonNode firstPreference = mapper.readTree(firstResponse.body());
        JsonNode secondPreference = mapper.readTree(secondResponse.body());
        assertEquals(firstPreference, secondPreference);
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_contact_preferences where lead_id = ?",
                Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'CONTACT_PREFERENCE_RECORDED'",
                Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ? "
                        + "and operation_id = ? and action = 'LEAD_CONTACT_PREFERENCE_RECORD'",
                Long.class, owner.user().getId(), operationId));
        assertEquals("********6789", firstPreference.path("maskedDestination").asText());
        assertFalse(firstResponse.body().contains("9123456789"));

        HttpResponse<String> receipt = send("GET",
                "/api/v1/operations/lead-commands/" + operationId
                        + "?action=LEAD_CONTACT_PREFERENCE_RECORD",
                owner.token(), null);
        assertEquals(200, receipt.statusCode(), receipt.body());
        JsonNode receiptView = mapper.readTree(receipt.body());
        assertEquals("RECORDED", receiptView.path("state").asText());
        assertTrue(receiptView.path("resultReference").asText()
                .startsWith("preference:"));
        assertFalse(receipt.body().contains("9123456789"));

        String changed = contactBody(firstPreference.path("leadVersion").asLong(),
                firstPreference.path("revision").asLong(), "NONE", null, null, "GRANTED");
        HttpResponse<String> conflict = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), changed, "Idempotency-Key", operationId.toString());
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_contact_preferences where lead_id = ?",
                Long.class, lead.getId()));

        HttpResponse<String> foreignReceipt = send("GET",
                "/api/v1/operations/lead-commands/" + operationId
                        + "?action=LEAD_CONTACT_PREFERENCE_RECORD",
                coordinator("consent-idempotency-peer", team).token(), null);
        assertEquals(200, foreignReceipt.statusCode(), foreignReceipt.body());
        assertEquals("NOT_RECORDED",
                mapper.readTree(foreignReceipt.body()).path("state").asText());
    }

    @Test
    void differentConsentCommandsAtTheSameRevisionHaveOneWinner() throws Exception {
        SupportedCity city = city("consent-command-race");
        OperatingTeam team = team(city, "consent-command-race");
        Actor owner = coordinator("consent-command-race-owner", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "consent-command-race");
        String body = contactBody(lead.getVersion(), 0L, "SAME", null, null, "GRANTED");
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/operations/leads/" + lead.getId()
                                + "/contact-preferences", owner.token(), body,
                        "Idempotency-Key", UUID.randomUUID().toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/operations/leads/" + lead.getId()
                                + "/contact-preferences", owner.token(), body,
                        "Idempotency-Key", UUID.randomUUID().toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        start.countDown();
        HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
        HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
        assertEquals(List.of(201, 409),
                List.of(firstResponse.statusCode(), secondResponse.statusCode()).stream()
                        .sorted().toList(),
                firstResponse.body() + " | " + secondResponse.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_contact_preferences where lead_id = ?",
                Long.class, lead.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ? "
                        + "and action = 'LEAD_CONTACT_PREFERENCE_RECORD'",
                Long.class, owner.user().getId()));
    }

    @Test
    void preferenceAndShortlistMutationsRollBackAtTheActivityBoundary() throws Exception {
        SupportedCity city = city("crm-activity-rollback");
        OperatingTeam team = team(city, "crm-activity-rollback");
        Actor owner = coordinator("crm-activity-rollback-owner", team);
        Locality locality = locality(city, "crm-activity-rollback");
        RentalDetails property = publicListing(locality, "Rollback activity home");
        LeadRoutingQueue contactLead = lead(city, team, owner.user(), "rollback-contact");
        LeadRoutingQueue shortlistLead = lead(city, team, owner.user(), "rollback-shortlist");
        long contactVersion = contactLead.getVersion();
        UUID shortlistOperation = UUID.randomUUID();
        UUID contactOperation = UUID.randomUUID();

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "g09_activity_boundary_" + suffix;
        String trigger = "trg_" + function;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $g09$ "
                + "begin if (new.lead_id = " + contactLead.getId()
                + " and new.activity_type = 'CONTACT_PREFERENCE_RECORDED') or (new.lead_id = "
                + shortlistLead.getId() + " and new.activity_type = 'PROPERTY_SHORTLIST_CREATED') "
                + "then raise exception 'G09 activity boundary failure'; end if; return new; end $g09$");
        jdbc.execute("create trigger " + trigger + " before insert on lead_activities "
                + "for each row execute function " + function + "()");
        try {
            HttpResponse<String> contactFailure = send("POST",
                    "/api/v1/operations/leads/" + contactLead.getId() + "/contact-preferences",
                    owner.token(), contactBody(contactVersion, 0L, "SAME", null, null, "GRANTED"),
                    "Idempotency-Key", contactOperation.toString());
            assertEquals(500, contactFailure.statusCode(), contactFailure.body());
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_contact_preferences where lead_id = ?",
                    Long.class, contactLead.getId()));
            assertEquals(contactVersion, jdbc.queryForObject(
                    "select version from lead_routing_queue where id = ?",
                    Long.class, contactLead.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from operational_audit_events where target_id = ? "
                            + "and action_code = 'LEAD_CONTACT_PREFERENCE_RECORDED'",
                    Long.class, contactLead.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_command_receipts where operation_id = ?",
                    Long.class, contactOperation));

            HttpResponse<String> shortlistFailure = send("POST",
                    "/api/v1/operations/leads/" + shortlistLead.getId() + "/shortlists",
                    owner.token(), shortlistBody(shortlistLead.getVersion(), List.of(property.getId())),
                    "Idempotency-Key", shortlistOperation.toString());
            assertEquals(500, shortlistFailure.statusCode(), shortlistFailure.body());
            assertEquals("no-store",
                    shortlistFailure.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_shortlists where lead_id = ?",
                    Long.class, shortlistLead.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_shortlist_items where listing_id = ?",
                    Long.class, property.getId()));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from lead_command_receipts where operation_id = ?",
                    Long.class, shortlistOperation));
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_activities");
            jdbc.execute("drop function if exists " + function + "()");
        }
    }

    @Test
    void capturePersistsExactRequirementsAndReplaysOneBusinessEffect() throws Exception {
        SupportedCity city = city("capture-idempotency");
        OperatingTeam team = team(city, "capture-idempotency");
        Actor owner = coordinator("capture-idempotency-owner", team);
        Locality locality = locality(city, "capture-idempotency");
        UUID operationId = UUID.randomUUID();
        String body = "{\"cityId\":" + city.getId() + ",\"teamId\":" + team.getId()
                + ",\"customerName\":\"Synthetic Customer\""
                + ",\"contactPhone\":\"+919876543210\""
                + ",\"budgetMax\":999999999999.99"
                + ",\"preferredLocalityIds\":[" + locality.getId() + "]}";

        HttpResponse<String> captured = send("POST", "/api/v1/operations/leads",
                owner.token(), body, "Idempotency-Key", operationId.toString());
        assertEquals(201, captured.statusCode(), captured.body());
        JsonNode detail = mapper.readTree(captured.body());
        long leadId = detail.path("summary").path("id").asLong();
        assertEquals(0, new BigDecimal("999999999999.99")
                .compareTo(leads.findById(leadId).orElseThrow().getBudgetMax()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_preferred_localities where lead_id = ? and locality_id = ?",
                Long.class, leadId, locality.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? and activity_type = 'LEAD_CAPTURED'",
                Long.class, leadId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_command_receipts "
                        + "where operation_id = ? and action = 'LEAD_CAPTURE' and result_reference = ?",
                Long.class, operationId, Long.toString(leadId)));

        HttpResponse<String> replay = send("POST", "/api/v1/operations/leads",
                owner.token(), body, "Idempotency-Key", operationId.toString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(leadId, mapper.readTree(replay.body()).path("summary").path("id").asLong());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? and activity_type = 'LEAD_CAPTURED'",
                Long.class, leadId));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_command_receipts "
                        + "where operation_id = ? and action = 'LEAD_CAPTURE'",
                Long.class, operationId));

        String changed = body.replace("+919876543210", "+919876543211");
        HttpResponse<String> conflict = send("POST", "/api/v1/operations/leads",
                owner.token(), changed, "Idempotency-Key", operationId.toString());
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId()));

        HttpResponse<String> receipt = send("GET",
                "/api/v1/operations/lead-commands/" + operationId + "?action=LEAD_CAPTURE",
                owner.token(), null);
        assertEquals(200, receipt.statusCode(), receipt.body());
        assertEquals("RECORDED", mapper.readTree(receipt.body()).path("state").asText());
    }

    @Test
    void signedMetaEventsReplayByEventIdentityWithoutDemandOrCommunication() throws Exception {
        String leadPrefix = "lead-live-" + UUID.randomUUID().toString().substring(0, 8);
        String eventA = "{\"value\":{\"leadgen_id\":\"" + leadPrefix + "-a\""
                + ",\"form_id\":\"form-202\",\"ad_id\":100,\"field_data\":[]}"
                + ",\"field\":\"leadgen\"}";
        String eventB = "{\"value\":{\"leadgen_id\":\"" + leadPrefix + "-b\""
                + ",\"form_id\":\"form-202\",\"ad_id\":200,\"field_data\":[]}"
                + ",\"field\":\"leadgen\"}";
        String batch = "{\"object\":\"page\",\"entry\":[{\"id\":\"page-101\","
                + "\"changes\":[" + eventA + "," + eventB + "]}]}";
        long leadsBefore = jdbc.queryForObject(
                "select count(*) from lead_routing_queue", Long.class);
        long activitiesBefore = jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class);

        HttpResponse<String> initial = metaWebhook(batch);
        assertEquals(200, initial.statusCode(), initial.body());
        assertEquals("EVENT_RECEIVED", initial.body());

        String aAlone = "{\"entry\":[{\"changes\":[" + eventA
                + "],\"id\":\"page-101\"}],\"object\":\"page\"}";
        assertEquals(200, metaWebhook(aAlone).statusCode());
        String reordered = "{\"object\":\"page\",\"entry\":[{\"id\":\"page-101\","
                + "\"changes\":[" + eventB + "," + eventA + "]}]}";
        assertEquals(200, metaWebhook(reordered).statusCode());

        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id like ?",
                Long.class, leadPrefix + "%"));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id like ? and processing_state = 'QUARANTINED' "
                        + "and error_code = 'SOURCE_ACTIVATION_NOT_APPROVED' "
                        + "and fingerprint_version = 'EVENT_CANONICAL_V2'",
                Long.class, leadPrefix + "%"));

        String changedA = batch.replace("\"ad_id\":100", "\"ad_id\":101");
        HttpResponse<String> changed = metaWebhook(changedA);
        assertEquals(409, changed.statusCode(), changed.body());
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id like ?",
                Long.class, leadPrefix + "%"));

        HttpResponse<String> missingSignature = send("POST", "/api/v1/webhooks/meta-leads",
                null, batch);
        assertEquals(403, missingSignature.statusCode(), missingSignature.body());
        HttpResponse<String> staleSignature = send("POST", "/api/v1/webhooks/meta-leads",
                null, batch + " ", "X-Hub-Signature-256", metaSignature(batch));
        assertEquals(403, staleSignature.statusCode(), staleSignature.body());
        HttpResponse<String> wrongForm = metaWebhook(batch.replace("form-202", "form-foreign"));
        assertEquals(403, wrongForm.statusCode(), wrongForm.body());
        HttpResponse<String> wrongPage = metaWebhook(batch.replace("page-101", "page-other"));
        assertEquals(200, wrongPage.statusCode(), wrongPage.body());
        assertEquals("EVENT_IGNORED", wrongPage.body());

        String concurrentBody = "{\"object\":\"page\",\"entry\":[{\"id\":\"page-101\","
                + "\"changes\":[" + eventA + "]}]}";
        List<CompletableFuture<HttpResponse<String>>> deliveries =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                            try {
                                return metaWebhook(concurrentBody);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        }))
                        .toList();
        for (CompletableFuture<HttpResponse<String>> delivery : deliveries) {
            assertEquals(200, delivery.get(15, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id like ?",
                Long.class, leadPrefix + "%"));
        assertEquals(leadsBefore, jdbc.queryForObject(
                "select count(*) from lead_routing_queue", Long.class));
        assertEquals(activitiesBefore, jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from visit_sessions", Long.class));
    }

    @Test
    void sourceRecoveryExpiresAndFencesTheOlderWorkerLease() throws Exception {
        String leadgenId = "source-recovery-" + UUID.randomUUID();
        String value = "{\"leadgen_id\":\"" + leadgenId + "\",\"form_id\":\"form-202\","
                + "\"field_data\":[{\"name\":\"full_name\",\"values\":[\"Synthetic recovery\"]}]}";
        String payload = "{\"object\":\"page\",\"entry\":[{\"id\":\"page-101\","
                + "\"changes\":[{\"value\":" + value + ",\"field\":\"leadgen\"}]}]}";
        HttpResponse<String> intake = metaWebhook(payload);
        assertEquals(200, intake.statusCode(), intake.body());

        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "select id::text from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id = ?", String.class, leadgenId));
        Actor admin = staff("source-recovery-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        String stalePayload = "{\"customerName\":\"Stale source result\"}";
        String recoveredPayload = "{\"customerName\":\"Recovered source result\"}";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Instant firstClaim = Instant.parse("2026-10-22T05:00:00Z");
        TEST_SOURCE.configure(stalePayload, recoveredPayload, started, release);
        testClock.fixAt(firstClaim);

        CompletableFuture<HttpResponse<String>> stale = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads/source-exceptions/" + eventId + "/retry",
                        admin.token(), null);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            assertEquals(true, started.await(10, TimeUnit.SECONDS));
            testClock.fixAt(firstClaim.plusSeconds(360));
            HttpResponse<String> recovered = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + eventId + "/retry",
                    admin.token(), null);
            assertEquals(200, recovered.statusCode(), recovered.body());
            assertEquals("no-store", recovered.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("AWAITING_REVIEW", mapper.readTree(recovered.body()).path("state").asText());
            assertEquals(2, mapper.readTree(recovered.body()).path("attempts").asInt());

            release.countDown();
            HttpResponse<String> staleResponse = stale.get(10, TimeUnit.SECONDS);
            assertEquals(409, staleResponse.statusCode(), staleResponse.body());
            assertEquals("no-store", staleResponse.headers().firstValue("Cache-Control").orElse(""));

            Map<String, Object> stored = jdbc.queryForMap(
                    "select processing_state, attempts, extracted_payload_ciphertext, "
                            + "extracted_payload_iv, extraction_key_version "
                            + "from lead_source_events where id = ?", eventId);
            assertEquals("AWAITING_REVIEW", stored.get("processing_state"));
            assertEquals(2, ((Number) stored.get("attempts")).intValue());
            String ciphertext = (String) stored.get("extracted_payload_ciphertext");
            assertNotNull(ciphertext);
            assertFalse(ciphertext.contains(recoveredPayload));
            assertEquals(recoveredPayload, protection.reveal(ciphertext,
                    (String) stored.get("extracted_payload_iv"),
                    (String) stored.get("extraction_key_version")));
        } finally {
            release.countDown();
            TEST_SOURCE.reset();
            testClock.useDatabaseClock();
        }
    }

    @Test
    void expiredThirdSourceClaimDurablyQuarantinesWithoutAFourthExtraction()
            throws Exception {
        SourceFixture source = sourceFixture("source-expired-third");
        Actor admin = staff("source-expired-third-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        LeadDataProtection.ProtectedValue retained = protection.protect(
                "{\"prior\":\"retained source truth\"}");
        UUID expiredLease = UUID.randomUUID();
        jdbc.update("update lead_source_events set attempts = 3, processing_state = 'PROCESSING', "
                        + "lease_token = ?, lease_until = now() - interval '1 minute', "
                        + "extracted_payload_ciphertext = ?, extracted_payload_iv = ?, "
                        + "extraction_key_version = ?, error_code = null where id = ?",
                expiredLease, retained.ciphertext(), retained.iv(), retained.keyId(),
                source.eventId());
        TEST_SOURCE.configure("{\"customerName\":\"Must not be fetched\"}",
                "{\"customerName\":\"Must not be fetched\"}", null, null);
        try {
            HttpResponse<String> exhausted = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + source.eventId() + "/retry",
                    admin.token(), null);
            assertEquals(409, exhausted.statusCode(), exhausted.body());
            assertEquals("no-store", exhausted.headers().firstValue("Cache-Control").orElse(""));

            Map<String, Object> stored = jdbc.queryForMap(
                    "select provider, provider_account_id, external_event_id, provider_form_id, "
                            + "processing_state, attempts, error_code, lease_token, lease_until, "
                            + "extracted_payload_ciphertext, extracted_payload_iv, extraction_key_version "
                            + "from lead_source_events where id = ?", source.eventId());
            assertEquals("QUARANTINED", stored.get("processing_state"));
            assertEquals(3, ((Number) stored.get("attempts")).intValue());
            assertEquals("SOURCE_RETRY_EXHAUSTED", stored.get("error_code"));
            assertNull(stored.get("lease_token"));
            assertNull(stored.get("lease_until"));
            assertEquals("META_LEADGEN", stored.get("provider"));
            assertEquals(source.accountId(), stored.get("provider_account_id"));
            assertEquals(source.externalId(), stored.get("external_event_id"));
            assertEquals(source.formId(), stored.get("provider_form_id"));
            assertEquals(retained.ciphertext(), stored.get("extracted_payload_ciphertext"));
            assertEquals(retained.iv(), stored.get("extracted_payload_iv"));
            assertEquals(retained.keyId(), stored.get("extraction_key_version"));
            assertEquals(0, TEST_SOURCE.calls());

            HttpResponse<String> replayed = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + source.eventId() + "/retry",
                    admin.token(), null);
            assertEquals(409, replayed.statusCode(), replayed.body());
            assertEquals(0, TEST_SOURCE.calls());
            assertEquals(3, jdbc.queryForObject(
                    "select attempts from lead_source_events where id = ?",
                    Integer.class, source.eventId()));
        } finally {
            TEST_SOURCE.reset();
        }
    }

    @Test
    void sourceExtractionAndPayloadFailuresQuarantineWithNoPlaintextOrLease() throws Exception {
        Actor admin = staff("source-failure-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        List<SourceFixture> failures = new ArrayList<>();
        TEST_SOURCE.configureFailure(new IllegalStateException("Controlled extraction network failure"));
        try {
            SourceFixture network = sourceFixture("source-network-failure");
            failures.add(network);
            assertEquals(500, send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + network.eventId() + "/retry",
                    admin.token(), null).statusCode());

            for (String payload : List.of(" ", "{not-json", "x".repeat(1_000_001))) {
                SourceFixture malformed = sourceFixture("source-payload-failure");
                failures.add(malformed);
                TEST_SOURCE.configure(payload, payload, null, null);
                HttpResponse<String> response = send("POST",
                        "/api/v1/operations/leads/source-exceptions/" + malformed.eventId() + "/retry",
                        admin.token(), null);
                assertEquals(500, response.statusCode(), response.body());
            }

            for (SourceFixture failure : failures) {
                Map<String, Object> stored = jdbc.queryForMap(
                        "select processing_state, attempts, error_code, lease_token, lease_until, "
                                + "extracted_payload_ciphertext, extracted_payload_iv, extraction_key_version "
                                + "from lead_source_events where id = ?", failure.eventId());
                assertEquals("QUARANTINED", stored.get("processing_state"));
                assertEquals(1, ((Number) stored.get("attempts")).intValue());
                assertEquals("SOURCE_EXTRACTION_FAILED", stored.get("error_code"));
                assertNull(stored.get("lease_token"));
                assertNull(stored.get("lease_until"));
                assertNull(stored.get("extracted_payload_ciphertext"));
                assertNull(stored.get("extracted_payload_iv"));
                assertNull(stored.get("extraction_key_version"));
            }
        } finally {
            TEST_SOURCE.reset();
        }
    }

    @Test
    void failedSourceCompletionRollsBackAndSupportsAFreshPersistedRetry() throws Exception {
        SourceFixture source = sourceFixture("source-completion-failure");
        Actor admin = staff("source-completion-failure-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        String payload = "{\"customerName\":\"Persisted after safe retry\"}";
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "f2_complete_failure_" + suffix;
        String trigger = "trg_" + function;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $f2$ "
                + "begin if new.id = '" + source.eventId()
                + "'::uuid and new.processing_state = 'AWAITING_REVIEW' then "
                + "raise exception 'Controlled source completion failure'; end if; return new; end $f2$");
        jdbc.execute("create trigger " + trigger + " before update on lead_source_events "
                + "for each row execute function " + function + "()");
        TEST_SOURCE.configure(payload, payload, null, null);
        try {
            HttpResponse<String> failed = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + source.eventId() + "/retry",
                    admin.token(), null);
            assertEquals(500, failed.statusCode(), failed.body());

            Map<String, Object> recovered = jdbc.queryForMap(
                    "select processing_state, attempts, error_code, lease_token, lease_until, "
                            + "extracted_payload_ciphertext from lead_source_events where id = ?",
                    source.eventId());
            assertEquals("QUARANTINED", recovered.get("processing_state"));
            assertEquals(1, ((Number) recovered.get("attempts")).intValue());
            assertEquals("SOURCE_EXTRACTION_FAILED", recovered.get("error_code"));
            assertNull(recovered.get("lease_token"));
            assertNull(recovered.get("lease_until"));
            assertNull(recovered.get("extracted_payload_ciphertext"));
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_source_events");
            jdbc.execute("drop function if exists " + function + "()");
        }

        TEST_SOURCE.configure(payload, payload, null, null);
        try {
            HttpResponse<String> retried = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + source.eventId() + "/retry",
                    admin.token(), null);
            assertEquals(200, retried.statusCode(), retried.body());
            assertEquals("AWAITING_REVIEW", mapper.readTree(retried.body()).path("state").asText());
            assertEquals(2, mapper.readTree(retried.body()).path("attempts").asInt());
            Map<String, Object> completed = jdbc.queryForMap(
                    "select processing_state, attempts, lease_token, lease_until, "
                            + "extracted_payload_ciphertext, extracted_payload_iv, extraction_key_version "
                            + "from lead_source_events where id = ?", source.eventId());
            assertEquals("AWAITING_REVIEW", completed.get("processing_state"));
            assertEquals(2, ((Number) completed.get("attempts")).intValue());
            assertNull(completed.get("lease_token"));
            assertNull(completed.get("lease_until"));
            assertEquals(payload, protection.reveal(
                    (String) completed.get("extracted_payload_ciphertext"),
                    (String) completed.get("extracted_payload_iv"),
                    (String) completed.get("extraction_key_version")));
        } finally {
            TEST_SOURCE.reset();
        }
    }

    @Test
    void revocationWinningWhileClaimWaitsDeniesWithoutClaimOrExtraction() throws Exception {
        SourceFixture source = sourceFixture("source-revocation-wins");
        Actor admin = staff("source-revocation-wins-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        TEST_SOURCE.configure("{\"customerName\":\"Must not run\"}",
                "{\"customerName\":\"Must not run\"}", null, null);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from staff_access_grants where user_id = "
                        + admin.user().getId() + " and revoked_at is null order by id for update").close();
                CompletableFuture<HttpResponse<String>> waiting = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send("POST", "/api/v1/operations/leads/source-exceptions/"
                                + source.eventId() + "/retry", admin.token(), null);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                });
                awaitCondition(() -> blockedSourceAuthorityCount() >= 1, 10,
                        "Source claim did not wait on the held staff authority row");
                statement.executeUpdate("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoked_by_user_id = " + admin.user().getId()
                        + ", revoke_reason_code = 'F3_REVOCATION_WINS' where id = "
                        + admin.grants().get(0).getId());
                writer.commit();
                HttpResponse<String> denied = waiting.get(20, TimeUnit.SECONDS);
                assertEquals(403, denied.statusCode(), denied.body());
                assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElse(""));
            }
        } finally {
            TEST_SOURCE.reset();
        }
        assertEquals(0, TEST_SOURCE.calls());
        assertEquals(0, jdbc.queryForObject(
                "select attempts from lead_source_events where id = ?",
                Integer.class, source.eventId()));
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "select processing_state from lead_source_events where id = ?",
                String.class, source.eventId()));
        assertNull(jdbc.queryForObject(
                "select extracted_payload_ciphertext from lead_source_events where id = ?",
                String.class, source.eventId()));
    }

    @Test
    void authorizedClaimCommitWinsWhileLaterRevocationDeniesExtractionCompletion()
            throws Exception {
        SourceFixture source = sourceFixture("source-claim-wins");
        Actor admin = staff("source-claim-wins-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TEST_SOURCE.configure("{\"customerName\":\"Completion must be denied\"}",
                "{\"customerName\":\"Completion must be denied\"}", started, release);
        try (Connection eventHolder = schemaConnection()) {
            eventHolder.setAutoCommit(false);
            try (Statement statement = eventHolder.createStatement()) {
                statement.executeQuery("select id from lead_source_events where id = '"
                        + source.eventId() + "'::uuid for update").close();
            }
            CompletableFuture<HttpResponse<String>> claim = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/source-exceptions/"
                            + source.eventId() + "/retry", admin.token(), null);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            awaitCondition(() -> blockedSourceEventCount() >= 1, 10,
                    "Authorized claim did not wait on the source event lock");

            CompletableFuture<Integer> revocation = CompletableFuture.supplyAsync(() -> jdbc.update(
                    "update staff_access_grants set revoked_at = clock_timestamp(), "
                            + "revoked_by_user_id = ?, revoke_reason_code = 'F3_CLAIM_COMMIT_WINS' "
                            + "where id = ?", admin.user().getId(), admin.grants().get(0).getId()));
            awaitCondition(() -> blockedSourceAuthorityCount() >= 1, 10,
                    "Revocation did not wait for the admitted claim's authority hold");
            assertFalse(revocation.isDone(),
                    "Revocation must not commit while the authorized claim is still waiting");

            eventHolder.commit();
            assertEquals(true, started.await(10, TimeUnit.SECONDS));
            assertEquals(1, revocation.get(20, TimeUnit.SECONDS));
            assertFalse(claim.isDone(), "Extraction must remain outside the guarded claim transaction");
            release.countDown();
            HttpResponse<String> deniedCompletion = claim.get(20, TimeUnit.SECONDS);
            assertEquals(403, deniedCompletion.statusCode(), deniedCompletion.body());
        } finally {
            release.countDown();
            TEST_SOURCE.reset();
        }

        Map<String, Object> stored = jdbc.queryForMap(
                "select processing_state, attempts, lease_token, lease_until, "
                        + "extracted_payload_ciphertext from lead_source_events where id = ?",
                source.eventId());
        assertEquals("PROCESSING", stored.get("processing_state"));
        assertEquals(1, ((Number) stored.get("attempts")).intValue());
        assertNotNull(stored.get("lease_token"));
        assertNotNull(stored.get("lease_until"));
        assertNull(stored.get("extracted_payload_ciphertext"));
    }

    @Test
    void inactiveProfileIsDeniedBeforeASourceClaim() throws Exception {
        Actor inactive = staff("source-inactive-profile",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        SourceFixture inactiveSource = sourceFixture("source-inactive-profile");
        jdbc.update("update employee_profiles set staff_active = false, "
                + "staff_deactivated_at = now(), version = version + 1 where user_id = ?",
                inactive.user().getId());
        HttpResponse<String> inactiveResponse = send("POST",
                "/api/v1/operations/leads/source-exceptions/" + inactiveSource.eventId() + "/retry",
                inactive.token(), null);
        assertEquals(403, inactiveResponse.statusCode(), inactiveResponse.body());
        assertEquals(0, jdbc.queryForObject(
                "select attempts from lead_source_events where id = ?",
                Integer.class, inactiveSource.eventId()));
        assertEquals(0, TEST_SOURCE.calls());
    }

    @Test
    void oneRevokedGrantDoesNotDenyAnotherEffectiveGlobalAdminGrant() throws Exception {
        Actor admin = staff("source-two-global-grants",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null),
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        SourceFixture source = sourceFixture("source-two-global-grants");
        jdbc.update("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoked_by_user_id = ?, revoke_reason_code = 'F3_ONE_GRANT_REVOKED' "
                        + "where id = ?",
                admin.user().getId(), admin.grants().get(0).getId());
        TEST_SOURCE.configure("{\"customerName\":\"Equivalent grant remains\"}",
                "{\"customerName\":\"Equivalent grant remains\"}", null, null);
        try {
            HttpResponse<String> completed = send("POST",
                    "/api/v1/operations/leads/source-exceptions/" + source.eventId() + "/retry",
                    admin.token(), null);
            assertEquals(200, completed.statusCode(), completed.body());
            assertEquals("AWAITING_REVIEW", mapper.readTree(completed.body()).path("state").asText());
            assertEquals(1, mapper.readTree(completed.body()).path("attempts").asInt());
        } finally {
            TEST_SOURCE.reset();
        }
    }

    @Test
    void concurrentSourceAttachmentWinsWithoutAStaleCompletionOverwritingIt() throws Exception {
        SupportedCity city = city("source-attach-race");
        OperatingTeam team = team(city, "source-attach-race");
        Actor owner = coordinator("source-attach-race-owner", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "source-attach-race");
        SourceFixture source = sourceFixture("source-attach-race");
        Actor admin = staff("source-attach-race-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TEST_SOURCE.configure("{\"customerName\":\"Stale completion\"}",
                "{\"customerName\":\"Stale completion\"}", started, release);
        try {
            CompletableFuture<HttpResponse<String>> retry = CompletableFuture.supplyAsync(() -> {
                try {
                    return send("POST", "/api/v1/operations/leads/source-exceptions/"
                            + source.eventId() + "/retry", admin.token(), null);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            assertEquals(true, started.await(10, TimeUnit.SECONDS));
            String attach = "{\"expectedVersion\":" + lead.getVersion()
                    + ",\"eventId\":\"" + source.eventId()
                    + "\",\"decision\":\"ATTACH_ENQUIRY\"}";
            HttpResponse<String> attached = send("POST",
                    "/api/v1/operations/leads/" + lead.getId() + "/source-events",
                    owner.token(), attach, "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(200, attached.statusCode(), attached.body());
            release.countDown();
            HttpResponse<String> stale = retry.get(20, TimeUnit.SECONDS);
            assertEquals(409, stale.statusCode(), stale.body());
        } finally {
            release.countDown();
            TEST_SOURCE.reset();
        }
        Map<String, Object> stored = jdbc.queryForMap(
                "select processing_state, attempts, extracted_payload_ciphertext, lead_id "
                        + "from lead_source_events where id = ?", source.eventId());
        assertEquals("PROCESSED", stored.get("processing_state"));
        assertEquals(1, ((Number) stored.get("attempts")).intValue());
        assertEquals(lead.getId(), ((Number) stored.get("lead_id")).longValue());
        assertNull(stored.get("extracted_payload_ciphertext"));
    }

    @Test
    void revokedCaptureAuthorityCreatesNoPartialLeadSourceActivityOrReceipt() throws Exception {
        SupportedCity city = city("revoked-capture");
        OperatingTeam team = team(city, "revoked-capture");
        Actor actor = coordinator("revoked-capture-owner", team);
        StaffAccessGrant actorGrant = actor.grants().get(0);
        actorGrant.revoke(actor.user(), Instant.now(), "TEST_REVOCATION");
        grants.saveAndFlush(actorGrant);
        long before = jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId());
        long activitiesBefore = jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class);
        long receiptsBefore = jdbc.queryForObject(
                "select count(*) from lead_command_receipts", Long.class);
        long sourceEventsBefore = jdbc.queryForObject(
                "select count(*) from lead_source_events", Long.class);
        String body = "{\"cityId\":" + city.getId() + ",\"teamId\":" + team.getId()
                + ",\"contactPhone\":\"+919876543210\"}";

        HttpResponse<String> response = send("POST", "/api/v1/operations/leads",
                actor.token(), body, "Idempotency-Key", UUID.randomUUID().toString());

        assertEquals(403, response.statusCode(), response.body());
        assertEquals(before, jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId()));
        assertEquals(sourceEventsBefore, jdbc.queryForObject(
                "select count(*) from lead_source_events", Long.class));
        assertEquals(activitiesBefore, jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class));
        assertEquals(receiptsBefore, jdbc.queryForObject(
                "select count(*) from lead_command_receipts", Long.class));
    }

    @Test
    void captureHoldsGuardsAgainstACompetingAuthorityRevocation() throws Exception {
        SupportedCity city = city("capture-race");
        OperatingTeam team = team(city, "capture-race");
        Actor actor = coordinator("capture-race-owner", team);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        int lockKey = Math.floorMod(suffix.hashCode(), 1_000_000) + 7_000_000;
        String function = "g01_capture_hold_" + suffix;
        String trigger = "trg_" + function;
        String body = "{\"cityId\":" + city.getId() + ",\"teamId\":" + team.getId()
                + ",\"customerName\":\"Synthetic race customer\""
                + ",\"contactPhone\":\"+919876543219\"}";
        long leadsBefore = jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId());
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $g01$ "
                + "begin perform pg_advisory_xact_lock(" + lockKey + "); "
                + "perform pg_sleep(1.2); return new; end $g01$");
        jdbc.execute("create trigger " + trigger + " before insert on lead_routing_queue "
                + "for each row execute function " + function + "()");

        CompletableFuture<HttpResponse<String>> capture = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads", actor.token(), body,
                        "Idempotency-Key", UUID.randomUUID().toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                Long held = jdbc.queryForObject(
                        "select count(*) from pg_locks where locktype = 'advisory' "
                                + "and classid = 0 and objid = ? and granted",
                        Long.class, (long) lockKey);
                if (held != null && held > 0) break;
                if (attempt == 199) throw new AssertionError("Capture did not hold its domain guard");
                Thread.sleep(25);
            }

            CompletableFuture<Integer> revocation = CompletableFuture.supplyAsync(() -> jdbc.update(
                    "update staff_access_grants set revoked_at = clock_timestamp(), "
                            + "revoked_by_user_id = ?, revoke_reason_code = 'G01_RACE' where id = ?",
                    actor.user().getId(), actor.grants().get(0).getId()));
            boolean observedWait = false;
            for (int attempt = 0; attempt < 200; attempt++) {
                Long blocked = jdbc.queryForObject(
                        "select count(*) from pg_stat_activity "
                                + "where datname = current_database() and pid <> pg_backend_pid() "
                                + "and wait_event_type = 'Lock' and query like '%staff_access_grants%'",
                        Long.class);
                if (blocked != null && blocked > 0) {
                    observedWait = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(observedWait, "The revocation writer never blocked on the capture guard");

            HttpResponse<String> admitted = capture.get(10, TimeUnit.SECONDS);
            assertEquals(201, admitted.statusCode(), admitted.body());
            assertEquals(1, revocation.get(10, TimeUnit.SECONDS));
            assertEquals(leadsBefore + 1L, jdbc.queryForObject(
                    "select count(*) from lead_routing_queue where supported_city_id = ?",
                    Long.class, city.getId()));

            HttpResponse<String> denied = send("POST", "/api/v1/operations/leads",
                    actor.token(), body, "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(leadsBefore + 1L, jdbc.queryForObject(
                    "select count(*) from lead_routing_queue where supported_city_id = ?",
                    Long.class, city.getId()));
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on lead_routing_queue");
            jdbc.execute("drop function if exists " + function + "()");
        }
    }

    @Test
    void profileDeactivationWinningWhileCaptureWaitsCreatesNoPartialCapture() throws Exception {
        SupportedCity city = city("capture-deactivation-wins");
        OperatingTeam team = team(city, "capture-deactivation-wins");
        Actor actor = coordinator("capture-deactivation-wins-owner", team);
        String body = "{\"cityId\":" + city.getId() + ",\"teamId\":" + team.getId()
                + ",\"customerName\":\"Synthetic deactivation race\""
                + ",\"contactPhone\":\"+919876543218\"}";
        long leadsBefore = jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId());
        long activitiesBefore = jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class);
        long receiptsBefore = jdbc.queryForObject(
                "select count(*) from lead_command_receipts", Long.class);
        try (Connection writer = schemaConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeQuery("select id from employee_profiles where user_id = "
                        + actor.user().getId() + " for update").close();
                CompletableFuture<HttpResponse<String>> capture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send("POST", "/api/v1/operations/leads", actor.token(), body,
                                "Idempotency-Key", UUID.randomUUID().toString());
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                });
                awaitCondition(() -> blockedDuplicateLookupCount() >= 1, 10,
                        "Capture did not wait on the held staff profile");
                statement.executeUpdate("update employee_profiles set staff_active = false, "
                        + "staff_deactivated_at = clock_timestamp(), version = version + 1 "
                        + "where user_id = " + actor.user().getId());
                statement.executeUpdate("update staff_access_grants set revoked_at = clock_timestamp(), "
                        + "revoked_by_user_id = " + actor.user().getId()
                        + ", revoke_reason_code = 'G01_DEACTIVATION_WINS' where id = "
                        + actor.grants().get(0).getId());
                writer.commit();
                HttpResponse<String> denied = capture.get(20, TimeUnit.SECONDS);
                assertEquals(403, denied.statusCode(), denied.body());
                assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElse(""));
            }
        }
        assertEquals(leadsBefore, jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId()));
        assertEquals(activitiesBefore, jdbc.queryForObject(
                "select count(*) from lead_activities", Long.class));
        assertEquals(receiptsBefore, jdbc.queryForObject(
                "select count(*) from lead_command_receipts", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = ?",
                Long.class, Long.toString(actor.user().getId())));
    }

    @Test
    void diagnosticOnlyAdminSeesNoOrdinaryLeadDataWhileCombinedGrantRetainsItsScope() throws Exception {
        SupportedCity city = city("diagnostic-separation");
        OperatingTeam team = team(city, "diagnostic-separation");
        Actor owner = coordinator("diagnostic-separation-owner", team);
        LeadRoutingQueue lead = lead(city, team, owner.user(), "Diagnostic separation Lead");
        Actor admin = staff("diagnostic-separation-admin",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null));
        Actor combined = staff("diagnostic-combined-supervisor",
                grantSpec(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null),
                grantSpec(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team));

        HttpResponse<String> adminList = send("GET", "/api/v1/operations/leads",
                admin.token(), null);
        assertEquals(403, adminList.statusCode(), adminList.body());
        assertFalse(adminList.body().contains("totalElements"));
        assertEquals("no-store", adminList.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(403, send("GET", "/api/v1/operations/leads/" + lead.getId(),
                admin.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/leads/" + lead.getId() + "/activities",
                admin.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/leads/" + lead.getId() + "/shares",
                admin.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/leads/" + lead.getId() + "/contact",
                admin.token(), null).statusCode());
        assertEquals(403, send("PATCH", "/api/v1/operations/leads/" + lead.getId(),
                admin.token(), "{\"expectedVersion\":" + lead.getVersion()
                        + ",\"changes\":{\"customerSaid\":\"must not change\"}}").statusCode());

        HttpResponse<String> supervisorList = send("GET", "/api/v1/operations/leads",
                combined.token(), null);
        assertEquals(200, supervisorList.statusCode(), supervisorList.body());
        assertEquals(1, mapper.readTree(supervisorList.body()).path("items").size());
        assertEquals(200, send("GET", "/api/v1/operations/leads/" + lead.getId(),
                combined.token(), null).statusCode());
        assertEquals(200, send("GET", "/api/v1/operations/leads/" + lead.getId() + "/activities",
                combined.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/leads/" + lead.getId() + "/contact",
                combined.token(), null).statusCode());

        HttpResponse<String> ownerList = send("GET", "/api/v1/operations/leads",
                owner.token(), null);
        assertEquals(200, ownerList.statusCode(), ownerList.body());
        assertEquals(1, mapper.readTree(ownerList.body()).path("items").size());
    }

    @Test
    void manualEnquiryAttachesAtomicallyWithProtectedEvidenceAndLegacyReplayPrecedence()
            throws Exception {
        SupportedCity city = city("manual-attach");
        OperatingTeam team = team(city, "manual-attach");
        Actor owner = coordinator("manual-attach-owner", team);
        LeadRoutingQueue target = lead(city, team, owner.user(), "Existing authoritative Lead");
        target.setCustomerSaid("Existing customer requirement");
        target.setBudgetMin(new BigDecimal("15000"));
        target.setBudgetMax(new BigDecimal("18000"));
        target = leads.saveAndFlush(target);
        long originalVersion = target.getVersion();
        UUID operationId = UUID.randomUUID();
        String body = manualCaptureBody(city, team, target, "ATTACH_ENQUIRY",
                "OPERATOR_CONFIRMED_EXISTING_ENQUIRY");
        long sourceEventsBefore = jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = ?",
                Long.class, Long.toString(owner.user().getId()));

        HttpResponse<String> attached = send("POST", "/api/v1/operations/leads",
                owner.token(), body, "Idempotency-Key", operationId.toString());
        assertEquals(201, attached.statusCode(), attached.body());
        JsonNode response = mapper.readTree(attached.body());
        assertEquals("ATTACHED_TO_EXISTING",
                response.path("captureOutcome").path("resolution").asText());
        assertEquals(target.getId(), response.path("summary").path("id").asLong());
        assertTrue(response.path("sourceAttributions").findValues("protectedPayloadRetained")
                .stream().anyMatch(JsonNode::asBoolean));
        assertFalse(attached.body().contains("ciphertext"));

        Map<String, Object> persisted = jdbc.queryForMap(
                "select customer_said, budget_min, budget_max, owner_user_id, version "
                        + "from lead_routing_queue where id = ?", target.getId());
        assertEquals("Existing customer requirement", persisted.get("customer_said"));
        assertEquals(0, new BigDecimal("15000").compareTo(
                (BigDecimal) persisted.get("budget_min")));
        assertEquals(owner.user().getId(), ((Number) persisted.get("owner_user_id")).longValue());
        assertEquals(originalVersion + 1L, ((Number) persisted.get("version")).longValue());
        Map<String, Object> source = jdbc.queryForMap(
                "select lead_id, resolution_lead_id, fingerprint_version, "
                        + "extracted_payload_ciphertext, extracted_payload_iv "
                        + "from lead_source_events where external_event_id = ?", operationId.toString());
        assertEquals(target.getId(), ((Number) source.get("lead_id")).longValue());
        assertEquals(target.getId(), ((Number) source.get("resolution_lead_id")).longValue());
        assertEquals("MANUAL_ATTACHED_V1", source.get("fingerprint_version"));
        assertNotNull(source.get("extracted_payload_ciphertext"));
        assertNotNull(source.get("extracted_payload_iv"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'MANUAL_ENQUIRY_ATTACHED'",
                Long.class, target.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from operational_audit_events where target_type = 'LEAD' "
                        + "and target_id = ? and action_code = 'LEAD_MANUAL_ENQUIRY_ATTACHED'",
                Long.class, target.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_command_receipts where actor_user_id = ? "
                        + "and operation_id = ? and action = 'LEAD_CAPTURE'",
                Long.class, owner.user().getId(), operationId));

        HttpResponse<String> replay = send("POST", "/api/v1/operations/leads",
                owner.token(), body, "Idempotency-Key", operationId.toString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(sourceEventsBefore + 1L, jdbc.queryForObject(
                "select count(*) from lead_source_events where provider_account_id = ?",
                Long.class, Long.toString(owner.user().getId())));
        OperatingTeam foreignTeam = team(city, "manual-attach-foreign");
        Actor foreign = coordinator("manual-attach-foreign-owner", foreignTeam);
        LeadRoutingQueue currentTarget = leads.findById(target.getId()).orElseThrow();
        HttpResponse<String> foreignAttempt = send("POST", "/api/v1/operations/leads",
                foreign.token(), manualCaptureBody(city, foreignTeam, currentTarget,
                        "ATTACH_ENQUIRY", "OPERATOR_CONFIRMED_EXISTING_ENQUIRY"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(404, foreignAttempt.statusCode(), foreignAttempt.body());
        String changed = body.replace("OPERATOR_CONFIRMED_EXISTING_ENQUIRY",
                "OPERATOR_CONFIRMED_DIFFERENT_PAYLOAD");
        assertEquals(409, send("POST", "/api/v1/operations/leads",
                owner.token(), changed, "Idempotency-Key", operationId.toString()).statusCode());
    }

    @Test
    void manualEnquiryKeepDistinctCreatesOneIndependentLeadAndNeverMergesIdentity()
            throws Exception {
        SupportedCity city = city("manual-distinct");
        OperatingTeam team = team(city, "manual-distinct");
        Actor owner = coordinator("manual-distinct-owner", team);
        LeadRoutingQueue target = lead(city, team, owner.user(), "Distinct comparison Lead");
        long before = jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId());
        UUID operationId = UUID.randomUUID();
        String body = manualCaptureBody(city, team, target, "KEEP_DISTINCT",
                "OPERATOR_CONFIRMED_DISTINCT_ENQUIRY");

        HttpResponse<String> distinct = send("POST", "/api/v1/operations/leads",
                owner.token(), body, "Idempotency-Key", operationId.toString());
        assertEquals(201, distinct.statusCode(), distinct.body());
        JsonNode response = mapper.readTree(distinct.body());
        assertEquals("KEPT_DISTINCT", response.path("captureOutcome").path("resolution").asText());
        long createdLeadId = response.path("summary").path("id").asLong();
        assertNotEquals(target.getId(), createdLeadId);
        assertEquals(before + 1L, jdbc.queryForObject(
                "select count(*) from lead_routing_queue where supported_city_id = ?",
                Long.class, city.getId()));
        assertEquals(target.getVersion(), jdbc.queryForObject(
                "select version from lead_routing_queue where id = ?", Long.class, target.getId()));
        Map<String, Object> source = jdbc.queryForMap(
                "select lead_id, resolution_lead_id, extracted_payload_ciphertext "
                        + "from lead_source_events where external_event_id = ?", operationId.toString());
        assertEquals(createdLeadId, ((Number) source.get("lead_id")).longValue());
        assertEquals(target.getId(), ((Number) source.get("resolution_lead_id")).longValue());
        assertNotNull(source.get("extracted_payload_ciphertext"));
        assertNull(jdbc.queryForObject(
                "select linked_user_id from lead_routing_queue where id = ?",
                Long.class, createdLeadId));
        assertNull(jdbc.queryForObject(
                "select linked_user_id from lead_routing_queue where id = ?",
                Long.class, target.getId()));
    }

    @Test
    void concurrentManualAttachmentsSerializeToOneWinnerAndOneSourceEvent() throws Exception {
        SupportedCity city = city("manual-attach-race");
        OperatingTeam team = team(city, "manual-attach-race");
        Actor owner = coordinator("manual-attach-race-owner", team);
        LeadRoutingQueue target = lead(city, team, owner.user(), "Concurrent attach target");
        String firstBody = manualCaptureBody(city, team, target, "ATTACH_ENQUIRY",
                "OPERATOR_CONFIRMED_EXISTING_ENQUIRY");
        String secondBody = firstBody.replace("Synthetic phone enquiry",
                "Synthetic concurrent phone enquiry");
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads", owner.token(), firstBody,
                        "Idempotency-Key", firstOperation.toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/v1/operations/leads", owner.token(), secondBody,
                        "Idempotency-Key", secondOperation.toString());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        HttpResponse<String> firstResult = first.get(20, TimeUnit.SECONDS);
        HttpResponse<String> secondResult = second.get(20, TimeUnit.SECONDS);
        assertEquals(Set.of(201, 409),
                Set.of(firstResult.statusCode(), secondResult.statusCode()),
                firstResult.body() + "\n" + secondResult.body());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_source_events where external_event_id in (?, ?)",
                Long.class, firstOperation.toString(), secondOperation.toString()));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from lead_activities where lead_id = ? "
                        + "and activity_type = 'MANUAL_ENQUIRY_ATTACHED'",
                Long.class, target.getId()));
    }

    private static String manualCaptureBody(SupportedCity city, OperatingTeam team,
                                            LeadRoutingQueue target, String decision,
                                            String reason) {
        return "{\"cityId\":" + city.getId() + ",\"teamId\":" + team.getId()
                + ",\"customerName\":\"Synthetic attached customer\""
                + ",\"contactPhone\":\"+919811002244\""
                + ",\"customerSaid\":\"Synthetic phone enquiry with new requirements\""
                + ",\"intent\":\"RENT\",\"propertyType\":\"Flat\",\"bhk\":\"3 BHK\""
                + ",\"budgetMin\":25000,\"budgetMax\":32000,\"currency\":\"INR\""
                + ",\"sourceChannel\":\"PHONE_CALL\",\"preferredLocalityIds\":[]"
                + ",\"duplicateDecision\":\"" + decision + "\""
                + ",\"existingLeadId\":" + target.getId()
                + ",\"expectedExistingLeadVersion\":" + target.getVersion()
                + ",\"reasonCode\":\"" + reason + "\"}";
    }

    private static String contactBody(Long expectedLeadVersion, Long expectedPreferenceRevision,
                                      String destinationChoice, String countryCode,
                                      String number, String consentStatus) {
        return "{\"expectedLeadVersion\":" + expectedLeadVersion
                + ",\"expectedPreferenceRevision\":" + expectedPreferenceRevision
                + ",\"channel\":\"WHATSAPP\",\"destinationChoice\":\"" + destinationChoice + "\""
                + (countryCode == null ? "" : ",\"countryCallingCode\":\"" + countryCode + "\"")
                + (number == null ? "" : ",\"number\":\"" + number + "\"")
                + ",\"consentStatus\":\"" + consentStatus + "\""
                + ",\"consentWordingVersion\":\"property-sharing-v1\""
                + ",\"evidenceMethod\":\"CUSTOMER_VERBAL\"}";
    }

    private static String shortlistBody(Long expectedLeadVersion, List<Long> listingIds) {
        return "{\"expectedLeadVersion\":" + expectedLeadVersion
                + ",\"listingIds\":" + listingIds + "}";
    }

    private static String shortlistBody(Long expectedLeadVersion, Long expectedShortlistVersion,
                                        List<Long> listingIds) {
        return "{\"expectedLeadVersion\":" + expectedLeadVersion
                + ",\"expectedShortlistVersion\":" + expectedShortlistVersion
                + ",\"listingIds\":" + listingIds + "}";
    }

    private static String shareBody(UUID operationId, Long expectedLeadVersion,
                                    Long shortlistId, Long expectedShortlistVersion,
                                    Long contactPreferenceRevision) {
        return "{\"operationId\":\"" + operationId + "\""
                + ",\"expectedLeadVersion\":" + expectedLeadVersion
                + ",\"shortlistId\":" + shortlistId
                + ",\"expectedShortlistVersion\":" + expectedShortlistVersion
                + ",\"contactPreferenceRevision\":" + contactPreferenceRevision
                + ",\"mode\":\"COPY_LINKS\"}";
    }

    private PreparedHandoff prepareFinalHandoff(Actor owner, SupportedCity city,
                                                OperatingTeam team, RentalDetails property,
                                                String label) throws Exception {
        LeadRoutingQueue lead = lead(city, team, owner.user(), "final-handoff-" + label);
        HttpResponse<String> consent = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/contact-preferences",
                owner.token(), contactBody(lead.getVersion(), 0L, "SAME", null, null,
                        "GRANTED"));
        assertEquals(201, consent.statusCode(), consent.body());
        JsonNode preference = mapper.readTree(consent.body());
        HttpResponse<String> shortlistResponse = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shortlists",
                owner.token(), shortlistBody(preference.path("leadVersion").asLong(),
                        List.of(property.getId())),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, shortlistResponse.statusCode(), shortlistResponse.body());
        JsonNode shortlist = mapper.readTree(shortlistResponse.body());
        String command = shareBody(UUID.randomUUID(), shortlist.path("leadVersion").asLong(),
                shortlist.path("id").asLong(), shortlist.path("version").asLong(),
                preference.path("revision").asLong())
                .replace("COPY_LINKS", "MANUAL_BUSINESS_HANDOFF");
        HttpResponse<String> prepared = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares",
                owner.token(), command);
        assertEquals(201, prepared.statusCode(), prepared.body());
        return new PreparedHandoff(lead, preference, shortlist,
                UUID.fromString(mapper.readTree(prepared.body()).path("id").asText()));
    }

    private HttpResponse<String> finalHandoffAttempt(Actor actor, PreparedHandoff handoff)
            throws Exception {
        return send("POST", "/api/v1/operations/leads/" + handoff.lead().getId()
                        + "/shares/" + handoff.shareId() + "/events",
                actor.token(), "{\"eventId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"HANDOFF_ATTEMPTED\"}");
    }

    private void assertShareReplayConflict(Actor actor, LeadRoutingQueue lead, String command)
            throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/v1/operations/leads/" + lead.getId() + "/shares", actor.token(), command);
        assertEquals(409, response.statusCode(), response.body());
    }

    private G09ShareCommitSnapshot shareCommitSnapshot(
            Actor actor, Long leadId, Long shortlistId, UUID historicalShareId) {
        return new G09ShareCommitSnapshot(
                jdbc.queryForObject("select version from lead_routing_queue where id = ?",
                        Long.class, leadId),
                jdbc.queryForObject("select version from lead_shortlists where id = ?",
                        Long.class, shortlistId),
                jdbc.queryForObject("select count(*) from lead_shares where lead_id = ?",
                        Long.class, leadId),
                jdbc.queryForObject("select count(*) from lead_activities "
                                + "where lead_id = ? and activity_type = 'SHARE_PREPARED'",
                        Long.class, leadId),
                jdbc.queryForObject("select count(*) from operational_audit_events "
                                + "where target_type = 'LEAD' and target_id = ? "
                                + "and action_code = 'LEAD_SHARE_PREPARED'",
                        Long.class, leadId),
                jdbc.queryForObject("select count(*) from lead_command_receipts "
                                + "where actor_user_id = ? and action = 'LEAD_SHARE_PREPARE'",
                        Long.class, actor.user().getId()),
                jdbc.queryForObject("select count(*) from lead_share_events "
                                + "where share_id = ?", Long.class, historicalShareId),
                jdbc.queryForObject("select count(*) from visit_notification_outbox",
                        Long.class),
                jdbc.queryForObject("select row_to_json(share)::text from lead_shares share "
                        + "where share.id = ?", String.class, historicalShareId));
    }

    private Actor coordinator(String label, OperatingTeam team) {
        return staff(label, grantSpec(StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team));
    }

    private Actor staff(String label, GrantSpec... specifications) {
        User user = new User();
        user.setEmail("p4-crm-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+9198" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000)));
        user.setRole(Role.ROLE_TENANT);
        user = users.saveAndFlush(user);
        EmployeeProfile profile = new EmployeeProfile(user, "OPERATIONS", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        List<StaffAccessGrant> createdGrants = new ArrayList<>();
        for (GrantSpec specification : specifications) {
            Instant now = Instant.now();
            Instant expiresAt = specification.capability() == StaffCapability.STAFF_ADMIN
                    ? null : now.plusSeconds(specification.expiresInSeconds());
            createdGrants.add(grants.saveAndFlush(new StaffAccessGrant(user,
                    specification.capability(), specification.scopeType(),
                    specification.city(), specification.team(),
                    now.minusSeconds(3600), expiresAt, null,
                    now, "CRM_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API)));
        }
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()),
                List.copyOf(createdGrants));
    }

    private Actor staffAt(String label, TimedGrantSpec... specifications) {
        User user = new User();
        user.setEmail("p4-crm-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+9198" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000)));
        user.setRole(Role.ROLE_TENANT);
        user = users.saveAndFlush(user);
        EmployeeProfile profile = new EmployeeProfile(user, "OPERATIONS", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        List<StaffAccessGrant> createdGrants = new ArrayList<>();
        Instant createdAt = Instant.now();
        for (TimedGrantSpec specification : specifications) {
            createdGrants.add(grants.saveAndFlush(new StaffAccessGrant(user,
                    specification.capability(), specification.scopeType(),
                    specification.city(), specification.team(),
                    specification.effectiveAt(), specification.expiresAt(), null,
                    createdAt, "CRM_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API)));
        }
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()),
                List.copyOf(createdGrants));
    }

    private SupportedCity city(String label) {
        return cities.saveAndFlush(new SupportedCity("crm-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), "CRM " + label, true));
    }

    private OperatingTeam team(SupportedCity city, String label) {
        return teams.saveAndFlush(new OperatingTeam(city, "crm-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), "CRM " + label, true));
    }

    private HighScope highScope(String label) {
        long cityId = 3_000_000_000L;
        long teamId = 3_000_000_001L;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into supported_cities(id, code, display_name, active) "
                        + "values (?, ?, ?, true)",
                cityId, "high-" + label + "-" + suffix, "High " + label);
        jdbc.update("insert into operating_teams(id, city_id, code, display_name, active) "
                        + "values (?, ?, ?, ?, true)",
                teamId, cityId, "high-team-" + suffix, "High Team " + label);
        return new HighScope(cities.findById(cityId).orElseThrow(),
                teams.findById(teamId).orElseThrow());
    }

    private Locality locality(SupportedCity city, String label) {
        Locality locality = new Locality(city.getDisplayName(), "crm-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private RentalDetails publicListing(Locality locality, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title + " " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic address");
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setBhkCount("2 BHK");
        listing.setMonthlyRent(new BigDecimal("18000.00"));
        listing.setSecurityDeposit(new BigDecimal("18000.00"));
        return listings.saveAndFlush(listing);
    }

    private LeadRoutingQueue lead(SupportedCity city, OperatingTeam team, User owner, String label) {
        LeadRoutingQueue lead = seedLead(city, team, owner, label, LeadStatus.NEW, null);
        lead.setContactPhoneNormalized("+919876543210");
        lead.setPhoneNumber("+919876543210");
        return leads.saveAndFlush(lead);
    }

    private LeadRoutingQueue matchingLead(SupportedCity city, OperatingTeam team, User owner,
                                          String label, String phone, LeadStatus status) {
        LeadRoutingQueue lead = seedLead(city, team, owner, label, status, null);
        lead.setContactPhoneNormalized(phone);
        lead.setPhoneNumber(phone);
        return leads.saveAndFlush(lead);
    }

    private LeadRoutingQueue seedLead(SupportedCity city, OperatingTeam team, User owner,
                                      String label, LeadStatus status, Instant followUpAt) {
        return leads.saveAndFlush(newLead(city, team, owner, label, status, followUpAt, null));
    }

    private LeadRoutingQueue seededRawLead(String label, LeadStatus status) {
        return leads.saveAndFlush(newLead(null, null, null, label, status, null, null));
    }

    private LeadRoutingQueue newLead(SupportedCity city, OperatingTeam team, User owner,
                                     String label, LeadStatus status, Instant followUpAt,
                                     LocalDateTime createdAt) {
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(city);
        lead.setOperatingTeam(team);
        lead.setOwner(owner);
        lead.setCustomerName(label);
        String syntheticPhone = "+9198" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().hashCode(), 100_000_000));
        lead.setContactPhoneNormalized(syntheticPhone);
        lead.setPhoneNumber(syntheticPhone);
        lead.setStatus(status);
        lead.setFollowUpAt(followUpAt);
        lead.setFollowUpZoneId(followUpAt == null ? null : "Asia/Kolkata");
        lead.setLegacyProvenance(LeadLegacyProvenance.VERIFIED_MANUAL);
        if (createdAt != null) {
            lead.setCreatedAt(createdAt);
            lead.setUpdatedAt(createdAt);
        }
        return lead;
    }

    private HttpResponse<String> visible(Actor actor, Long cityId, Long teamId, String status,
                                         boolean ownerMe, Instant followUpBefore,
                                         int page, int size) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (cityId != null) parameters.put("cityId", cityId.toString());
        if (teamId != null) parameters.put("teamId", teamId.toString());
        if (status != null) parameters.put("status", status);
        if (ownerMe) parameters.put("owner", "me");
        if (followUpBefore != null) parameters.put("followUpBefore", followUpBefore.toString());
        parameters.put("page", Integer.toString(page));
        parameters.put("size", Integer.toString(size));
        String path = "/api/v1/operations/leads?" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        return send("GET", path, actor.token(), null);
    }

    private HttpResponse<String> search(Actor actor, SupportedCity city, OperatingTeam team,
                                        String q, String followUp, String followUpZone,
                                        int page, int size) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("cityId", city.getId().toString());
        parameters.put("teamId", team.getId().toString());
        if (q != null) parameters.put("q", q);
        if (followUp != null) parameters.put("followUp", followUp);
        if (followUpZone != null) parameters.put("followUpZone", followUpZone);
        parameters.put("page", Integer.toString(page));
        parameters.put("size", Integer.toString(size));
        String path = "/api/v1/operations/leads?" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        return send("GET", path, actor.token(), null);
    }

    private HttpResponse<String> duplicate(Actor actor, Long cityId, String phone) throws Exception {
        String body = cityId == null
                ? "{\"contactPhone\":\"" + phone + "\"}"
                : "{\"cityId\":" + cityId + ",\"contactPhone\":\"" + phone + "\"}";
        return send("POST", "/api/v1/operations/leads/duplicate-candidates",
                actor.token(), body);
    }

    private CompletableFuture<HttpResponse<String>> duplicateAsync(
            Actor actor, Long cityId, String phone) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return duplicate(actor, cityId, phone);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private LostHttpResult duplicateWithoutClientAcknowledgement(
            Actor actor, Long cityId, String phone) throws Exception {
        return sendWithoutClientAcknowledgement(duplicateHttpRequest(actor, cityId, phone));
    }

    private LostHttpResult sendWithoutClientAcknowledgement(
            String method, String path, String token, String body,
            String header, String headerValue) throws Exception {
        return sendWithoutClientAcknowledgement(httpRequest(method, path, token, body, header, headerValue));
    }

    private LostHttpResult sendWithoutClientAcknowledgement(byte[] request) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket proxy = new ServerSocket(0, 1, loopback)) {
            proxy.setSoTimeout(20_000);
            CompletableFuture<ServerReply> captured = CompletableFuture.supplyAsync(() -> {
                try (Socket client = proxy.accept()) {
                    byte[] forwarded = readHttpRequest(client.getInputStream());
                    try (Socket upstream = new Socket()) {
                        upstream.connect(new InetSocketAddress(loopback, port), 5_000);
                        upstream.setSoTimeout(20_000);
                        OutputStream output = upstream.getOutputStream();
                        output.write(forwarded);
                        output.flush();
                        upstream.shutdownOutput();
                        String response = new String(readToEnd(upstream.getInputStream()),
                                StandardCharsets.UTF_8);
                        int firstLineEnd = response.indexOf("\r\n");
                        return new ServerReply(firstLineEnd < 0
                                ? response : response.substring(0, firstLineEnd), response);
                    }
                    // The accepted client socket is deliberately closed without forwarding a byte.
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            });
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(loopback, proxy.getLocalPort()), 5_000);
                client.setSoTimeout(30_000);
                OutputStream output = client.getOutputStream();
                output.write(request);
                output.flush();
                client.shutdownOutput();
                int clientRead = client.getInputStream().read();
                ServerReply reply = captured.get(30, TimeUnit.SECONDS);
                return new LostHttpResult(clientRead, reply.statusLine(), reply.response());
            }
        }
    }

    private byte[] duplicateHttpRequest(Actor actor, Long cityId, String phone)
            throws IOException {
        String body = "{\"cityId\":" + cityId + ",\"contactPhone\":\"" + phone + "\"}";
        return httpRequest("POST", "/api/v1/operations/leads/duplicate-candidates",
                actor.token(), body, null, null);
    }

    private byte[] httpRequest(String method, String path, String token, String body,
                               String header, String headerValue) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = method + " " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Origin: http://localhost:5173\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Content-Type: application/json\r\n"
                + "Accept: application/json\r\n"
                + (header == null ? "" : header + ": " + headerValue + "\r\n")
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        byte[] request = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, request, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, request, headerBytes.length, bodyBytes.length);
        return request;
    }

    private static byte[] readHttpRequest(InputStream input) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("Client closed before the HTTP request headers completed");
            }
            request.write(next);
            matched = switch (matched) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                default -> next == '\n' ? 4 : 0;
            };
        }
        String headers = request.toString(StandardCharsets.UTF_8);
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Client closed before the declared HTTP request body completed");
        }
        request.write(body);
        return request.toByteArray();
    }

    private static byte[] readToEnd(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            response.write(buffer, 0, read);
        }
        return response.toByteArray();
    }

    private void installDeadlockInversion(Actor first, int firstKey, Actor second, int secondKey) {
        jdbc.execute("create or replace function c05_lookup_hook() returns trigger "
                + "language plpgsql as $c05$ begin "
                + "if new.actor_user_id = " + first.user().getId() + " then "
                + "perform pg_advisory_xact_lock(" + firstKey + "); "
                + "perform pg_sleep(0.35); "
                + "perform pg_advisory_xact_lock(" + secondKey + "); "
                + "elsif new.actor_user_id = " + second.user().getId() + " then "
                + "perform pg_advisory_xact_lock(" + secondKey + "); "
                + "perform pg_sleep(0.35); "
                + "perform pg_advisory_xact_lock(" + firstKey + "); "
                + "end if; return new; end $c05$");
        jdbc.execute("create trigger c05_lookup_hook after insert on lead_lookup_events "
                + "for each row execute function c05_lookup_hook()");
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from pg_trigger where tgname = 'c05_lookup_hook' "
                        + "and not tgisinternal", Long.class));
    }

    private void installLookupAdvisoryBlock(Actor actor, int key, Integer sleepSeconds) {
        String action = sleepSeconds == null
                ? "perform pg_advisory_xact_lock(" + key + "); perform pg_sleep(30);"
                : "perform pg_sleep(" + sleepSeconds + ");";
        jdbc.execute("create or replace function c05_lookup_hook() returns trigger "
                + "language plpgsql as $c05$ begin "
                + "if new.actor_user_id = " + actor.user().getId() + " then "
                + action + " end if; return new; end $c05$");
        jdbc.execute("create trigger c05_lookup_hook after insert on lead_lookup_events "
                + "for each row execute function c05_lookup_hook()");
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from pg_trigger where tgname = 'c05_lookup_hook' "
                        + "and not tgisinternal", Long.class));
    }

    private void dropC05LookupHook() {
        jdbc.execute("drop trigger if exists c05_lookup_hook on lead_lookup_events");
        jdbc.execute("drop function if exists c05_lookup_hook()");
    }

    private void dropC05DeferredCommitHook() {
        jdbc.execute("drop trigger if exists c05_reject_lookup_commit on lead_lookup_events");
        jdbc.execute("drop function if exists c05_reject_lookup_commit()");
    }

    private long awaitAdvisoryHolder(
            int key, CompletableFuture<HttpResponse<String>> lookup) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Long> pids = jdbc.queryForList(
                    "select pid from pg_locks where locktype = 'advisory' "
                            + "and classid = 0 and objid = ? and granted order by pid",
                    Long.class, (long) key);
            if (!pids.isEmpty()) return pids.get(0);
            if (lookup.isDone()) {
                HttpResponse<String> response = lookup.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Lookup completed before acquiring advisory lock " + key
                        + ": status=" + response.statusCode() + " body=" + response.body());
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The lookup transaction did not acquire advisory lock " + key);
    }

    private long advisoryLockHolderCount(int... keys) {
        long holders = 0;
        for (int key : keys) {
            holders += jdbc.queryForObject(
                    "select count(distinct pid) from pg_locks where locktype = 'advisory' "
                            + "and classid = 0 and objid = ? and granted",
                    Long.class, (long) key);
        }
        return holders;
    }

    private RealSqlFailure singleRealSqlFailure() {
        List<RealSqlFailure> failures = testJdbc.realSqlFailures();
        assertEquals(1, failures.size(), "Expected one server-originated SQL failure: " + failures);
        return failures.get(0);
    }

    private static SqlError sqlError(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (!(cause instanceof java.sql.SQLException sqlFailure)) continue;
            Set<java.sql.SQLException> visitedSql =
                    Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (java.sql.SQLException link = sqlFailure;
                 link != null && visitedSql.add(link); link = link.getNextException()) {
                if (link.getSQLState() != null) {
                    return new SqlError(link.getSQLState(), link.getMessage());
                }
            }
        }
        return null;
    }

    private void seedLookupEvents(Actor actor, int count, Instant occurredAt) {
        for (int index = 0; index < count; index++) {
            jdbc.update("insert into lead_lookup_events(actor_user_id,purpose,occurred_at) "
                            + "values (?, 'DUPLICATE_CANDIDATE', ?)",
                    actor.user().getId(), Timestamp.from(occurredAt));
        }
    }

    private long lookupEventCount(Actor actor) {
        return jdbc.queryForObject("select count(*) from lead_lookup_events "
                        + "where actor_user_id = ? and purpose = 'DUPLICATE_CANDIDATE'",
                Long.class, actor.user().getId());
    }

    private Instant latestLookupEvent(Actor actor) {
        Timestamp value = jdbc.queryForObject("select occurred_at from lead_lookup_events "
                        + "where actor_user_id = ? and purpose = 'DUPLICATE_CANDIDATE' "
                        + "order by id desc limit 1",
                Timestamp.class, actor.user().getId());
        return value == null ? null : value.toInstant();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()",
                (rows, row) -> rows.getTimestamp(1).toInstant());
    }

    private long blockedDuplicateLookupCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and pid <> pg_backend_pid() "
                        + "and wait_event_type = 'Lock' "
                        + "and (query like '%employee_profiles%' "
                + "or query like '%supported_cities%' "
                + "or query like '%operating_teams%' "
                + "or query like '%lead_lookup_events%')",
                Long.class);
    }

    private long blockedSourceAuthorityCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and pid <> pg_backend_pid() "
                        + "and wait_event_type = 'Lock' "
                        + "and (query like '%employee_profiles%' "
                        + "or query like '%staff_access_grants%')",
                Long.class);
    }

    private long blockedSourceEventCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and pid <> pg_backend_pid() "
                        + "and wait_event_type = 'Lock' "
                        + "and query like '%lead_source_events%'",
                Long.class);
    }

    private long activeDuplicateLookupBackends() {
        return jdbc.queryForObject(
                "select count(distinct pid) from pg_stat_activity "
                        + "where datname = current_database() and pid <> pg_backend_pid() "
                        + "and (query like '%employee_profiles%' "
                        + "or query like '%supported_cities%' "
                        + "or query like '%operating_teams%' "
                        + "or query like '%lead_lookup_events%')",
                Long.class);
    }

    private ShareAdmissionHold installShareAdmissionHold(boolean enforceLockTimeout) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "hold_share_admission_" + suffix;
        String trigger = "trg_share_admission_" + suffix;
        String qualifiedFunction = schema + "." + function;
        String timeout = enforceLockTimeout
                ? "perform set_config('lock_timeout', '250ms', true); "
                : "";
        jdbc.execute("create function " + qualifiedFunction
                + "() returns trigger language plpgsql "
                + "as $share_hold$ begin "
                + timeout
                + "perform 1 from " + schema
                + ".share_event_admission_gate where id = 1 for update; "
                + "insert into " + schema
                + ".share_event_admission_witness(event_id, inserted_at) "
                + "values (new.id, clock_timestamp()); "
                + "return new; end $share_hold$");
        jdbc.execute("create trigger " + trigger
                + " before insert on " + schema
                + ".lead_share_events for each row execute function "
                + qualifiedFunction + "()");
        return new ShareAdmissionHold(function, trigger);
    }

    private void dropShareAdmissionHold(ShareAdmissionHold hold) {
        jdbc.execute("drop trigger if exists " + hold.trigger() + " on " + schema
                + ".lead_share_events");
        jdbc.execute("drop function if exists " + schema + "." + hold.function() + "()");
    }

    private long blockedShareAdmissionInsertCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() "
                        + "and pid <> pg_backend_pid() and wait_event_type = 'Lock' "
                        + "and query like '%insert into lead_share_events%'",
                Long.class);
    }

    private boolean boundaryGrantQualifies(Long grantId, Instant evaluatedAt, String operator) {
        String sql = """
                select exists (
                  select 1
                  from staff_access_grants g
                  join employee_profiles p on p.user_id = g.user_id and p.staff_active = true
                  left join operating_teams t on t.id = g.team_id
                  left join supported_cities c on c.id = coalesce(g.city_id, t.city_id)
                  cross join (select cast(? as timestamptz) as db_now) clock
                  where g.id = ?
                    and g.revoked_at is null
                    and g.effective_at <= clock.db_now
                    and (g.expires_at is null or clock.db_now %s g.expires_at)
                    and (g.scope_type = 'GLOBAL'
                      or (g.scope_type = 'CITY' and c.active = true)
                      or (g.scope_type = 'TEAM' and t.active = true and c.active = true))
                )
                """.formatted(operator);
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class,
                Timestamp.from(evaluatedAt), grantId));
    }

    private boolean boundaryUserQualifies(Long userId, Instant evaluatedAt, String operator) {
        return boundaryUserGrantCount(userId, evaluatedAt, operator) > 0;
    }

    private long boundaryUserGrantCount(Long userId, Instant evaluatedAt, String operator) {
        String sql = """
                select count(*)
                from staff_access_grants g
                join employee_profiles p on p.user_id = g.user_id and p.staff_active = true
                left join operating_teams t on t.id = g.team_id
                left join supported_cities c on c.id = coalesce(g.city_id, t.city_id)
                cross join (select cast(? as timestamptz) as db_now) clock
                where g.user_id = ?
                  and g.revoked_at is null
                  and g.effective_at <= clock.db_now
                  and (g.expires_at is null or clock.db_now %s g.expires_at)
                  and (g.scope_type = 'GLOBAL'
                    or (g.scope_type = 'CITY' and c.active = true)
                    or (g.scope_type = 'TEAM' and t.active = true and c.active = true))
                """.formatted(operator);
        return jdbc.queryForObject(sql, Long.class, Timestamp.from(evaluatedAt), userId);
    }

    private static void awaitCondition(BooleanSupplier condition, int timeoutSeconds,
                                       String failureMessage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError(failureMessage);
    }

    private long blockedFinalListingLockCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and usename = current_user "
                        + "and wait_event_type = 'Lock' and query like '%from listings where id in%'",
                Long.class);
    }

    private long blockedFinalShortlistLockCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and usename = current_user "
                        + "and pid <> pg_backend_pid() and wait_event_type = 'Lock' "
                        + "and query like '%from lead_shortlists%'",
                Long.class);
    }

    private long blockedStaffGrantLockCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and usename = current_user "
                        + "and pid <> pg_backend_pid() and wait_event_type = 'Lock' "
                        + "and query like '%staff_access_grants%' "
                        + "and query not like '%update staff_access_grants%'",
                Long.class);
    }

    private long blockedStaffGrantWriteCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity "
                        + "where datname = current_database() and usename = current_user "
                        + "and pid <> pg_backend_pid() and wait_event_type = 'Lock' "
                        + "and query like '%update staff_access_grants%'",
                Long.class);
    }

    private Map<String, Long> unrelatedOperationalCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("property_visit_requests", "visit_sessions",
                "tenant_visit_entitlement_accounts", "visit_notification_outbox")) {
            counts.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
        }
        return counts;
    }

    private Instant duplicateInSessionZone(Actor actor, Long cityId, String timezone) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            jdbc.execute("set local time zone '" + timezone + "'");
            leadService.duplicateCandidates(actor.user().getId(),
                    new com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidatesCommand(
                            cityId, "+919811111111"));
            return latestLookupEvent(actor);
        });
    }

    private void assertVisiblePage(HttpResponse<String> response, List<Long> expected,
                                   int size, int pageIndex) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        JsonNode page = mapper.readTree(response.body());
        assertEquals(expected.size(), page.path("totalElements").asLong());
        assertEquals(pageIndex, page.path("page").asInt());
        assertEquals(size, page.path("size").asInt());
        assertEquals(size == 0 ? 0 : (expected.size() + size - 1L) / size,
                page.path("totalPages").asLong());
        int from = Math.min(pageIndex * size, expected.size());
        int to = Math.min(from + size, expected.size());
        assertEquals(expected.subList(from, to), itemIds(page));
        assertNoSqlStateEvidence(response);
    }

    private List<Long> itemIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private List<Long> candidateIds(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("candidates").forEach(item ->
                ids.add(item.path("leadId").asLong()));
        return ids;
    }

    private List<Long> serviceVisibleIds(Actor actor, SupportedCity city, OperatingTeam team,
                                         Instant cutoff, String timezone) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            jdbc.execute("set local time zone '" + timezone + "'");
            return leadService.list(actor.user().getId(), city.getId(), team.getId(), null,
                    false, cutoff, 0, 20).items().stream()
                    .map(item -> item.id()).toList();
        });
    }

    private static boolean isCountSql(String sql) {
        String normalized = compactSql(sql).toLowerCase(Locale.ROOT);
        return normalized.contains("select count(")
                && normalized.contains(" from lead_routing_queue ");
    }

    private static boolean isSelectSql(String sql) {
        String normalized = compactSql(sql).toLowerCase(Locale.ROOT);
        return !isCountSql(sql) && normalized.contains(" from lead_routing_queue ");
    }

    private static String compactSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static void assertNoSqlStateEvidence(HttpResponse<String> response) {
        assertFalse(response.body().contains("42P18"));
        assertFalse(response.body().contains("could not determine data type"));
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        return send(method, path, token, body, null, null);
    }

    private HttpResponse<String> send(String method, String path, String token, String body,
                                      String header, String headerValue) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        request.header("Origin", "http://localhost:5173");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (header != null) request.header(header, headerValue);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> metaWebhook(String body) throws Exception {
        return send("POST", "/api/v1/webhooks/meta-leads", null, body,
                "X-Hub-Signature-256", metaSignature(body));
    }

    private SourceFixture sourceFixture(String label) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String externalId = label + "-" + suffix;
        String formId = "form-202";
        String value = "{\"leadgen_id\":\"" + externalId + "\",\"form_id\":\"" + formId
                + "\",\"field_data\":[{\"name\":\"full_name\",\"values\":[\"Synthetic source\"]}]}";
        String body = "{\"object\":\"page\",\"entry\":[{\"id\":\"page-101\","
                + "\"changes\":[{\"value\":" + value + ",\"field\":\"leadgen\"}]}]}";
        HttpResponse<String> response = metaWebhook(body);
        assertEquals(200, response.statusCode(), response.body());
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "select id::text from lead_source_events where provider_account_id = 'page-101' "
                        + "and external_event_id = ?", String.class, externalId));
        return new SourceFixture(eventId, "page-101", externalId, formId);
    }

    private static String metaSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "p4-crm-meta-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(
                mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record SourceFixture(UUID eventId, String accountId, String externalId, String formId) {}

    private static GrantSpec grantSpec(StaffCapability capability, StaffScopeType scopeType,
                                       SupportedCity city, OperatingTeam team) {
        return new GrantSpec(capability, scopeType, city, team, 3600);
    }

    private static GrantSpec expiringGrantSpec(StaffCapability capability, StaffScopeType scopeType,
                                               SupportedCity city, OperatingTeam team,
                                               long expiresInSeconds) {
        return new GrantSpec(capability, scopeType, city, team, expiresInSeconds);
    }

    private static ScopedLead scoped(LeadRoutingQueue lead) {
        return new ScopedLead(lead, lead.getSupportedCity(), lead.getOperatingTeam());
    }

    private record RealSqlFailure(Long actorId, int backendPid, String sqlState, String message) {}
    private record SqlError(String sqlState, String message) {}
    private record ServerReply(String statusLine, String response) {}
    private record LostHttpResult(int clientRead, String serverStatusLine, String serverResponse) {}
    private record GrantSpec(StaffCapability capability, StaffScopeType scopeType,
                             SupportedCity city, OperatingTeam team, long expiresInSeconds) {}
    private record TimedGrantSpec(StaffCapability capability, StaffScopeType scopeType,
                                  SupportedCity city, OperatingTeam team,
                                  Instant effectiveAt, Instant expiresAt) {}
    private record ScopedLead(LeadRoutingQueue entity, SupportedCity city, OperatingTeam team) {}
    private record HighScope(SupportedCity city, OperatingTeam team) {}
    private record Actor(User user, String token, List<StaffAccessGrant> grants) {}
    private record PreparedHandoff(LeadRoutingQueue lead, JsonNode preference,
                                   JsonNode shortlist, UUID shareId) {}
    private record ShareAdmissionHold(String function, String trigger) {}
    private record G09ShareCommitSnapshot(
            long leadVersion, long shortlistVersion, long shareCount, long shareActivityCount,
            long shareAuditCount, long shareReceiptCount, long shareEventCount,
            long notificationOutboxCount, String historicalShareSnapshot) {}
}
