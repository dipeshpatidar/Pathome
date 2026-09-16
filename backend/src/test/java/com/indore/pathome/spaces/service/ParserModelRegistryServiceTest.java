package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ParserModelRegistrationRequest;
import com.indore.pathome.spaces.dto.ParserModelVersionDTO;
import com.indore.pathome.spaces.entity.ParserModelStatus;
import com.indore.pathome.spaces.entity.ParserModelVersion;
import com.indore.pathome.spaces.repository.ParserModelVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParserModelRegistryServiceTest {

    private static final String DATASET_FINGERPRINT = "a".repeat(64);
    private static final String SIGNING_KEY = "unit-test-model-signing-key-with-32-bytes";

    @Mock
    private ParserModelVersionRepository repository;

    @TempDir
    Path modelRoot;

    @Test
    void registersOnlyAnUntamperedCandidateThatPassesSealedHoldoutGates() throws Exception {
        Path artifact = passingArtifact("candidate-one");
        when(repository.findByModelVersion("property-v1")).thenReturn(Optional.empty());
        when(repository.save(any(ParserModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        ParserModelVersionDTO registered = service.registerCandidate(new ParserModelRegistrationRequest(
                "property-v1",
                modelRoot.relativize(artifact).toString(),
                DATASET_FINGERPRINT));

        assertEquals(ParserModelStatus.CANDIDATE, registered.modelStatus());
        assertEquals(100, registered.holdoutExampleCount());
        assertEquals("Passed sealed holdout safety gates", registered.decisionReason());
    }

    @Test
    void blocksPathTraversalOutsideThePrivateModelDirectory() {
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);
        when(repository.findByModelVersion("property-v2")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v2", "../outside", "b".repeat(64))));

        assertTrue(error.getMessage().contains("private model directory"));
    }

    @Test
    void checksumMismatchCannotBeRegistered() throws Exception {
        Path artifact = passingArtifact("candidate-three");
        Files.writeString(artifact.resolve("config.json"), "changed", StandardCharsets.UTF_8);
        when(repository.findByModelVersion("property-v3")).thenReturn(Optional.empty());
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v3",
                        modelRoot.relativize(artifact).toString(),
                        "c".repeat(64))));

        assertTrue(error.getMessage().contains("checksum"));
    }

    @Test
    void datasetFingerprintMustMatchTheTrainingManifest() throws Exception {
        Path artifact = passingArtifact("candidate-four");
        when(repository.findByModelVersion("property-v4")).thenReturn(Optional.empty());
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v4",
                        modelRoot.relativize(artifact).toString(),
                        "d".repeat(64))));

        assertTrue(error.getMessage().contains("Dataset fingerprint"));
    }

    @Test
    void holdoutReportMustBelongToTheRegisteredArtifact() throws Exception {
        Path artifact = passingArtifact("candidate-five");
        Path report = artifact.getParent().resolve("holdout-metrics.json");
        writeHoldoutReport(report, "f".repeat(64));
        when(repository.findByModelVersion("property-v5")).thenReturn(Optional.empty());
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v5",
                        modelRoot.relativize(artifact).toString(),
                        DATASET_FINGERPRINT)));

        assertTrue(error.getMessage().contains("different model artifact"));
    }

    @Test
    void rejectsUnsignedOrModifiedEvaluationEvidence() throws Exception {
        Path artifact = passingArtifact("candidate-six");
        Path report = artifact.getParent().resolve("holdout-metrics.json");
        String reportJson = Files.readString(report, StandardCharsets.UTF_8)
                .replaceFirst("[a-f0-9]{64}", "0".repeat(64));
        Files.writeString(report, reportJson, StandardCharsets.UTF_8);
        when(repository.findByModelVersion("property-v6")).thenReturn(Optional.empty());
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v6", modelRoot.relativize(artifact).toString(), DATASET_FINGERPRINT)));

        assertTrue(error.getMessage().contains("signature"));
    }

    @Test
    void rejectsSymbolicLinksInsideTheArtifact() throws Exception {
        Path artifact = passingArtifact("candidate-seven");
        Path linkedTarget = Files.writeString(
                modelRoot.resolve("linked-config.json"), "{}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(artifact.resolve("linked.json"), linkedTarget);
        Files.writeString(artifact.resolve("artifact.sha256"), fingerprint(artifact), StandardCharsets.UTF_8);
        when(repository.findByModelVersion("property-v7")).thenReturn(Optional.empty());
        ParserModelRegistryService service = new ParserModelRegistryService(
                repository, new ObjectMapper(), modelRoot, SIGNING_KEY);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerCandidate(new ParserModelRegistrationRequest(
                        "property-v7", modelRoot.relativize(artifact).toString(), DATASET_FINGERPRINT)));

        assertTrue(error.getMessage().contains("symbolic links"));
    }

    private Path passingArtifact(String directory) throws Exception {
        Path candidate = Files.createDirectories(modelRoot.resolve(directory));
        Path artifact = Files.createDirectories(candidate.resolve("artifact"));
        Files.writeString(artifact.resolve("config.json"), "{\"model\":\"local\"}", StandardCharsets.UTF_8);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("trainExamples", 500);
        manifest.put("validationExamples", 100);
        manifest.put("trainFieldCounts", Map.of("rentAmount", 25));
        manifest.put("validationFieldCounts", Map.of("rentAmount", 10));
        manifest.put("labels", List.of("O", "B-rentAmount", "I-rentAmount"));
        manifest.put("datasetFingerprint", DATASET_FINGERPRINT);
        Files.writeString(artifact.resolve("training_manifest.json"), signedDocument(manifest), StandardCharsets.UTF_8);
        Files.writeString(artifact.resolve("artifact.sha256"), fingerprint(artifact), StandardCharsets.UTF_8);
        String artifactChecksum = Files.readString(artifact.resolve("artifact.sha256"), StandardCharsets.UTF_8);
        writeHoldoutReport(candidate.resolve("holdout-metrics.json"), artifactChecksum);
        return artifact;
    }

    private void writeHoldoutReport(Path report, String artifactChecksum) throws Exception {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("f1", 0.97);
        metrics.put("minimum_field_f1", 0.96);
        metrics.put("precision", 0.99);
        metrics.put("hallucination_rate", 0.0);
        metrics.put("false_positive_tokens", 0.0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactChecksum", artifactChecksum);
        payload.put("holdoutDatasetSha256", "b".repeat(64));
        payload.put("holdoutExamples", 100);
        payload.put("fieldCoveragePassed", true);
        payload.put("metrics", metrics);
        payload.put("passed", true);
        Files.writeString(report, signedDocument(payload), StandardCharsets.UTF_8);
    }

    private String signedDocument(Map<String, Object> payload) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
        return mapper.writeValueAsString(Map.of(
                "signatureAlgorithm", "HMAC-SHA256",
                "signedPayload", encoded,
                "signature", signature));
    }

    private String fingerprint(Path directory) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !"artifact.sha256".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .toList()) {
                digest.update(directory.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
