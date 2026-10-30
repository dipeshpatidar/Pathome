package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** One-shot migration launcher for the isolated DB-A01 Lead database. */
@EnabledIfEnvironmentVariable(named = DbA01LeadTestTarget.TEST_FLAG, matches = "true")
class DbA01LeadMigrationPostgresTest {
    @Test
    void migrateChildDatabaseFromPreservedV60ThroughUnchangedV63() throws Exception {
        DbA01LeadTestTarget.Target target = DbA01LeadTestTarget.requireFromEnvironment();
        DbA01LeadSchemaHarness.migrate(target);
        DbA01LeadTestTarget.verifyLiveDatabaseIdentity(target);
    }
}
