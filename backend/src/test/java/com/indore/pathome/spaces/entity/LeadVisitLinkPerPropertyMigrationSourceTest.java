package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadVisitLinkPerPropertyMigrationSourceTest {
    @Test
    void additiveSourceBackfillsBeforeReplacingLeadWideUniqueness() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V62__lead_visit_link_per_property.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int addColumn = sql.indexOf("ADD COLUMN listing_id");
        int backfill = sql.indexOf("UPDATE lead_visit_links");
        int notNull = sql.indexOf("ALTER COLUMN listing_id SET NOT NULL");
        int dropOld = sql.indexOf("DROP CONSTRAINT uk_lead_visit_link_lead");
        int newUnique = sql.indexOf(
                "UNIQUE (lead_id, user_id, listing_id)");

        assertTrue(addColumn >= 0);
        assertTrue(backfill > addColumn);
        assertTrue(notNull > backfill);
        assertTrue(dropOld > notNull);
        assertTrue(newUnique > dropOld);
        assertTrue(sql.contains("REFERENCES listings(id)"));
        assertTrue(!sql.contains("DELETE FROM lead_visit_links"));
        assertTrue(!sql.contains("V61__"));
    }
}
