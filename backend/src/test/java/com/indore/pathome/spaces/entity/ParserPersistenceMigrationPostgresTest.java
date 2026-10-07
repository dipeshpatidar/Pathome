package com.indore.pathome.spaces.entity;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.SessionFactory;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies V47 completes parser persistence on the supported baseline and preserves historical tables. */
@EnabledIfEnvironmentVariable(named = "PATHOME_PACKAGE5_FLYWAY_TEST", matches = "true")
class ParserPersistenceMigrationPostgresTest {
    @Test
    void v47CompletesCleanParserSchemaAndHibernateValidatesIt() throws Exception {
        String url = databaseUrl();
        String username = username();
        String password = password();
        String schema = "pathome_parser_clean_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));

                Flyway throughV46 = flyway(url, username, password, schema).target("46").load();
                assertTrue(throughV46.migrate().success);
                assertEquals(2, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='parser_training_examples'"),
                        "The supported V1 baseline plus V2 has only the parser ID and created_by columns before V47");
                assertEquals(0, count(connection, "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema=current_schema() AND table_name='parser_model_versions'"));

                Flyway latest = flyway(url, username, password, schema).target("48").load();
                var migrated = latest.migrate();
                assertTrue(migrated.success);
                assertEquals(2, migrated.migrationsExecuted);
                assertEquals("48", migrated.targetSchemaVersion.toString());
                assertDoesNotThrow(latest::validate);

                assertEquals(21, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='parser_training_examples'"));
                assertEquals(11, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='parser_model_versions'"));
                assertEquals(14, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='parser_field_reviews'"));
                assertEquals(4, count(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() "
                        + "AND indexname IN ('idx_parser_example_status_created','idx_parser_example_partition_status',"
                        + "'idx_parser_example_batch_property','idx_parser_example_hash_status')"));
                assertEquals(1, count(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() "
                        + "AND indexname='idx_parser_model_status_created'"));
                assertEquals(0, count(connection, "SELECT count(*) FROM parser_training_examples"));
                assertEquals(0, count(connection, "SELECT count(*) FROM parser_field_reviews"));
                assertEquals(0, count(connection, "SELECT count(*) FROM parser_model_versions"));

                validateParserEntities(url, username, password, schema);
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v47PreservesCompatibleHistoricalParserRowsAndIdentitySequences() throws Exception {
        String url = databaseUrl();
        String username = username();
        String password = password();
        String schema = "pathome_parser_history_" + UUID.randomUUID().toString().replace("-", "");
        String exampleId = UUID.randomUUID().toString();
        long originalReviewId;
        long originalModelId;

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                assertTrue(flyway(url, username, password, schema).target("45").load().migrate().success);
                prepareHistoricalParserTables(statement, exampleId);

                try (ResultSet row = statement.executeQuery("INSERT INTO parser_model_versions "
                        + "(model_version,artifact_path,artifact_checksum,dataset_fingerprint,metrics_json,"
                        + "holdout_example_count,decision_reason,model_status,created_at,activated_at) "
                        + "VALUES ('history-1','models/history-1','" + "a".repeat(64) + "','" + "b".repeat(64)
                        + "','{}',100,'historical fixture','CANDIDATE',CURRENT_TIMESTAMP,NULL) RETURNING id")) {
                    assertTrue(row.next());
                    originalModelId = row.getLong(1);
                }
                try (ResultSet row = statement.executeQuery("INSERT INTO parser_field_reviews "
                        + "(example_id,field_name,predicted_value,reviewed_value,source_text,source_start,source_end,"
                        + "was_corrected,evidence_supported,value_valid,training_eligible,exclusion_reason,created_at) "
                        + "VALUES ('" + exampleId + "','rentAmount','1000','1200','rent 1200',5,9,TRUE,TRUE,TRUE,"
                        + "TRUE,NULL,CURRENT_TIMESTAMP) RETURNING id")) {
                    assertTrue(row.next());
                    originalReviewId = row.getLong(1);
                }

                assertTrue(flyway(url, username, password, schema).target("46").load().migrate().success);
                Flyway latest = flyway(url, username, password, schema).target("48").load();
                var migrated = latest.migrate();
                assertTrue(migrated.success);
                assertEquals(2, migrated.migrationsExecuted);
                assertEquals("48", migrated.targetSchemaVersion.toString());
                assertDoesNotThrow(latest::validate);

                assertEquals(1, count(connection, "SELECT count(*) FROM parser_training_examples "
                        + "WHERE id='" + exampleId + "' AND batch_id='" + exampleId + "' AND property_index=1 "
                        + "AND input_source='TYPED' AND review_status='QUARANTINED' "
                        + "AND dataset_partition='EXCLUDED' AND raw_prompt='parser migration fixture prompt' "
                        + "AND prompt_hash='" + "c".repeat(64) + "' AND initial_prediction='{}' "
                        + "AND parser_version='fixture-parser' AND created_by='migration-fixture' "
                        + "AND label_count=1 AND eligible_label_count=0"));
                assertEquals(1, count(connection, "SELECT count(*) FROM parser_field_reviews WHERE id="
                        + originalReviewId + " AND example_id='" + exampleId + "' AND reviewed_value='1200'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM parser_model_versions WHERE id="
                        + originalModelId + " AND model_version='history-1' AND model_status='CANDIDATE'"));

                long nextModelId;
                try (ResultSet row = statement.executeQuery("INSERT INTO parser_model_versions "
                        + "(model_version,artifact_path,artifact_checksum,dataset_fingerprint,metrics_json,"
                        + "holdout_example_count,model_status,created_at) VALUES ('history-2','models/history-2','"
                        + "d".repeat(64) + "','" + "e".repeat(64) + "','{}',100,'CANDIDATE',CURRENT_TIMESTAMP) "
                        + "RETURNING id")) {
                    assertTrue(row.next());
                    nextModelId = row.getLong(1);
                }
                assertTrue(nextModelId > originalModelId, "model identity must continue beyond preserved IDs");

                long nextReviewId;
                try (ResultSet row = statement.executeQuery("INSERT INTO parser_field_reviews "
                        + "(example_id,field_name,reviewed_value,was_corrected,evidence_supported,value_valid,"
                        + "training_eligible,created_at) VALUES ('" + exampleId
                        + "','securityDeposit','1200',FALSE,TRUE,TRUE,FALSE,CURRENT_TIMESTAMP) RETURNING id")) {
                    assertTrue(row.next());
                    nextReviewId = row.getLong(1);
                }
                assertTrue(nextReviewId > originalReviewId, "review identity must continue beyond preserved IDs");

                validateParserEntities(url, username, password, schema);
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v47KeepsHighIdsAheadOfNormalAndLaggingAscendingIdentityGenerators() throws Exception {
        verifyV47IdentityGenerator("START WITH 900003", true);
        verifyV47IdentityGenerator("START WITH 1", true);
        verifyV47IdentityGenerator("SERIAL", true);
    }

    @Test
    void v47RejectsDescendingCyclingAndCachedIdentityGeneratorsWithoutChangingRows() throws Exception {
        verifyV47IdentityGenerator(
                "INCREMENT BY -1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 900003", false);
        verifyV47IdentityGenerator("START WITH 1 CYCLE", false);
        verifyV47IdentityGenerator("START WITH 1 CACHE 10", false);
        verifyV47IdentityGenerator("OWNED_WITHOUT_DEFAULT", false);
        verifyV47IdentityGenerator("WRONG_SEQUENCE_DEFAULT", false);
    }

    private static void verifyV47IdentityGenerator(String identityOptions, boolean shouldMigrate) throws Exception {
        String url = databaseUrl();
        String username = username();
        String password = password();
        String schema = "pathome_parser_model_generator_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                assertTrue(flyway(url, username, password, schema).target("46").load().migrate().success);
                String idDefinition = identityOptions.equals("SERIAL")
                        ? "id BIGSERIAL,"
                        : identityOptions.equals("OWNED_WITHOUT_DEFAULT") || identityOptions.equals("WRONG_SEQUENCE_DEFAULT")
                            ? "id BIGINT,"
                            : "id BIGINT GENERATED BY DEFAULT AS IDENTITY (" + identityOptions + "),";
                statement.execute("CREATE TABLE parser_model_versions ("
                        + idDefinition
                        + "model_version VARCHAR(100) NOT NULL UNIQUE,artifact_path VARCHAR(500) NOT NULL,"
                        + "artifact_checksum VARCHAR(64) NOT NULL,dataset_fingerprint VARCHAR(64) NOT NULL,"
                        + "metrics_json TEXT NOT NULL,holdout_example_count INTEGER NOT NULL,decision_reason VARCHAR(500),"
                        + "model_status VARCHAR(16) NOT NULL,created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,"
                        + "activated_at TIMESTAMP WITHOUT TIME ZONE,CONSTRAINT parser_model_versions_pkey PRIMARY KEY(id))");
                if (identityOptions.equals("OWNED_WITHOUT_DEFAULT") || identityOptions.equals("WRONG_SEQUENCE_DEFAULT")) {
                    statement.execute("CREATE SEQUENCE parser_model_versions_owned_seq START WITH 900003");
                    statement.execute("ALTER SEQUENCE parser_model_versions_owned_seq OWNED BY parser_model_versions.id");
                    if (identityOptions.equals("WRONG_SEQUENCE_DEFAULT")) {
                        statement.execute("CREATE SEQUENCE parser_model_versions_wrong_seq START WITH 900003");
                        statement.execute("ALTER TABLE parser_model_versions ALTER COLUMN id SET DEFAULT "
                                + "nextval('parser_model_versions_wrong_seq'::regclass)");
                    }
                }
                statement.execute("INSERT INTO parser_model_versions(id,model_version,artifact_path,artifact_checksum,"
                        + "dataset_fingerprint,metrics_json,holdout_example_count,model_status,created_at) VALUES "
                        + "(900001,'historical-one','a','a','b','{}',1,'CANDIDATE',CURRENT_TIMESTAMP),"
                        + "(900002,'historical-two','a','a','b','{}',1,'CANDIDATE',CURRENT_TIMESTAMP)");
                if (identityOptions.startsWith("INCREMENT BY -1")) {
                    assertEquals(900003L, scalarLong(connection,
                            "SELECT nextval(pg_get_serial_sequence('parser_model_versions','id'))"));
                }

                Flyway v47 = flyway(url, username, password, schema).target("47").load();
                if (!shouldMigrate) {
                    RuntimeException failure = assertThrows(RuntimeException.class, v47::migrate,
                            "unsafe historical identity metadata must fail V47");
                    String expectedFailure = identityOptions.equals("OWNED_WITHOUT_DEFAULT")
                            || identityOptions.equals("WRONG_SEQUENCE_DEFAULT")
                            ? "has an incompatible sequence default"
                            : "requires a noncycling ascending generator with cache 1";
                    assertTrue(rootMessage(failure).contains(expectedFailure),
                            "V47 should identify the unsafe generator metadata");
                    assertEquals(2, count(connection,
                            "SELECT count(*) FROM parser_model_versions WHERE id IN (900001,900002)"),
                            "a rejected migration must preserve historical model IDs");
                    return;
                }

                assertTrue(v47.migrate().success);
                assertEquals(2, count(connection,
                        "SELECT count(*) FROM parser_model_versions WHERE id IN (900001,900002)"),
                        "migration must preserve historical model IDs");
                long nextId;
                try (ResultSet row = statement.executeQuery("INSERT INTO parser_model_versions "
                        + "(model_version,artifact_path,artifact_checksum,dataset_fingerprint,metrics_json,"
                        + "holdout_example_count,model_status,created_at) VALUES ('generated','a','a','b','{}',1,"
                        + "'CANDIDATE',CURRENT_TIMESTAMP) RETURNING id")) {
                    assertTrue(row.next());
                    nextId = row.getLong(1);
                }
                assertTrue(nextId > scalarLong(connection, "SELECT max(id) FROM parser_model_versions "
                        + "WHERE model_version <> 'generated'"),
                        "every accepted generator must produce an ID above all preserved IDs");
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private static void prepareHistoricalParserTables(Statement statement, String exampleId) throws Exception {
        statement.execute("ALTER TABLE parser_training_examples ALTER COLUMN id DROP DEFAULT");
        statement.execute("ALTER SEQUENCE parser_training_examples_id_seq OWNED BY NONE");
        statement.execute("DROP SEQUENCE parser_training_examples_id_seq");
        statement.execute("ALTER TABLE parser_training_examples ALTER COLUMN id TYPE VARCHAR(36) USING id::TEXT");
        statement.execute("ALTER TABLE parser_training_examples "
                + "ADD COLUMN batch_id VARCHAR(36) NOT NULL, ADD COLUMN property_index INTEGER NOT NULL, "
                + "ADD COLUMN input_source VARCHAR(16) NOT NULL, ADD COLUMN review_status VARCHAR(40) NOT NULL, "
                + "ADD COLUMN dataset_partition VARCHAR(16) NOT NULL, ADD COLUMN raw_prompt TEXT NOT NULL, "
                + "ADD COLUMN prompt_hash VARCHAR(64) NOT NULL, ADD COLUMN initial_prediction TEXT NOT NULL, "
                + "ADD COLUMN final_reviewed_values TEXT, ADD COLUMN parser_version VARCHAR(80) NOT NULL, "
                + "ADD COLUMN published_listing_id BIGINT, ADD COLUMN reviewed_by VARCHAR(254), "
                + "ADD COLUMN exclusion_reason VARCHAR(500), ADD COLUMN label_count INTEGER NOT NULL, "
                + "ADD COLUMN eligible_label_count INTEGER NOT NULL, "
                + "ADD COLUMN created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                + "ADD COLUMN updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                + "ADD COLUMN published_at TIMESTAMP WITHOUT TIME ZONE, "
                + "ADD COLUMN curated_at TIMESTAMP WITHOUT TIME ZONE");
        statement.execute("INSERT INTO parser_training_examples "
                + "(id,batch_id,property_index,input_source,review_status,dataset_partition,raw_prompt,prompt_hash,"
                + "initial_prediction,parser_version,created_by,label_count,eligible_label_count,created_at,updated_at) "
                + "VALUES ('" + exampleId + "','" + exampleId + "',1,'TYPED','QUARANTINED','EXCLUDED',"
                + "'parser migration fixture prompt','" + "c".repeat(64) + "','{}','fixture-parser',"
                + "'migration-fixture',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        statement.execute("CREATE INDEX idx_parser_example_status_created ON parser_training_examples(review_status,created_at)");
        statement.execute("CREATE INDEX idx_parser_example_partition_status ON parser_training_examples(dataset_partition,review_status)");
        statement.execute("CREATE INDEX idx_parser_example_batch_property ON parser_training_examples(batch_id,property_index)");
        statement.execute("CREATE INDEX idx_parser_example_hash_status ON parser_training_examples(prompt_hash,review_status)");

        statement.execute("CREATE TABLE parser_model_versions ("
                + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, model_version VARCHAR(100) NOT NULL UNIQUE, "
                + "artifact_path VARCHAR(500) NOT NULL, artifact_checksum VARCHAR(64) NOT NULL, "
                + "dataset_fingerprint VARCHAR(64) NOT NULL, metrics_json TEXT NOT NULL, "
                + "holdout_example_count INTEGER NOT NULL, decision_reason VARCHAR(500), "
                + "model_status VARCHAR(16) NOT NULL, created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                + "activated_at TIMESTAMP WITHOUT TIME ZONE)");
        statement.execute("CREATE INDEX idx_parser_model_status_created ON parser_model_versions(model_status,created_at)");

        statement.execute("CREATE TABLE parser_field_reviews ("
                + "id BIGINT GENERATED BY DEFAULT AS IDENTITY, example_id VARCHAR(36) NOT NULL, "
                + "field_name VARCHAR(64) NOT NULL, predicted_value TEXT, reviewed_value TEXT NOT NULL, "
                + "source_text TEXT, source_start INTEGER, source_end INTEGER, was_corrected BOOLEAN NOT NULL, "
                + "evidence_supported BOOLEAN NOT NULL, value_valid BOOLEAN NOT NULL, training_eligible BOOLEAN NOT NULL, "
                + "exclusion_reason VARCHAR(500), created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                + "CONSTRAINT parser_field_reviews_pkey PRIMARY KEY (id), "
                + "CONSTRAINT fk_parser_field_review_example FOREIGN KEY (example_id) "
                + "REFERENCES parser_training_examples(id), "
                + "CONSTRAINT uk_parser_field_example_name UNIQUE (example_id,field_name))");
        statement.execute("CREATE INDEX idx_parser_field_example_eligible ON parser_field_reviews(example_id,training_eligible)");
        statement.execute("CREATE INDEX idx_parser_field_name_eligible ON parser_field_reviews(field_name,training_eligible)");
    }

    private static void validateParserEntities(String url, String username, String password, String schema) {
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
                .build();
        try (SessionFactory ignored = new MetadataSources(registry)
                .addAnnotatedClass(ParserTrainingExample.class)
                .addAnnotatedClass(ParserFieldReview.class)
                .addAnnotatedClass(ParserModelVersion.class)
                .buildMetadata()
                .buildSessionFactory()) {
            assertNotNull(ignored);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static FluentConfiguration flyway(String url, String username, String password, String schema) {
        return Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1");
    }

    private static String databaseUrl() {
        return System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    }

    private static String username() {
        return System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    }

    private static String password() {
        return System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
