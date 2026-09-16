package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ParserModelRegistrationRequest;
import com.indore.pathome.spaces.dto.ParserModelVersionDTO;
import com.indore.pathome.spaces.entity.ParserModelStatus;
import com.indore.pathome.spaces.entity.ParserModelVersion;
import com.indore.pathome.spaces.repository.ParserModelVersionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class ParserModelRegistryService {

    private static final int MIN_HOLDOUT_EXAMPLES = 100;
    private static final int MIN_TRAIN_EXAMPLES = 500;
    private static final int MIN_VALIDATION_EXAMPLES = 100;
    private static final int MIN_TRAIN_EXAMPLES_PER_FIELD = 25;
    private static final int MIN_VALIDATION_EXAMPLES_PER_FIELD = 10;
    private static final double MIN_OVERALL_F1 = 0.95;
    private static final double MIN_PER_FIELD_F1 = 0.90;
    private static final double MIN_PRECISION = 0.98;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,99}$");
    private static final Pattern SHA_256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern BASE64_URL_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final String CHECKSUM_FILE = "artifact.sha256";
    private static final String TRAINING_MANIFEST_FILE = "training_manifest.json";
    private static final String HOLDOUT_REPORT_FILE = "holdout-metrics.json";

    private final ParserModelVersionRepository repository;
    private final ObjectMapper objectMapper;
    private final Path modelRoot;
    private final String signingKey;

    @Autowired
    public ParserModelRegistryService(
            ParserModelVersionRepository repository,
            ObjectMapper objectMapper,
            @Value("${pathome.parser.model-directory:local-nlp/models/candidates}") String modelDirectory,
            @Value("${PATHOME_MODEL_SIGNING_KEY:}") String signingKey) {
        this(repository, objectMapper, Path.of(modelDirectory), signingKey);
    }

    ParserModelRegistryService(
            ParserModelVersionRepository repository,
            ObjectMapper objectMapper,
            Path modelRoot,
            String signingKey) {
        this.repository = Objects.requireNonNull(repository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.modelRoot = Objects.requireNonNull(modelRoot).toAbsolutePath().normalize();
        this.signingKey = Objects.requireNonNull(signingKey);
    }

    @Transactional
    public ParserModelVersionDTO registerCandidate(ParserModelRegistrationRequest request) {
        Objects.requireNonNull(request, "Model registration details are required");
        validateVersion(request.modelVersion());
        validateSha256(request.datasetFingerprint(), "Dataset fingerprint");
        repository.findByModelVersion(request.modelVersion()).ifPresent(existing -> {
            throw new IllegalArgumentException("This model version is already registered");
        });

        Path artifact = resolveArtifactPath(request.artifactPath());
        Path candidateDirectory = artifact.getParent();
        if (candidateDirectory == null || !candidateDirectory.startsWith(modelRoot)) {
            throw new IllegalArgumentException("Model artifact must be inside a candidate directory");
        }
        Path checksumPath = artifact.resolve(CHECKSUM_FILE);
        Path trainingManifestPath = artifact.resolve(TRAINING_MANIFEST_FILE);
        Path holdoutReportPath = candidateDirectory.resolve(HOLDOUT_REPORT_FILE);
        requireRegularFile(checksumPath, "Model checksum file");
        requireRegularFile(trainingManifestPath, "Training manifest");
        requireRegularFile(holdoutReportPath, "Sealed holdout report");

        String declaredChecksum = readTrimmed(checksumPath);
        validateSha256(declaredChecksum, "Model checksum");
        String calculatedChecksum = directoryFingerprint(artifact);
        if (!declaredChecksum.equalsIgnoreCase(calculatedChecksum)) {
            throw new IllegalArgumentException("Model artifact checksum does not match its registered files");
        }

        String trainingManifestJson = readTrimmed(trainingManifestPath);
        validateTrainingManifest(trainingManifestJson, request.datasetFingerprint());

        String metricsJson = readTrimmed(holdoutReportPath);
        HoldoutDecision decision = evaluateHoldoutReport(metricsJson, calculatedChecksum);

        ParserModelVersion model = new ParserModelVersion();
        model.setModelVersion(request.modelVersion());
        model.setArtifactPath(modelRoot.relativize(artifact).toString());
        model.setArtifactChecksum(calculatedChecksum);
        model.setDatasetFingerprint(request.datasetFingerprint().toLowerCase());
        model.setMetricsJson(metricsJson);
        model.setHoldoutExampleCount(decision.holdoutExamples());
        model.setModelStatus(decision.passed() ? ParserModelStatus.CANDIDATE : ParserModelStatus.REJECTED);
        model.setDecisionReason(decision.reason());
        return toDto(repository.save(model));
    }

    @Transactional
    public ParserModelVersionDTO promoteToShadow(String version) {
        ParserModelVersion model = findVersion(version);
        if (model.getModelStatus() != ParserModelStatus.CANDIDATE) {
            throw new IllegalArgumentException("Only a passing candidate can enter shadow evaluation");
        }
        HoldoutDecision decision = evaluateHoldoutReport(model.getMetricsJson(), model.getArtifactChecksum());
        if (!decision.passed()) {
            model.setModelStatus(ParserModelStatus.REJECTED);
            model.setDecisionReason(decision.reason());
            repository.save(model);
            throw new IllegalArgumentException("Model safety gates no longer pass");
        }
        model.setModelStatus(ParserModelStatus.SHADOW);
        model.setDecisionReason("Passing model is restricted to shadow evaluation; production output is unchanged");
        return toDto(repository.save(model));
    }

    @Transactional(readOnly = true)
    public Page<ParserModelVersionDTO> versions(ParserModelStatus status, Pageable pageable) {
        return repository.findByModelStatusOrderByCreatedAtDesc(status, pageable).map(this::toDto);
    }

    private void validateTrainingManifest(String manifestJson, String requestedFingerprint) {
        try {
            JsonNode manifest = verifiedSignedPayload(manifestJson, "Training manifest");
            String manifestFingerprint = manifest.path("datasetFingerprint").asText("");
            validateSha256(manifestFingerprint, "Training manifest dataset fingerprint");
            if (!manifestFingerprint.equalsIgnoreCase(requestedFingerprint)) {
                throw new IllegalArgumentException("Dataset fingerprint does not match the recorded training manifest");
            }
            if (manifest.path("trainExamples").asInt(0) < MIN_TRAIN_EXAMPLES) {
                throw new IllegalArgumentException("Training manifest has too few approved training examples");
            }
            if (manifest.path("validationExamples").asInt(0) < MIN_VALIDATION_EXAMPLES) {
                throw new IllegalArgumentException("Training manifest has too few approved validation examples");
            }
            Set<String> modelFields = new HashSet<>();
            manifest.path("labels").forEach(label -> {
                String value = label.asText("");
                if (value.startsWith("B-") && value.length() > 2) modelFields.add(value.substring(2));
            });
            if (modelFields.isEmpty()) {
                throw new IllegalArgumentException("Training manifest has no field labels");
            }
            validateFieldCoverage(
                    manifest.path("trainFieldCounts"), modelFields,
                    MIN_TRAIN_EXAMPLES_PER_FIELD, "training");
            validateFieldCoverage(
                    manifest.path("validationFieldCounts"), modelFields,
                    MIN_VALIDATION_EXAMPLES_PER_FIELD, "validation");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Training manifest is not valid JSON", exception);
        }
    }

    private void validateFieldCoverage(
            JsonNode counts, Set<String> requiredFields, int minimum, String partition) {
        if (!counts.isObject() || counts.isEmpty()) {
            throw new IllegalArgumentException("Training manifest has no " + partition + " field coverage");
        }
        requiredFields.forEach(field -> {
            if (counts.path(field).asInt(0) < minimum) {
                throw new IllegalArgumentException(
                        "Field " + field + " has insufficient " + partition + " coverage");
            }
        });
    }

    private HoldoutDecision evaluateHoldoutReport(String metricsJson, String expectedArtifactChecksum) {
        try {
            JsonNode root = verifiedSignedPayload(metricsJson, "Sealed holdout report");
            JsonNode metrics = root.path("metrics");
            String evaluatedArtifactChecksum = root.path("artifactChecksum").asText("");
            if (!expectedArtifactChecksum.equalsIgnoreCase(evaluatedArtifactChecksum)) {
                throw new IllegalArgumentException(
                        "Sealed holdout report belongs to a different model artifact");
            }
            int examples = root.path("holdoutExamples").asInt(0);
            String holdoutDatasetFingerprint = root.path("holdoutDatasetSha256").asText("");
            validateSha256(holdoutDatasetFingerprint, "Holdout dataset fingerprint");
            double f1 = metrics.path("f1").asDouble(-1);
            double minimumFieldF1 = metrics.path("minimum_field_f1").asDouble(-1);
            double precision = metrics.path("precision").asDouble(-1);
            double hallucinationRate = metrics.path("hallucination_rate").asDouble(-1);
            double falsePositiveTokens = metrics.path("false_positive_tokens").asDouble(-1);
            boolean trainerPassed = root.path("passed").asBoolean(false);
            boolean fieldCoveragePassed = root.path("fieldCoveragePassed").asBoolean(false);

            List<String> failures = new java.util.ArrayList<>();
            if (examples < MIN_HOLDOUT_EXAMPLES) failures.add("insufficient sealed holdout examples");
            if (!fieldCoveragePassed) failures.add("sealed holdout field coverage is insufficient");
            if (f1 < MIN_OVERALL_F1) failures.add("overall field accuracy is below the release threshold");
            if (minimumFieldF1 < MIN_PER_FIELD_F1) {
                failures.add("at least one field is below the release threshold");
            }
            if (precision < MIN_PRECISION) failures.add("precision is below the release threshold");
            if (hallucinationRate != 0 || falsePositiveTokens != 0) {
                failures.add("unsupported field predictions were detected");
            }
            if (!trainerPassed) failures.add("the local evaluator did not approve the candidate");

            return failures.isEmpty()
                    ? new HoldoutDecision(true, examples, "Passed sealed holdout safety gates")
                    : new HoldoutDecision(false, examples, String.join("; ", failures));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Sealed holdout report is not valid JSON", exception);
        }
    }

    private Path resolveArtifactPath(String artifactPath) {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalArgumentException("Model artifact path is required");
        }
        Path supplied = Path.of(artifactPath);
        if (supplied.isAbsolute()) {
            throw new IllegalArgumentException("Model artifact path must be relative to the private model directory");
        }
        Path resolved = modelRoot.resolve(supplied).normalize();
        if (resolved.equals(modelRoot) || !resolved.startsWith(modelRoot) || !Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Model artifact must be an existing directory inside the private model directory");
        }
        try {
            rejectSymlinkedPathComponents(resolved);
            if (!resolved.toRealPath().startsWith(modelRoot.toRealPath())) {
                throw new IllegalArgumentException(
                        "Model artifact symbolic links must remain inside the private model directory");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Model artifact path could not be verified", exception);
        }
        return resolved;
    }

    private String directoryFingerprint(Path directory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> allPaths = paths.toList();
                if (allPaths.stream().anyMatch(Files::isSymbolicLink)) {
                    throw new IllegalArgumentException("Model artifact must not contain symbolic links");
                }
                List<Path> files = allPaths.stream()
                        .filter(Files::isRegularFile)
                        .filter(path -> !CHECKSUM_FILE.equals(path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                        .toList();
                byte[] buffer = new byte[8192];
                for (Path file : files) {
                    digest.update(directory.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                    try (InputStream input = Files.newInputStream(file)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Model artifact could not be verified", exception);
        }
    }

    private ParserModelVersion findVersion(String version) {
        validateVersion(version);
        return repository.findByModelVersion(version)
                .orElseThrow(() -> new EntityNotFoundException("Parser model version not found"));
    }

    private void validateVersion(String version) {
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Model version must use 3–100 letters, numbers, dots, dashes, or underscores");
        }
    }

    private void validateSha256(String checksum, String label) {
        if (checksum == null || !SHA_256_PATTERN.matcher(checksum).matches()) {
            throw new IllegalArgumentException(label + " must be a SHA-256 value");
        }
    }

    private void requireRegularFile(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " is missing or unsafe");
        }
        try {
            if (!path.toRealPath().startsWith(modelRoot.toRealPath())) {
                throw new IllegalArgumentException(label + " must remain inside the private model directory");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " path could not be verified", exception);
        }
    }

    private void rejectSymlinkedPathComponents(Path path) {
        Path current = modelRoot;
        for (Path component : modelRoot.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Model artifact path must not contain symbolic links");
            }
        }
    }

    private JsonNode verifiedSignedPayload(String documentJson, String label) throws IOException {
        if (signingKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Private model signing key is not configured securely");
        }
        JsonNode document = objectMapper.readTree(documentJson);
        if (!"HMAC-SHA256".equals(document.path("signatureAlgorithm").asText())) {
            throw new IllegalArgumentException(label + " uses an unsupported signature algorithm");
        }
        String encodedPayload = document.path("signedPayload").asText("");
        String suppliedSignature = document.path("signature").asText("");
        if (!BASE64_URL_PATTERN.matcher(encodedPayload).matches()) {
            throw new IllegalArgumentException(label + " signed payload is invalid");
        }
        validateSha256(suppliedSignature, label + " signature");
        String expectedSignature = hmacSha256(encodedPayload);
        if (!MessageDigest.isEqual(
                suppliedSignature.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                expectedSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(label + " signature could not be verified");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedPayload);
            return objectMapper.readTree(decoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " signed payload is invalid", exception);
        }
    }

    private String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String readTrimmed(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read " + path.getFileName(), exception);
        }
    }

    private ParserModelVersionDTO toDto(ParserModelVersion model) {
        return new ParserModelVersionDTO(
                model.getModelVersion(), model.getArtifactPath(), model.getArtifactChecksum(),
                model.getDatasetFingerprint(), model.getMetricsJson(), model.getHoldoutExampleCount(),
                model.getModelStatus(), model.getDecisionReason(), model.getCreatedAt(), model.getActivatedAt());
    }

    private record HoldoutDecision(boolean passed, int holdoutExamples, String reason) {}
}
