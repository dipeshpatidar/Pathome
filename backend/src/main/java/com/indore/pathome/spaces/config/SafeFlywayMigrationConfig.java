package com.indore.pathome.spaces.config;

import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Configuration
public class SafeFlywayMigrationConfig {

    private static final List<String> LEGACY_SCHEMA_MARKERS = List.of(
            "listings", "rental_details", "property_media_assets");

    @Bean
    FlywayMigrationStrategy safeFlywayMigrationStrategy(
            DataSource dataSource,
            @Value("${pathome.database.allow-flyway-baseline:false}") boolean allowBaseline) {
        return flyway -> {
            MigrationInfo current = flyway.info().current();
            if (current == null && allowBaseline) {
                verifyExpectedLegacySchema(dataSource);
                flyway.baseline();
            }
            flyway.migrate();
        };
    }

    private void verifyExpectedLegacySchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!"PostgreSQL".equalsIgnoreCase(metadata.getDatabaseProductName())) {
                throw new IllegalStateException("Legacy schema baselining is restricted to PostgreSQL");
            }
            String schema = connection.getSchema();
            for (String table : LEGACY_SCHEMA_MARKERS) {
                try (ResultSet tables = metadata.getTables(null, schema, table, new String[]{"TABLE"})) {
                    if (!tables.next()) {
                        throw new IllegalStateException(
                                "Legacy schema baselining refused because expected tables are missing");
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Legacy schema could not be verified before baselining", exception);
        }
    }
}
