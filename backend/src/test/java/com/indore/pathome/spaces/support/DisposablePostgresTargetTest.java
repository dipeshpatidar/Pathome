package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.indore.pathome.spaces.support.DisposablePostgresTarget.APPROVED_R1_DATABASE;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.APPROVED_PORT;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DATASOURCE_URL_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DATASOURCE_USERNAME_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DESIGNATION_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DISPOSABLE_OPT_IN_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.HOST_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.PORT_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_DATASOURCE_MISMATCH;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_ENDPOINT;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_NAME;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.TEST_ENABLEMENT_FLAG;
import static com.indore.pathome.spaces.support.DisposablePostgresTarget.REFUSED_LIVE_SENTINEL;
import static com.indore.pathome.spaces.support.DisposablePostgresTarget.REFUSED_LIVE_SERVER;
import static com.indore.pathome.spaces.support.DisposablePostgresTarget.REFUSED_LIVE_USER;
import static com.indore.pathome.spaces.support.DisposablePostgresTarget.REFUSED_RUN_IDENTITY;
import static com.indore.pathome.spaces.support.DisposablePostgresTarget.RUN_ID_ENV;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposablePostgresTargetTest {
    private static final String RUN_ID = "run-20261018-r1-abcdef";
    private static final DisposablePostgresTarget.LiveProbe NO_CONNECTION = target -> {
        throw new AssertionError("a refused configuration must not open a connection");
    };

    @Test
    void refusesEveryDangerousConfigurationBeforeAnyConnection() {
        for (Map.Entry<String, Integer> refusal : Map.of(
                "jdbc:postgresql://localhost:5432/pathome_db", REFUSED_NAME,
                "jdbc:postgresql://primary:5432," + APPROVED_R1_DATABASE, REFUSED_NAME,
                "jdbc:postgresql://user:secret@localhost:5432/" + APPROVED_R1_DATABASE, REFUSED_NAME,
                "jdbc:postgresql://db.production.example.com:5432/" + APPROVED_R1_DATABASE, REFUSED_ENDPOINT,
                "jdbc:postgresql://localhost:5432/" + APPROVED_R1_DATABASE + "?currentSchema=pathome_db",
                REFUSED_DATASOURCE_MISMATCH
        ).entrySet()) {
            Map<String, String> environment = validEnvironment();
            environment.put(DATASOURCE_URL_FLAG, refusal.getKey());
            assertRefused(refusal.getValue(), environment, NO_CONNECTION);
            var denied = assertThrows(DisposableIntegrationTarget.RefusedException.class,
                    () -> DisposablePostgresTarget.verify(environment, NO_CONNECTION));
            assertTrue(!denied.getMessage().contains("secret@"), "JDBC diagnostics must redact credentials");
        }
    }

    @Test
    void refusesWithoutAnExplicitOrApprovedRunIdentity() {
        Map<String, String> environment = validEnvironment();
        environment.remove(RUN_ID_ENV);
        assertRefused(REFUSED_RUN_IDENTITY, environment, NO_CONNECTION);
        environment.put(RUN_ID_ENV, "short");
        assertRefused(REFUSED_RUN_IDENTITY, environment, NO_CONNECTION);
        environment.put(RUN_ID_ENV, "20261013T124820-98553");
        assertRefused(REFUSED_RUN_IDENTITY, environment, NO_CONNECTION);
    }

    @Test
    void refusesUnexpectedLiveUserServerMarkerOrRun() {
        assertRefused(REFUSED_LIVE_USER, validEnvironment(), target -> facts(APPROVED_R1_DATABASE,
                "someone_else", "127.0.0.1", 1, RUN_ID));
        assertRefused(REFUSED_LIVE_SERVER, validEnvironment(), target -> facts(APPROVED_R1_DATABASE,
                "pathome", "10.0.0.8", 1, RUN_ID));
        assertRefused(REFUSED_LIVE_SERVER, validEnvironment(), target -> facts(APPROVED_R1_DATABASE,
                "pathome", null, 1, RUN_ID));
        assertRefused(REFUSED_LIVE_SENTINEL, validEnvironment(), target -> facts(APPROVED_R1_DATABASE,
                "pathome", "127.0.0.1", 0, RUN_ID));
        assertRefused(REFUSED_LIVE_SENTINEL, validEnvironment(), target -> facts(APPROVED_R1_DATABASE,
                "pathome", "127.0.0.1", 1, "another-run-20261018"));
    }

    @Test
    void acceptsOnlyTheFullyVerifiedLoopbackTarget() {
        DisposablePostgresTarget.Verified verified = DisposablePostgresTarget.verify(validEnvironment(),
                target -> facts(APPROVED_R1_DATABASE, "pathome", "127.0.0.1", 1, RUN_ID));
        assertEquals(APPROVED_R1_DATABASE, verified.target().database());
        assertEquals(RUN_ID, verified.runId());
        assertEquals("jdbc:postgresql://localhost:" + APPROVED_PORT + "/" + APPROVED_R1_DATABASE,
                verified.target().jdbcUrl());
    }

    @Test
    void exactR1ApprovalDoesNotAcceptLegacyOrLookalikeDesignations() {
        for (String database : new String[] { DisposableIntegrationTarget.APPROVED_DATABASE,
                "pathome_oe_test_phase2a_20261015", "pathome_r1_test_20261019",
                "pathome_r1_test_20261018_clone", "pathome_db" }) {
            Map<String, String> environment = validEnvironment();
            environment.put(DESIGNATION_FLAG, database);
            environment.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/" + database);
            assertRefused(REFUSED_NAME, environment, NO_CONNECTION);
        }
        assertThrows(DisposableIntegrationTarget.RefusedException.class,
                () -> DisposableIntegrationTarget.requireApprovedStaticTarget(validEnvironment()));
    }

    @Test
    void rejectsBlankEncodedMultipleHostAndSearchPathOverridesBeforeConnection() {
        for (String url : new String[] { "", " ",
                "jdbc:postgresql://localhost:5432/pathome%5fr1_test_20261018",
                "jdbc:postgresql://localhost:5432,127.0.0.1:5432/" + APPROVED_R1_DATABASE,
                "jdbc:postgresql://localhost:5432/" + APPROVED_R1_DATABASE + "?options=-c%20search_path=public",
                "jdbc:postgresql://localhost:5432/" + APPROVED_R1_DATABASE + "#override" }) {
            Map<String, String> environment = validEnvironment();
            environment.put(DATASOURCE_URL_FLAG, url);
            assertThrows(DisposableIntegrationTarget.RefusedException.class,
                    () -> DisposablePostgresTarget.verify(environment, NO_CONNECTION));
        }
        assertRefused(DisposableIntegrationTarget.REFUSED_LIVE_IDENTITY, validEnvironment(),
                target -> facts("wrong_database", "pathome", "127.0.0.1", 1, RUN_ID));
        assertRefused(REFUSED_LIVE_SENTINEL, validEnvironment(),
                target -> facts(APPROVED_R1_DATABASE, "pathome", "127.0.0.1", 2, RUN_ID));
    }

    private static DisposablePostgresTarget.LiveFacts facts(String database, String user, String server,
                                                             int markers, String runId) {
        return new DisposablePostgresTarget.LiveFacts(database, user, server, markers, runId);
    }

    private static void assertRefused(int code, Map<String, String> environment,
                                      DisposablePostgresTarget.LiveProbe probe) {
        DisposableIntegrationTarget.RefusedException refusal = assertThrows(
                DisposableIntegrationTarget.RefusedException.class,
                () -> DisposablePostgresTarget.verify(environment, probe));
        assertEquals(code, refusal.code(), refusal.getMessage());
        assertTrue(refusal.getMessage().startsWith("REFUSED [" + code + "]"));
    }

    private static Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(TEST_ENABLEMENT_FLAG, "true");
        environment.put(DISPOSABLE_OPT_IN_FLAG, "true");
        environment.put(DESIGNATION_FLAG, APPROVED_R1_DATABASE);
        environment.put(HOST_FLAG, "localhost");
        environment.put(PORT_FLAG, APPROVED_PORT);
        environment.put(DATASOURCE_URL_FLAG,
                "jdbc:postgresql://localhost:" + APPROVED_PORT + "/" + APPROVED_R1_DATABASE);
        environment.put(DATASOURCE_USERNAME_FLAG, "pathome");
        environment.put(RUN_ID_ENV, RUN_ID);
        return environment;
    }
}
