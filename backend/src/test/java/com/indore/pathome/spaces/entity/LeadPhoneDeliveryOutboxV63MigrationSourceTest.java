package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadPhoneDeliveryOutboxV63MigrationSourceTest {
    private static final String EXPECTED_SQL = """
            ALTER TABLE lead_phone_delivery_outbox
            DROP CONSTRAINT ck_lead_phone_outbox_state,
            ADD CONSTRAINT ck_lead_phone_outbox_state
            CHECK (state IN (
            'QUEUED', 'DISPATCHING', 'ACCEPTED', 'DELIVERED',
            'FAILED', 'UNKNOWN', 'SUPPRESSED'
            ));
            """;
    private static final Pattern OUTBOX_STATE_CHECK = Pattern.compile(
            "CONSTRAINT\\s+ck_lead_phone_outbox_state\\s+CHECK\\s*"
                    + "\\(state\\s+IN\\s*\\((.*?)\\)\\)",
            Pattern.DOTALL);
    private static final Pattern QUOTED_STATE = Pattern.compile("'([^']+)'");

    @Test
    void v63WidensOnlyTheNamedOutboxStateConstraintAfterV62() throws Exception {
        String sql = resource("db/migration/V63__lead_phone_outbox_delivered_state.sql");
        String v61 = resource(
                "db/migration/V61__lead_phone_availability_and_visit_conversion.sql");
        String v62 = resource("db/migration/V62__lead_visit_link_per_property.sql");

        assertEquals(normalize(EXPECTED_SQL), normalize(sql));
        assertTrue(sql.startsWith("ALTER TABLE lead_phone_delivery_outbox"));
        assertEquals(1, occurrences(sql, "DROP CONSTRAINT ck_lead_phone_outbox_state"));
        assertEquals(1, occurrences(sql, "ADD CONSTRAINT ck_lead_phone_outbox_state"));
        assertTrue(sql.indexOf("DROP CONSTRAINT")
                < sql.indexOf("ADD CONSTRAINT"));
        assertEquals(List.of("QUEUED", "DISPATCHING", "ACCEPTED", "DELIVERED",
                        "FAILED", "UNKNOWN", "SUPPRESSED"),
                checkedStates(sql));
        assertFalse(sql.contains("CASCADE"));
        assertFalse(sql.contains("NOT VALID"));
        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("INSERT "));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("ALTER TABLE lead_phone_challenges"));
        assertFalse(sql.contains("ALTER COLUMN"));
        assertEquals(1, occurrences(sql, "ALTER TABLE "));
        assertEquals(sha256(v61),
                "b1f13eb476d5dccd7671acf16983bb607d162a2f343610612e4f6ec014bf016f");
        assertEquals(sha256(v62),
                "cd64c50a592dc7f82abdee746b805d15c27ce148c860bdccab70293cafc36a45");
    }

    @Test
    void v61AndV62DoNotAlreadyContainTheV63Replacement() throws Exception {
        String v61 = resource(
                "db/migration/V61__lead_phone_availability_and_visit_conversion.sql");
        String v62 = resource("db/migration/V62__lead_visit_link_per_property.sql");

        assertEquals(List.of("QUEUED", "DISPATCHING", "ACCEPTED", "FAILED",
                        "UNKNOWN", "SUPPRESSED"),
                checkedStates(v61));
        assertFalse(v62.contains("ck_lead_phone_outbox_state"));
        assertFalse(v62.contains("lead_phone_delivery_outbox"));
    }

    private static List<String> checkedStates(String sql) {
        Matcher matcher = OUTBOX_STATE_CHECK.matcher(sql);
        assertTrue(matcher.find(), "Expected the canonical outbox state constraint");
        String stateList = matcher.group(1);
        assertFalse(matcher.find(), "Expected exactly one outbox state constraint");
        Matcher stateMatcher = QUOTED_STATE.matcher(stateList);
        List<String> states = new java.util.ArrayList<>();
        while (stateMatcher.find()) states.add(stateMatcher.group(1));
        return states;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count += 1;
            offset += needle.length();
        }
        return count;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String resource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
