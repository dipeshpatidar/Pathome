package com.indore.pathome.spaces.support;

import com.indore.pathome.spaces.support.DisposableIntegrationTarget.LiveFacts;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget.LiveProbe;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget.RefusedException;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget.Target;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.APPROVED_DATABASE;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.APPROVED_PORT;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DATASOURCE_URL_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DATASOURCE_USERNAME_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DESIGNATION_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DISPOSABLE_OPT_IN_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.HOST_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.PORT_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_AMBIGUOUS_NAME;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_CONNECTION_FAILED;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_DATASOURCE_MISMATCH;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_ENDPOINT;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_LIVE_IDENTITY;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_MISSING_MARKER;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_NAME;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_NO_DESIGNATION;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_NOT_OPTED_IN;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.REFUSED_TEST_NOT_ENABLED;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.TEST_ENABLEMENT_FLAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Package 3A database safety policy, proven fail-closed before any connection is opened.
 *
 * <p>These cases are the contract for a direct {@code mvn} invocation with no wrapper: a missing
 * enablement flag, a missing designation, {@code pathome_db}, a foreign host, a foreign database, a
 * malformed or overridden JDBC URL, a target that resolves elsewhere and a database without the live
 * disposable marker must all refuse. Every static case asserts that no live probe ran, so a refusal can
 * never have touched a database - protected or otherwise.
 */
class DisposableIntegrationTargetGuardTest {

    /** Proves the guard refused without opening any connection. */
    private static final LiveProbe NO_CONNECTION_ALLOWED = target -> {
        throw new AssertionError("a refused configuration must not open a connection to " + target.jdbcUrl());
    };

    @Test
    void directMavenInvocationWithoutTheWrapperIsRefused() {
        // Exactly what a bare "mvn -Dtest=... test" leaves behind: the runner flag and a datasource
        // pointing at the preserved development database, with no disposable designation at all.
        Map<String, String> environment = new HashMap<>();
        environment.put(TEST_ENABLEMENT_FLAG, "true");
        environment.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/pathome_db");
        assertRefused(REFUSED_NOT_OPTED_IN, environment, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesWhenTheTestRunnerIsNotExplicitlyEnabled() {
        Map<String, String> environment = validEnvironment();
        environment.remove(TEST_ENABLEMENT_FLAG);
        assertRefused(REFUSED_TEST_NOT_ENABLED, environment, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesWhenTheDisposableOptInIsMissing() {
        Map<String, String> environment = validEnvironment();
        environment.remove(DISPOSABLE_OPT_IN_FLAG);
        assertRefused(REFUSED_NOT_OPTED_IN, environment, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesWhenNothingIsDesignated() {
        Map<String, String> environment = validEnvironment();
        environment.remove(DESIGNATION_FLAG);
        assertRefused(REFUSED_NO_DESIGNATION, environment, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesWhenTheDatasourceIsMissingOrUnnamed() {
        Map<String, String> environment = validEnvironment();
        environment.remove(DATASOURCE_URL_FLAG);
        assertRefused(REFUSED_NO_DESIGNATION, environment, NO_CONNECTION_ALLOWED);

        Map<String, String> unnamed = validEnvironment();
        unnamed.remove(DATASOURCE_USERNAME_FLAG);
        assertRefused(REFUSED_NO_DESIGNATION, unnamed, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesThePreservedDevelopmentDatabase() {
        Map<String, String> designated = validEnvironment();
        designated.put(DESIGNATION_FLAG, "pathome_db");
        designated.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/pathome_db");
        assertRefused(REFUSED_NAME, designated, NO_CONNECTION_ALLOWED);

        // The designation is correct but the datasource still points at the preserved database.
        Map<String, String> datasource = validEnvironment();
        datasource.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/pathome_db");
        assertRefused(REFUSED_NAME, datasource, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesForeignHostsAndUndeclaredOrWrongPorts() {
        Map<String, String> foreign = validEnvironment();
        foreign.put(HOST_FLAG, "db.production.example.com");
        assertRefused(REFUSED_ENDPOINT, foreign, NO_CONNECTION_ALLOWED);

        Map<String, String> foreignDatasource = validEnvironment();
        foreignDatasource.put(DATASOURCE_URL_FLAG,
                "jdbc:postgresql://db.production.example.com:5432/" + APPROVED_DATABASE);
        assertRefused(REFUSED_ENDPOINT, foreignDatasource, NO_CONNECTION_ALLOWED);

        Map<String, String> missingPort = validEnvironment();
        missingPort.remove(PORT_FLAG);
        assertRefused(REFUSED_ENDPOINT, missingPort, NO_CONNECTION_ALLOWED);

        Map<String, String> wrongPort = validEnvironment();
        wrongPort.put(PORT_FLAG, "5433");
        assertRefused(REFUSED_ENDPOINT, wrongPort, NO_CONNECTION_ALLOWED);

        Map<String, String> wrongUrlPort = validEnvironment();
        wrongUrlPort.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5433/" + APPROVED_DATABASE);
        assertRefused(REFUSED_ENDPOINT, wrongUrlPort, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesUnapprovedAndAmbiguousDatabaseNames() {
        Map<String, String> unapproved = validEnvironment();
        unapproved.put(DESIGNATION_FLAG, "pathome_oe_test_other");
        unapproved.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/pathome_oe_test_other");
        assertRefused(REFUSED_NAME, unapproved, NO_CONNECTION_ALLOWED);

        Map<String, String> ambiguous = validEnvironment();
        ambiguous.put(DESIGNATION_FLAG, "pathome_oe_test_staging");
        ambiguous.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/pathome_oe_test_staging");
        assertRefused(REFUSED_AMBIGUOUS_NAME, ambiguous, NO_CONNECTION_ALLOWED);

        Map<String, String> unrelated = validEnvironment();
        unrelated.put(DESIGNATION_FLAG, "divyavastu_db");
        unrelated.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/divyavastu_db");
        assertRefused(REFUSED_NAME, unrelated, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesMalformedAndOverriddenJdbcUrls() {
        Map<String, String> malformed = validEnvironment();
        malformed.put(DATASOURCE_URL_FLAG, "jdbc:postgresql:" + APPROVED_DATABASE);
        assertRefused(REFUSED_NAME, malformed, NO_CONNECTION_ALLOWED);

        Map<String, String> noPort = validEnvironment();
        noPort.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost/" + APPROVED_DATABASE);
        assertRefused(REFUSED_NAME, noPort, NO_CONNECTION_ALLOWED);

        Map<String, String> overridden = validEnvironment();
        overridden.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:5432/" + APPROVED_DATABASE
                + "?databaseName=pathome_db");
        assertRefused(REFUSED_DATASOURCE_MISMATCH, overridden, NO_CONNECTION_ALLOWED);
    }

    @Test
    void refusesWhenTheLiveConnectionIsNotTheDesignatedDatabase() {
        Map<String, String> environment = validEnvironment();
        assertRefused(REFUSED_LIVE_IDENTITY, environment, target -> new LiveFacts("pathome_db", 1));
    }

    @Test
    void refusesWhenTheLiveDisposableMarkerIsMissingOrAmbiguous() {
        assertRefused(REFUSED_MISSING_MARKER, validEnvironment(),
                target -> new LiveFacts(APPROVED_DATABASE, 0));
        assertRefused(REFUSED_MISSING_MARKER, validEnvironment(),
                target -> new LiveFacts(APPROVED_DATABASE, 2));
    }

    @Test
    void refusesWhenTheLiveVerificationConnectionFails() {
        assertRefused(REFUSED_CONNECTION_FAILED, validEnvironment(), target -> {
            throw new SQLException("connection refused");
        });
    }

    @Test
    void acceptsTheApprovedTargetOnceEveryStaticRuleAndBothLiveFactsHold() {
        Target target = DisposableIntegrationTarget.verify(validEnvironment(),
                facts -> new LiveFacts(APPROVED_DATABASE, 1));

        assertEquals("jdbc:postgresql://localhost:" + APPROVED_PORT + "/" + APPROVED_DATABASE, target.jdbcUrl());
        assertEquals(APPROVED_DATABASE, target.database());
        assertEquals(APPROVED_PORT, target.port());
        assertEquals("pathome", target.username());
        assertEquals(target.jdbcUrl() + "?currentSchema=p3a_team_http_test_abc",
                target.schemaUrl("p3a_team_http_test_abc"));
    }

    @Test
    void acceptableExplicitVariationsOfTheApprovedTargetAreAllowed() {
        Map<String, String> environment = validEnvironment();
        environment.put(HOST_FLAG, "127.0.0.1");
        environment.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://127.0.0.1:" + APPROVED_PORT + "/"
                + APPROVED_DATABASE);
        Target target = DisposableIntegrationTarget.verify(environment,
                facts -> new LiveFacts(APPROVED_DATABASE, 1));
        assertEquals("127.0.0.1", target.host());

        Map<String, String> ipv6 = validEnvironment();
        ipv6.put(HOST_FLAG, "[::1]");
        ipv6.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://[::1]:" + APPROVED_PORT + "/" + APPROVED_DATABASE);
        assertEquals("[::1]", DisposableIntegrationTarget.verify(ipv6,
                facts -> new LiveFacts(APPROVED_DATABASE, 1)).host());

        // The bare IPv6 alias is not addressable by the canonical JDBC URL form, so it is refused rather
        // than accepted and later defaulted somewhere else.
        Map<String, String> bareIpv6 = validEnvironment();
        bareIpv6.put(HOST_FLAG, "::1");
        assertRefused(REFUSED_ENDPOINT, bareIpv6, NO_CONNECTION_ALLOWED);
    }

    private static void assertRefused(int expectedCode, Map<String, String> environment, LiveProbe probe) {
        RefusedException refusal = assertThrows(RefusedException.class,
                () -> DisposableIntegrationTarget.verify(environment, probe));
        assertEquals(expectedCode, refusal.code(), refusal.getMessage());
        assertTrue(refusal.getMessage().startsWith("REFUSED [" + expectedCode + "]"), refusal.getMessage());
    }

    private static Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(TEST_ENABLEMENT_FLAG, "true");
        environment.put(DISPOSABLE_OPT_IN_FLAG, "true");
        environment.put(DESIGNATION_FLAG, APPROVED_DATABASE);
        environment.put(HOST_FLAG, "localhost");
        environment.put(PORT_FLAG, APPROVED_PORT);
        environment.put(DATASOURCE_URL_FLAG, "jdbc:postgresql://localhost:" + APPROVED_PORT + "/"
                + APPROVED_DATABASE);
        environment.put(DATASOURCE_USERNAME_FLAG, "pathome");
        return environment;
    }
}
