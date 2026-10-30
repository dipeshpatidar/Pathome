package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only local observer for operational rows without extracting customer values. */
final class OgV63SideEffectObserver {
    static final List<String> TABLES = List.of(
            "property_visit_requests",
            "visit_sessions",
            "visit_session_items",
            "visit_start_challenges",
            "visit_execution_events",
            "visit_notification_outbox",
            "lead_phone_delivery_outbox",
            "tenant_visit_entitlement_accounts",
            "visit_entitlement_ledger",
            "lead_routing_queue",
            "lead_source_events",
            "lead_activities",
            "lead_command_receipts",
            "lead_lookup_events",
            "lead_contact_preferences",
            "lead_shortlists",
            "lead_shortlist_items",
            "lead_shares",
            "lead_share_events",
            "lead_phone_challenges",
            "lead_lessor_availability_enquiries",
            "lead_visit_proposals",
            "lead_visit_continuations",
            "lead_visit_customer_responses",
            "lead_visit_association_claims",
            "lead_visit_links",
            "lead_assistance_command_receipts",
            "system_notifications",
            "operational_audit_events",
            "automatic_visit_routing_control",
            "visit_request_routing_tasks");

    private OgV63SideEffectObserver() {}

    static Map<String, String> snapshot() {
        List<String> statements = new ArrayList<>();
        statements.add("begin read only;");
        for (String table : TABLES) {
            statements.add("select '" + table + "|' || count(*) || '|' || "
                    + "coalesce(md5(string_agg(row_to_json(t)::text, E'\\n' "
                    + "order by row_to_json(t)::text)), md5('')) from public."
                    + table + " t;");
        }
        statements.add("commit;");
        String output = psql(String.join("\n", statements));
        Map<String, String> observed = new LinkedHashMap<>();
        for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
            int separator = line.indexOf('|');
            OgV63Target.require(separator > 0, "observer returned a malformed row");
            String table = line.substring(0, separator);
            OgV63Target.require(TABLES.contains(table) && !observed.containsKey(table),
                    "observer returned an unexpected/duplicate table");
            observed.put(table, line.substring(separator + 1));
        }
        OgV63Target.require(observed.keySet().containsAll(TABLES),
                "observer omitted operational tables");
        return Map.copyOf(observed);
    }

    static List<String> differences(Map<String, String> before, Map<String, String> after) {
        List<String> differences = new ArrayList<>();
        for (String table : TABLES) {
            if (!java.util.Objects.equals(before.get(table), after.get(table))) {
                differences.add(table);
            }
        }
        return List.copyOf(differences);
    }

    private static String psql(String sql) {
        ProcessBuilder builder = new ProcessBuilder(
                "psql", "-X", "-w", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                "host=127.0.0.1 port=5432 dbname=" + OgV63Target.DATABASE
                        + " user=dipeshpatidar connect_timeout=3");
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("PG")
                || key.startsWith("SPRING_"));
        environment.put("PGCONNECT_TIMEOUT", "3");
        environment.put("PGAPPNAME", OgV63Target.RUN_ID + "-readonly-observer");
        environment.put("PGGSSENCMODE", "disable");
        environment.put("PGSSLMODE", "disable");
        environment.put("LC_ALL", "C");
        try {
            Process process = builder.start();
            process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String standardOutput = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String standardError = new String(
                    process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            OgV63Target.require(process.waitFor(15, TimeUnit.SECONDS),
                    "read-only side-effect observer timed out");
            OgV63Target.require(process.exitValue() == 0,
                    "read-only side-effect observer failed");
            return standardOutput;
        } catch (IOException exception) {
            throw new IllegalStateException("Read-only side-effect observer unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Read-only side-effect observer interrupted", exception);
        }
    }
}
