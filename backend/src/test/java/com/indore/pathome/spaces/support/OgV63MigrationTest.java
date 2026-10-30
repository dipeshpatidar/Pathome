package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "PATHOME_OG_V63_MIGRATE", matches = "true")
class OgV63MigrationTest {
    @Test void frozenMigrationWithTransactionalControls() throws Exception { OgV63Migration.run(); }
}
