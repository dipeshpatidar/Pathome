package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.indore.pathome.spaces.dto.VisitSessionAvailabilityCommand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Batch A1 cross-boundary contract: the bytes the production OE frontend really transmits must survive
 * the real Jackson command DTO and the real {@link SchedulingWindowValidator}.
 *
 * The request bodies are not written here. They are produced by running the production frontend path
 * (the editor's own resolvers plus the production service transport) through
 * `frontend-web/src/tests/support/availabilityCommandBridge.mjs`, which prints the raw
 * `JSON.stringify` output for each row of the A1/F1 matrix. This test pushes those bytes through the
 * production deserialisation and validation, so a change on either side of the boundary breaks it.
 *
 * The reference clock is fixed because the "cannot start in the past" rule is not what this contract
 * measures; the offset acceptance and the preserved instants are.
 */
class AvailabilityTimezoneContractTest {
    private static final Instant REFERENCE_NOW = Instant.parse("2026-10-01T00:00:00Z");
    /** The A1 rows plus the F1 provenance rows. Adding a row means stating it here on purpose. */
    private static final int EXPECTED_SCENARIOS = 25;
    private static final int EXPECTED_ACCEPTED = 21;
    private static final int EXPECTED_REFUSED = 4;
    /**
     * Mirrors the application's HTTP mapper: Spring Boot disables unknown-property failures, which is
     * why the client's extra `targetType` discriminator is accepted by the real endpoint.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void productionFrontendAvailabilityBodiesAreAcceptedByTheRealDtosAndValidator() throws Exception {
        JsonNode root = MAPPER.readTree(runBridge());
        JsonNode scenarios = root.path("scenarios");
        long detailVersion = root.path("detailVersion").asLong();

        assertEquals(EXPECTED_SCENARIOS, root.path("scenarioCount").asInt(), "the A1/F1 matrix must stay complete");
        assertEquals(EXPECTED_SCENARIOS, scenarios.size(), "every declared row must be reported");
        int accepted = 0;
        int refused = 0;
        for (JsonNode scenario : scenarios) {
            String name = scenario.path("name").asText();
            String expectation = scenario.path("expectation").asText();
            JsonNode body = scenario.path("rawBody");
            if (body.isNull() || body.isMissingNode()) {
                assertEquals("refused", expectation, name + ": no request body was transmitted");
                refused++;
                assertRefusalIsReported(name, scenario);
                continue;
            }
            assertEquals("accepted", expectation, name + ": a request body was transmitted");
            accepted++;
            assertAcceptedByTheRealBoundary(name, scenario, body.asText(), detailVersion);
        }
        assertEquals(EXPECTED_ACCEPTED, accepted, "accepted scenarios");
        assertEquals(EXPECTED_REFUSED, refused, "refused scenarios");
        assertEquals(accepted, root.path("acceptedCount").asInt());
        assertEquals(refused, root.path("refusedCount").asInt());
    }

    /**
     * F1: the reported sequence — type a start, select IST, select New York again. The bytes that reach
     * the real DTO and validator must carry the typed clock, not the instant the server had recorded
     * (which is 9h30m earlier in New York and carries seconds the minute field cannot show).
     */
    @Test
    void theEditedFieldSurvivesZoneChangesAtTheRealBoundary() throws Exception {
        JsonNode row = scenarioNamed(MAPPER.readTree(runBridge()), "F1-01-start-edited-then-zone-toggles");
        Instant recorded = Instant.parse(row.path("recordedStartInstant").asText());
        Instant edited = Instant.parse(row.path("expectedStartInstant").asText());
        assertNotEquals(recorded, edited, "the row must move the instant the edit denotes");

        VisitSessionAvailabilityCommand command =
                MAPPER.readValue(row.path("rawBody").asText(), VisitSessionAvailabilityCommand.class);
        assertEquals(edited, command.availabilityStartAt().toInstant(), "the edited start is transmitted");
        assertNotEquals(recorded, command.availabilityStartAt().toInstant(), "the recorded start is not");

        SchedulingWindowValidator.Window window = SchedulingWindowValidator.required(
                command.availabilityStartAt(), command.availabilityEndAt(), command.availabilityZoneId(),
                REFERENCE_NOW, true);
        assertEquals(edited, window.startsAt());
        // The companion field was never edited, so it still denotes exactly the recorded instant.
        assertEquals(Instant.parse(row.path("recordedEndInstant").asText()), window.endsAt());
    }

    /**
     * F1: the sharpest form of the defect — the value typed into the New York field is exactly what the
     * recorded instant looks like once IST is selected. The transmitted instant must be the edited one,
     * so the recorded instant's hidden seconds must not appear on the wire.
     */
    @Test
    void anEditThatReadsLikeTheRecordedDisplayIsStillTheEdit() throws Exception {
        JsonNode row = scenarioNamed(MAPPER.readTree(runBridge()), "F1-04-edited-text-coincides-with-recorded-display");
        Instant recorded = Instant.parse(row.path("recordedStartInstant").asText());
        Instant edited = Instant.parse(row.path("expectedStartInstant").asText());
        String rawBody = row.path("rawBody").asText();
        assertNotEquals(recorded, edited, "the two readings differ only in the recorded seconds");

        VisitSessionAvailabilityCommand command = MAPPER.readValue(rawBody, VisitSessionAvailabilityCommand.class);
        assertEquals(edited, command.availabilityStartAt().toInstant(), "the edit decides the instant");
        assertTrue(rawBody.contains("18:17:00+05:30"), "the edited minute precision is written: " + rawBody);
        assertFalse(rawBody.contains(".123456789+05:30"),
                "the recorded instant's hidden seconds must not be restored: " + rawBody);
    }

    /**
     * The exact representation the defective build produced: an untouched field resent as the server's
     * UTC `Z` instant beside an IANA zone. The validator must still refuse it, which is why the frontend
     * had to change rather than the backend.
     */
    @Test
    void thePreRepairUtcRepresentationIsStillRefusedByTheRealValidator() throws Exception {
        JsonNode root = MAPPER.readTree(runBridge());
        JsonNode unchanged = scenarioNamed(root, "A1-01-both-unchanged");

        // Both fields untouched: start rejected first, exactly as the iPhone UAT warning read.
        assertOffsetRejected(
                bodyWith(unchanged.path("recordedStartInstant").asText(),
                        unchanged.path("recordedEndInstant").asText()),
                "Availability start offset does not match the supplied timezone");

        // Start edited to the zone's own offset, end left as the server delivered it: the rejection
        // moves to the untouched end, which is the second warning the owner saw.
        assertOffsetRejected(
                bodyWith("2026-10-23T13:45:00+05:30", unchanged.path("recordedEndInstant").asText()),
                "Availability end offset does not match the supplied timezone");
    }

    private static void assertAcceptedByTheRealBoundary(String name, JsonNode scenario, String rawBody,
                                                        long detailVersion) throws Exception {
        VisitSessionAvailabilityCommand command =
                MAPPER.readValue(rawBody, VisitSessionAvailabilityCommand.class);
        assertEquals(detailVersion, command.expectedSessionVersion(), name + ": authoritative Session version");
        assertEquals("CONFIRMED", command.status().name(), name);
        assertTrue(command.availabilitySource() != null, name + ": a confirmed window carries its source");

        // Zero precision loss on the wire: the serialized text still denotes the intended instant.
        Instant expectedStart = expectedInstant(scenario, "expectedStartInstant", "recordedStartInstant");
        Instant expectedEnd = expectedInstant(scenario, "expectedEndInstant", "recordedEndInstant");
        assertEquals(expectedStart, OffsetDateTime.parse(textOf(rawBody, "availabilityStartAt")).toInstant(),
                name + ": serialized start instant");
        assertEquals(expectedEnd, OffsetDateTime.parse(textOf(rawBody, "availabilityEndAt")).toInstant(),
                name + ": serialized end instant");

        SchedulingWindowValidator.Window window = SchedulingWindowValidator.required(
                command.availabilityStartAt(), command.availabilityEndAt(), command.availabilityZoneId(),
                REFERENCE_NOW, true);
        assertEquals(expectedStart, window.startsAt(), name + ": validated start instant");
        assertEquals(expectedEnd, window.endsAt(), name + ": validated end instant");
        assertEquals(command.availabilityZoneId(), window.zoneId(), name);

        if ("A1-08-subsecond-precision".equals(name)) {
            assertTrue(rawBody.contains(".123456789+05:30"),
                    "subsecond digits must be transmitted, not rounded: " + rawBody);
        }
    }

    /** A refused row must be refused by the editor itself: nothing built, nothing transmitted, and a reason. */
    private static void assertRefusalIsReported(String name, JsonNode scenario) {
        assertFalse(scenario.path("submitted").asBoolean(),
                name + ": a field that cannot be resolved must not reach the save");
        assertTrue(scenario.path("httpStatus").isNull() || scenario.path("httpStatus").isMissingNode(),
                name + ": a refused row has no response");
        String reason = scenario.path("refusalMessage").asText("");
        assertFalse(reason.isBlank(), name + ": the editor must tell the OE why the field cannot be resolved");
    }

    private static void assertOffsetRejected(String body, String expectedMessage) throws Exception {
        VisitSessionAvailabilityCommand command = MAPPER.readValue(body, VisitSessionAvailabilityCommand.class);
        IllegalArgumentException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> SchedulingWindowValidator.required(
                        command.availabilityStartAt(), command.availabilityEndAt(),
                        command.availabilityZoneId(), REFERENCE_NOW, true));
        assertEquals(expectedMessage, refusal.getMessage());
    }

    /** The pre-repair body shape: the recorded instants with their server-side `Z` designator. */
    private static String bodyWith(String start, String end) {
        return "{\"expectedSessionVersion\":6,\"status\":\"CONFIRMED\",\"availabilityStartAt\":\"" + start
                + "\",\"availabilityEndAt\":\"" + end
                + "\",\"availabilityZoneId\":\"Asia/Kolkata\",\"availabilitySource\":\"PHONE\"}";
    }

    private static Instant expectedInstant(JsonNode scenario, String expectedField, String recordedField) {
        String expected = scenario.path(expectedField).asText("");
        return Instant.parse(expected.isEmpty() ? scenario.path(recordedField).asText() : expected);
    }

    private static String textOf(String rawBody, String field) throws Exception {
        return MAPPER.readTree(rawBody).path(field).asText();
    }

    private static JsonNode scenarioNamed(JsonNode root, String name) {
        for (JsonNode scenario : root.path("scenarios")) {
            if (name.equals(scenario.path("name").asText())) return scenario;
        }
        return fail("missing bridge scenario " + name);
    }

    /**
     * Runs the production frontend bridge and returns its JSON report. Standard error is captured to a
     * file so a failure reports the real module error rather than a truncated pipe.
     */
    private static String runBridge() throws IOException, InterruptedException {
        Path frontendWeb = repositoryRoot().resolve("frontend-web");
        Path script = frontendWeb.resolve("src").resolve("tests").resolve("support")
                .resolve("availabilityCommandBridge.mjs");
        if (!Files.isRegularFile(script)) fail("bridge script is missing: " + script);
        Path errorLog = Files.createTempFile("availability-bridge-", ".err");
        Process process;
        try {
            process = new ProcessBuilder("node", script.toString())
                    .directory(frontendWeb.toFile())
                    .redirectError(errorLog.toFile())
                    .start();
        } catch (IOException unavailable) {
            return fail("node is required to exercise the production frontend boundary: " + unavailable);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return fail("the production frontend bridge did not finish within 180s");
        }
        if (process.exitValue() != 0) {
            return fail("the production frontend bridge failed (exit " + process.exitValue() + "):\n"
                    + new String(Files.readAllBytes(errorLog), StandardCharsets.UTF_8));
        }
        assertFalse(stdout.isBlank(), "the bridge produced no report");
        return stdout;
    }

    /** Maven runs this module's tests with `backend` as the working directory. */
    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(cwd, cwd.getParent() == null ? cwd : cwd.getParent())) {
            if (Files.isDirectory(candidate.resolve("frontend-web").resolve("src").resolve("tests"))) {
                return candidate;
            }
        }
        return fail("could not locate frontend-web from " + cwd);
    }
}
