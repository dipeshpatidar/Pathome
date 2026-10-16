package com.indore.pathome.spaces.service;

/** No test fallback to a developer or quarantined database, even outside the guarded launcher. */
final class DisposableSchedulingTestDatabase {
    private DisposableSchedulingTestDatabase() {}
    static String url() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        String designated = System.getenv("PATHOME_OE_TEST_DB");
        if (!"true".equals(System.getenv("PATHOME_VISIT_OPERATIONS_POSTGRES_TEST")))
            return "jdbc:postgresql://127.0.0.1:1/disabled";
        if (!"pathome_oe_test_phase2a_20261015".equals(designated)
                || !("jdbc:postgresql://localhost:5432/" + designated).equals(url))
            throw new IllegalStateException("Use the positively guarded disposable scheduling-test launcher");
        return url;
    }
}
