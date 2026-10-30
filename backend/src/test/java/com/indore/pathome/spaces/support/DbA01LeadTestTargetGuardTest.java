package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Negative controls for the DB-A01 target guard; these tests never open a database connection. */
class DbA01LeadTestTargetGuardTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void missingFlagAndWrongManifestPathAreRefusedBeforeDatasourceAcquisition() {
        assertRefused("explicit DB-A01 Lead test flag",
                () -> DbA01LeadTestTarget.verify(Map.of()));
        assertRefused("exact child custody file",
                () -> DbA01LeadTestTarget.verify(Map.of(
                        DbA01LeadTestTarget.TEST_FLAG, "true",
                        DbA01LeadTestTarget.MANIFEST_FLAG, "/tmp/forged-custody.json")));
    }

    @Test
    void protectedOrExistingCohortAndWrongRuntimeRoleAreRefused() throws Exception {
        JsonNode manifest = actualManifest();

        ObjectNode protectedDatabase = manifest.deepCopy();
        ((ObjectNode) protectedDatabase.path("database")).put("name", "pathome_db");
        assertRefused("database", () -> DbA01LeadTestTarget.verifyManifestDocument(
                protectedDatabase));

        ObjectNode existingCohort = manifest.deepCopy();
        ((ObjectNode) existingCohort.path("database")).put(
                "name", "pathome_it_20261025_01_business");
        assertRefused("database", () -> DbA01LeadTestTarget.verifyManifestDocument(
                existingCohort));

        ObjectNode wrongRole = manifest.deepCopy();
        ((ObjectNode) wrongRole.path("roles")).put("runtime", "itp_20261025_01_runtime");
        assertRefused("runtime role", () -> DbA01LeadTestTarget.verifyManifestDocument(wrongRole));
    }

    @Test
    void staleAndSubstitutedLiveAttestationsAreRefused() throws Exception {
        JsonNode attestation = actualLiveAttestation();
        assertRefused("freshness", () -> DbA01LeadTestTarget.verifyAttestationDocument(
                attestation,
                Instant.parse(attestation.path("attestedAt").asText()).plus(11,
                        ChronoUnit.MINUTES)));

        ObjectNode substituted = attestation.deepCopy();
        substituted.put("containerId", "0".repeat(64));
        assertRefused("attestation container", () -> DbA01LeadTestTarget.verifyAttestationDocument(
                substituted, Instant.now()));
    }

    private static JsonNode actualManifest() throws Exception {
        return JSON.readTree(Files.readString(Path.of(DbA01LeadTestTarget.MANIFEST_PATH)));
    }

    private static JsonNode actualLiveAttestation() throws Exception {
        JsonNode manifest = actualManifest();
        Path directory = Path.of(
                manifest.at("/liveAttestationDirectory").asText());
        try (var files = Files.list(directory)) {
            Path latest = files.filter(path -> path.getFileName().toString()
                            .endsWith("-live-attestation.json"))
                    .sorted()
                    .reduce((first, second) -> second)
                    .orElseThrow();
            return JSON.readTree(Files.readString(latest));
        }
    }

    private static void assertRefused(String expectedMessage, Runnable action) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, action::run);
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
    }
}
