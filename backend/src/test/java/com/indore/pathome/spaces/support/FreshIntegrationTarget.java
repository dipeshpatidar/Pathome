package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Test-only, fail-closed binding for the single owner-approved fresh integration environment.
 *
 * <p>This class intentionally has no fallback target. The historical disposable-database helper
 * remains unchanged because it protects unrelated accepted campaigns. This policy accepts only the
 * literal Gate P resource identities and an explicit stage/cohort manifest. Credentials are loaded
 * from one private file and are never part of the manifest, environment, or diagnostic text.</p>
 */
public final class FreshIntegrationTarget {
    public static final String TEST_ENABLEMENT_FLAG = "PATHOME_FRESH_INTEGRATION_TEST";
    public static final String MANIFEST_FLAG = "PATHOME_FRESH_INTEGRATION_MANIFEST";
    public static final String RUN_NAME = "backoffice-it-20261025-01";
    public static final String RUN_UUID = "9d38d6f0-791d-4cbb-9442-fde2d5f0b101";
    public static final String ORIGINAL_CONTAINER_ID =
            "dd3a36122f3f510b2fb65638125a5f82a70be88c4618ab19c86229e8103f06b1";
    public static final String CONTAINER = "pathome-it-backoffice-20261025-01";
    public static final String NETWORK = "pathome-it-backoffice-20261025-01-net";
    public static final String VOLUME = "pathome-it-backoffice-20261025-01-pgdata";
    public static final String HOST = "127.0.0.1";
    public static final int HOST_PORT = 55432;
    public static final int CONTAINER_PORT = 5432;
    public static final String RUN_ROOT = "/private/tmp/pathome-it-backoffice-20261025-01";
    public static final String MARKER = "PATHOME_DISPOSABLE_INTEGRATION_V1";
    public static final String PROVISIONER_PASSWORD_TARGET =
            "/run/pathome-provisioner.password";
    public static final String PGPASS_TARGET = "/run/pathome-roles.pgpass";
    public static final String APPROVAL_MANIFEST_SHA256 =
            "83aa6f3b416f5ce271245afd1e52d8b704bf8f1b75a57fc1c68942e5001647a4";
    public static final String APPROVED_IMAGE_DIGEST =
            "sha256:d81f7b51697014367236d07ec58b54153fc28f828b72bf247fe5c7487520bcb0";
    public static final String RECOVERY_EVIDENCE_DIRECTORY =
            "evidence/20261025T223730+0530-security-recovery";
    public static final String CLEAN_DATABASE = "pathome_it_20261025_01_clean";
    public static final String UPGRADE_DATABASE = "pathome_it_20261025_01_upgrade";
    public static final String BUSINESS_DATABASE = "pathome_it_20261025_01_business";
    public static final String PROVISIONER_ROLE = "itp_20261025_01_provisioner";
    public static final String DATABASE_OWNER_ROLE = "itp_20261025_01_db_owner";
    public static final String METADATA_ROLE = "itp_20261025_01_metadata";
    public static final String MIGRATOR_ROLE = "itp_20261025_01_migrator";
    public static final String RUNTIME_ROLE = "itp_20261025_01_runtime";
    public static final String CLEANUP_ROLE = "itp_20261025_01_cleanup";
    public static final String LEGACY_CORE_SHA256 =
            "a38d4fc50ddf08be2c3e17affaeb1b65d84bfa8ff23b402902693f39d543884f";
    public static final String MIGRATIONS_SHA256 =
            "78c9d6b5a22f2602308aff2363e25576f38dca5a8298029908923791cab944e5";
    public static final String CURRENT_MIGRATIONS_SHA256 =
            "e45b80c045fca5731636e2007b9f5080a8dfc88deb6e578362b23461466cfaa0";
    public static final String V59_MIGRATIONS_SHA256 =
            "8fdfce8ea3475f99aea3a0ca53cd0508291589dbffbab83ebc97464c92d61589";
    public static final String V60_MIGRATIONS_SHA256 =
            "ff9a18371e2d41d65a9c6181679abd20ad606691428883cd9e8239475c931512";
    public static final String V55_SHA256 =
            "f882fe7aeced548b3ef3a71cf112b1aa8e6252a8eaacb335ef80c4c5e8034246";
    public static final String V56_SHA256 =
            "b00907eb7e678244626bd3081e0fd3409ad087075ea11195ef24bc7b87a318dc";
    public static final String V57_SHA256 =
            "1c0e0faa088f088188a21593e6836c846fa1f5e1b448070fe6639769b555b69a";
    public static final String V58_SHA256 =
            "45ea912db2cca7ddb9b45dbdebcc02bf0fcf820526a11c0c330f24fc96b9f872";
    public static final String V59_SHA256 =
            "456463740b4e1dbf6515d1b94046f74b76a49aa97da45462438f40b46cfff7a3";
    public static final String V60_SHA256 =
            "403b3443d237fa3b6ac667be0280a0e660be6aec71b02bc4e88d1c3c1fe4de55";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CONTAINER_ID = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern ROLE_NAME = Pattern.compile("^itp_[0-9]{8}_[0-9]{2}_[a-z_]+$");
    private static final Pattern DATABASE_NAME = Pattern.compile("^pathome_it_[0-9]{8}_[0-9]{2}_[a-z]+$");
    private static final Pattern EVIDENCE_LINE =
            Pattern.compile("^([0-9a-f]{64})  \\./(.+)$");
    private static final Pattern LIVE_ATTESTATION_FILE =
            Pattern.compile("^[0-9]{8}T[0-9]{12}Z-live-attestation\\.json$");
    private static final Set<String> ALLOWED_TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "provisioningState", "permittedStages", "runName", "runUuid",
            "container", "network",
            "volume", "host", "port", "containerServerAddress", "containerServerPort",
            "containerId", "imageDigest", "volumeId", "serverVersion", "pgSystemIdentifier",
            "approvalManifestSha256", "custodianReference", "roles", "cohorts", "sourcePins");
    private static final Set<String> ALLOWED_ROLE_FIELDS = Set.of(
            "provisioner", "databaseOwner", "metadata", "migrator", "runtime", "cleanup");
    private static final Set<String> ALLOWED_COHORT_FIELDS = Set.of("clean", "upgrade", "business");
    private static final Set<String> ALLOWED_SOURCE_FIELDS =
            Set.of("legacyCore", "migrations", "v55", "v56", "v57");
    private static final Set<String> ALLOWED_RECEIPT_FIELDS = Set.of(
            "schemaVersion", "stage", "runUuid", "manifestSha256", "cohort", "database",
            "migratorRole", "applicationSchema", "baselineVersion", "targetVersion",
            "migrationCount", "historyValidated", "custodianReference", "sourcePins",
            "currentMigrationsSha256", "v58Sha256", "v59Sha256", "v60Sha256",
            "completedAt");
    private static final Set<String> ALLOWED_RECOVERY_FIELDS = Set.of(
            "schemaVersion", "provisioningState", "recoveryReason", "authorityReference",
            "runUuid", "originalContainerId", "activeContainerId", "originalCustodySha256",
            "container", "imageDigest", "network", "networkId", "volume", "volumeId",
            "pgSystemIdentifier", "containerServerAddress", "replacedAt", "mounts",
            "cohortSnapshots", "preRecoveryEvidenceSha256", "postRecoveryEvidenceSha256");
    private static final Set<String> ALLOWED_RECOVERY_MOUNT_FIELDS =
            Set.of("type", "name", "source", "destination", "readOnly");
    private static final Set<String> ALLOWED_RECOVERY_SNAPSHOT_FIELDS =
            Set.of("beforeSha256", "afterSha256");
    private static final Set<String> ALLOWED_RECOVERY_EVIDENCE_FIELDS = Set.of(
            "schemaVersion", "provisioningState", "authorityReference", "runUuid",
            "baseRecoverySha256", "evidenceDirectory", "supersededEvidence",
            "actualEvidence");
    private static final Set<String> ALLOWED_SUPERSEDED_EVIDENCE_FIELDS = Set.of(
            "cohortSnapshots", "preRecoveryEvidenceSha256",
            "postRecoveryEvidenceSha256");
    private static final Set<String> ALLOWED_ACTUAL_EVIDENCE_FIELDS =
            Set.of("manifestSha256", "cohortSnapshots");
    private static final Set<String> ALLOWED_ACTUAL_SNAPSHOT_FIELDS = Set.of(
            "beforeFile", "afterFile", "beforeSha256", "afterSha256");
    private static final Set<String> ALLOWED_LIVE_FIELDS = Set.of(
            "schemaVersion", "status", "runUuid", "custodySha256",
            "recoveryCustodySha256", "recoveryEvidenceSha256", "originalContainerId",
            "activeContainerId", "networkId", "containerServerAddress",
            "pgSystemIdentifier", "tunnel", "attestedAt");
    private static final Set<String> ALLOWED_TUNNEL_FIELDS = Set.of(
            "mode", "listener", "pid", "uid", "target", "containerAddress",
            "containerId", "networkId", "dockerPublishedPorts", "ephemeral");
    private static final Set<String> FORBIDDEN_ENVIRONMENT_NAMES = Set.of(
            "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE",
            "PGOPTIONS", "PGSERVICEFILE", "PGPASSFILE", "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", "PATHOME_OE_TEST_ALLOW_DISPOSABLE",
            "PATHOME_OE_TEST_DB", "PATHOME_OE_TEST_DB_HOST", "PATHOME_OE_TEST_DB_PORT",
            "PATHOME_PACKAGE5_FLYWAY_TEST");
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public enum Stage {
        PREPARATION("P"),
        ATTESTATION("A"),
        INTEGRATION("I");

        private final String code;

        Stage(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public enum Cohort {
        CLEAN("clean", CLEAN_DATABASE),
        UPGRADE("upgrade", UPGRADE_DATABASE),
        BUSINESS("business", BUSINESS_DATABASE);

        private final String key;
        private final String database;

        Cohort(String key, String database) {
            this.key = key;
            this.database = database;
        }

        public String key() {
            return key;
        }

        public String database() {
            return database;
        }
    }

    public record SourcePins(
            String legacyCore,
            String migrations,
            String v55,
            String v56,
            String v57) {
        public SourcePins {
            requireDigest(legacyCore, "legacy-core source pin");
            requireDigest(migrations, "migration-manifest source pin");
            requireDigest(v55, "V55 source pin");
            requireDigest(v56, "V56 source pin");
            requireDigest(v57, "V57 source pin");
        }

        public void requireApprovedPins() {
            if (!LEGACY_CORE_SHA256.equals(legacyCore)
                    || !MIGRATIONS_SHA256.equals(migrations)
                    || !V55_SHA256.equals(v55)
                    || !V56_SHA256.equals(v56)
                    || !V57_SHA256.equals(v57)) {
                throw refuse("the manifest source pins differ from the approved Gate P bytes");
            }
        }
    }

    public record Manifest(
            int schemaVersion,
            String provisioningState,
            List<String> permittedStages,
            String runName,
            String runUuid,
            String container,
            String network,
            String volume,
            String host,
            int port,
            String containerServerAddress,
            int containerServerPort,
            String containerId,
            String imageDigest,
            String volumeId,
            String serverVersion,
            String pgSystemIdentifier,
            String approvalManifestSha256,
            String custodianReference,
            Map<String, String> roles,
            Map<String, String> cohorts,
            SourcePins sourcePins) {
        public Manifest {
            roles = immutableCopy(roles);
            cohorts = immutableCopy(cohorts);
            permittedStages = permittedStages == null
                    ? List.of() : Collections.unmodifiableList(new ArrayList<>(permittedStages));
        }

        Manifest withActiveIdentity(String activeContainerId, String activeServerAddress) {
            return new Manifest(
                    schemaVersion, provisioningState, permittedStages, runName, runUuid,
                    container, network, volume, host, port, activeServerAddress,
                    containerServerPort, activeContainerId, imageDigest, volumeId, serverVersion,
                    pgSystemIdentifier, approvalManifestSha256, custodianReference, roles, cohorts,
                    sourcePins);
        }
    }

    public record Target(
            Manifest manifest,
            Cohort cohort,
            String database,
            String role,
            Path secretFile,
            String password,
            String networkId) {
        public Target {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(cohort, "cohort");
            Objects.requireNonNull(secretFile, "secretFile");
            password = Objects.requireNonNull(password, "password");
        }

        public String jdbcUrl() {
            return "jdbc:postgresql://" + HOST + ":" + HOST_PORT + "/" + database;
        }

        public StrictConnectionPolicy policy() {
            return new StrictConnectionPolicy(this);
        }

        @Override
        public String toString() {
            return "FreshIntegrationTarget[run=" + manifest.runUuid() + ", cohort=" + cohort.key()
                    + ", database=" + database + ", role=" + role + ", secret=<redacted>]";
        }
    }

    /** Stage/cohort authorization with no credential material. */
    public record Selection(
            Manifest manifest,
            Cohort cohort,
            String database,
            String role,
            Path secretFile) {}

    public record MigrationReceipt(
            int schemaVersion,
            String stage,
            String runUuid,
            String manifestSha256,
            String cohort,
            String database,
            String migratorRole,
            String applicationSchema,
            int baselineVersion,
            int targetVersion,
            int migrationCount,
            boolean historyValidated,
            String custodianReference,
            SourcePins sourcePins,
            String currentMigrationsSha256,
            String v58Sha256,
            String v59Sha256,
            String v60Sha256,
            String completedAt) {}

    private record RecoveryMount(
            String type,
            String name,
            String source,
            String destination,
            boolean readOnly) {}

    private record RecoverySnapshot(String beforeSha256, String afterSha256) {}

    private record RecoveryBinding(Manifest manifest, String networkId) {}

    private record ActiveRecovery(
            int schemaVersion,
            String provisioningState,
            String recoveryReason,
            String authorityReference,
            String runUuid,
            String originalContainerId,
            String activeContainerId,
            String originalCustodySha256,
            String container,
            String imageDigest,
            String network,
            String networkId,
            String volume,
            String volumeId,
            String pgSystemIdentifier,
            String containerServerAddress,
            String replacedAt,
            List<RecoveryMount> mounts,
            Map<String, RecoverySnapshot> cohortSnapshots,
            String preRecoveryEvidenceSha256,
            String postRecoveryEvidenceSha256) {}

    /** Additive policy consumed by {@link RunOwnedSchemaDataSource}. */
    public interface ConnectionPolicy {
        void prepare(Connection connection) throws SQLException;

        void beforeStatement(Connection connection) throws SQLException;
    }

    private FreshIntegrationTarget() {}

    public static Target requireFromEnvironment(Stage requestedStage, Cohort cohort) {
        return requireFromEnvironment(requestedStage, cohort,
                defaultRole(requestedStage, cohort));
    }

    public static Target requireFromEnvironment(
            Stage requestedStage, Cohort cohort, String requiredRole) {
        return requireFromEnvironment(requestedStage, cohort, requiredRole, System.getenv());
    }

    static Target requireFromEnvironment(
            Stage requestedStage, Cohort cohort, String requiredRole,
            Map<String, String> environment) {
        requireCleanEnvironment(environment);
        if (!"true".equals(environment.get(TEST_ENABLEMENT_FLAG))) {
            throw refuse("the fresh integration tests are disabled; set "
                    + TEST_ENABLEMENT_FLAG + "=true only under the approved Gate I contract");
        }
        String rawPath = environment.get(MANIFEST_FLAG);
        if (rawPath == null || rawPath.isBlank()) {
            throw refuse("no fresh integration manifest was explicitly designated");
        }
        return load(Path.of(rawPath), requestedStage, cohort, requiredRole, environment);
    }

    /**
     * Loads a literal manifest. The environment argument is injectable so negative controls can
     * prove an inherited service or legacy test setting is refused before any connection is opened.
     */
    public static Target load(Path manifestPath, Stage requestedStage, Cohort cohort,
                              Map<String, String> environment) {
        return load(manifestPath, requestedStage, cohort,
                defaultRole(requestedStage, cohort), environment);
    }

    public static Target load(Path manifestPath, Stage requestedStage, Cohort cohort,
                              String requiredRole, Map<String, String> environment) {
        requireCleanEnvironment(environment);
        Path normalized = manifestPath.toAbsolutePath().normalize();
        if (!normalized.equals(Path.of(RUN_ROOT, "custody.json"))) {
            throw refuse("the manifest path is not the one approved fresh custody manifest");
        }
        requirePrivateFile(normalized, "custody manifest");
        String content;
        try {
            content = Files.readString(normalized, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw refuse("the custody manifest could not be read safely");
        }
        Manifest originalManifest = parseManifest(content, requestedStage);
        Path recoveryPath = Path.of(RUN_ROOT, "recovery-custody.json");
        Manifest activeManifest;
        String activeNetworkId = null;
        Path recoveryEvidencePath = Path.of(RUN_ROOT, "recovery-evidence.json");
        if (Files.exists(recoveryPath, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(recoveryPath, "recovery custody record");
            String recoveryContent;
            try {
                recoveryContent = Files.readString(recoveryPath, StandardCharsets.UTF_8);
            } catch (IOException | SecurityException exception) {
                throw refuse("the recovery custody record could not be read safely");
            }
            RecoveryBinding recovery = applyRecovery(content, recoveryContent, originalManifest);
            activeManifest = recovery.manifest();
            activeNetworkId = recovery.networkId();
            requirePrivateFile(recoveryEvidencePath, "recovery evidence correction");
            String evidenceContent;
            try {
                evidenceContent = Files.readString(
                        recoveryEvidencePath, StandardCharsets.UTF_8);
            } catch (IOException | SecurityException exception) {
                throw refuse("the recovery evidence correction could not be read safely");
            }
            validateRecoveryEvidence(evidenceContent, recoveryContent);
        } else {
            requireEquals(ORIGINAL_CONTAINER_ID, originalManifest.containerId(),
                    "original active container");
            activeManifest = originalManifest;
        }
        requireLiveAttestation(
                latestLiveAttestation(),
                content,
                recoveryPath,
                recoveryEvidencePath,
                activeManifest,
                activeNetworkId);
        Selection selection = select(activeManifest,
                requestedStage, cohort, requiredRole);
        requirePrivateFile(selection.secretFile(), "role secret");
        String password;
        try {
            password = Files.readString(selection.secretFile(), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw refuse("the selected role secret could not be read safely");
        }
        if (password.isBlank() || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            throw refuse("the selected role secret is empty or malformed");
        }
        return new Target(selection.manifest(), selection.cohort(), selection.database(),
                selection.role(), selection.secretFile(), password, activeNetworkId);
    }

    public static Selection parse(String json, Stage requestedStage, Cohort cohort) {
        return select(parseManifest(json, requestedStage), requestedStage, cohort,
                defaultRole(requestedStage, cohort));
    }

    private static Manifest parseManifest(String json, Stage requestedStage) {
        Manifest manifest;
        try {
            JsonNode root = JSON.readTree(json);
            requireExactFields(root, ALLOWED_TOP_LEVEL_FIELDS, "manifest");
            requireExactFields(root.path("roles"), ALLOWED_ROLE_FIELDS, "roles");
            requireExactFields(root.path("cohorts"), ALLOWED_COHORT_FIELDS, "cohorts");
            requireExactFields(root.path("sourcePins"), ALLOWED_SOURCE_FIELDS, "sourcePins");
            manifest = JSON.treeToValue(root, Manifest.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw refuse("the fresh integration manifest is malformed or carries duplicate fields");
        }
        validateManifest(manifest, requestedStage);
        return manifest;
    }

    static Manifest applyRecovery(String originalCustodyJson, String recoveryJson) {
        return applyRecovery(
                originalCustodyJson,
                recoveryJson,
                parseManifest(originalCustodyJson, Stage.ATTESTATION)).manifest();
    }

    private static RecoveryBinding applyRecovery(
            String originalCustodyJson,
            String recoveryJson,
            Manifest originalManifest) {
        ActiveRecovery recovery;
        try {
            JsonNode root = JSON.readTree(recoveryJson);
            requireExactFields(root, ALLOWED_RECOVERY_FIELDS, "recovery custody");
            JsonNode mounts = root.path("mounts");
            if (!mounts.isArray() || mounts.size() != 3) {
                throw refuse("recovery custody must contain exactly three mount records");
            }
            for (int index = 0; index < mounts.size(); index++) {
                requireExactFields(mounts.get(index), ALLOWED_RECOVERY_MOUNT_FIELDS,
                        "recovery mount " + index);
            }
            JsonNode snapshots = root.path("cohortSnapshots");
            requireExactFields(snapshots, Set.of("clean", "upgrade", "business"),
                    "recovery cohort snapshots");
            for (String cohort : List.of("clean", "upgrade", "business")) {
                requireExactFields(snapshots.path(cohort), ALLOWED_RECOVERY_SNAPSHOT_FIELDS,
                        cohort + " recovery snapshot");
            }
            recovery = JSON.treeToValue(root, ActiveRecovery.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw refuse("the recovery custody record is malformed or duplicated");
        }
        validateRecovery(recovery, originalManifest,
                sha256(originalCustodyJson.getBytes(StandardCharsets.UTF_8)));
        return new RecoveryBinding(
                originalManifest.withActiveIdentity(
                        recovery.activeContainerId(), recovery.containerServerAddress()),
                recovery.networkId());
    }

    private static void validateRecovery(
            ActiveRecovery recovery,
            Manifest original,
            String originalCustodySha256) {
        requireEquals(1, recovery.schemaVersion(), "recovery schema version");
        requireEquals("RECOVERED", recovery.provisioningState(), "recovery state");
        requireEquals("SECRET_DIRECTORY_MOUNT_REMEDIATION", recovery.recoveryReason(),
                "recovery reason");
        requireEquals("OWNER_CONTROLLED_SECURITY_RECOVERY_20261025",
                recovery.authorityReference(), "recovery authority");
        requireEquals(RUN_UUID, recovery.runUuid(), "recovery run UUID");
        requireEquals(ORIGINAL_CONTAINER_ID, original.containerId(),
                "recovery original container");
        requireEquals(ORIGINAL_CONTAINER_ID, recovery.originalContainerId(),
                "recovery original provenance");
        if (recovery.activeContainerId() == null
                || !CONTAINER_ID.matcher(recovery.activeContainerId()).matches()
                || recovery.activeContainerId().equals(recovery.originalContainerId())) {
            throw refuse("recovery active container ID is malformed or stale");
        }
        requireEquals(originalCustodySha256, recovery.originalCustodySha256(),
                "recovery original custody binding");
        requireEquals(CONTAINER, recovery.container(), "recovery container");
        requireEquals(original.imageDigest(), recovery.imageDigest(), "recovery image digest");
        requireEquals(NETWORK, recovery.network(), "recovery network");
        if (recovery.networkId() == null
                || !CONTAINER_ID.matcher(recovery.networkId()).matches()) {
            throw refuse("recovery network ID is malformed");
        }
        requireEquals(VOLUME, recovery.volume(), "recovery volume");
        requireEquals(original.volumeId(), recovery.volumeId(), "recovery volume identity");
        requireEquals(original.pgSystemIdentifier(), recovery.pgSystemIdentifier(),
                "recovery PostgreSQL system identifier");
        if (!isIpv4(recovery.containerServerAddress())
                || HOST.equals(recovery.containerServerAddress())) {
            throw refuse("recovery container address is not the active IPv4 container");
        }
        try {
            OffsetDateTime.parse(recovery.replacedAt());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw refuse("recovery timestamp is absent or malformed");
        }
        if (recovery.mounts() == null || recovery.mounts().size() != 3) {
            throw refuse("recovery custody must contain exactly three mount records");
        }
        RecoveryMount volume = recovery.mounts().get(0);
        RecoveryMount provisioner = recovery.mounts().get(1);
        RecoveryMount pgpass = recovery.mounts().get(2);
        requireEquals(new RecoveryMount("volume", VOLUME, null,
                        "/var/lib/postgresql/data", false), volume, "recovery volume mount");
        requireEquals(new RecoveryMount("bind", null,
                        Path.of(RUN_ROOT, "secrets", PROVISIONER_ROLE + ".password").toString(),
                        PROVISIONER_PASSWORD_TARGET, true),
                provisioner, "recovery provisioner mount");
        requireEquals(new RecoveryMount("bind", null,
                        Path.of(RUN_ROOT, "secrets", "roles.pgpass").toString(),
                        PGPASS_TARGET, true),
                pgpass, "recovery pgpass mount");
        if (recovery.cohortSnapshots() == null
                || !recovery.cohortSnapshots().keySet()
                        .equals(Set.of("clean", "upgrade", "business"))) {
            throw refuse("recovery cohort snapshots are incomplete");
        }
        for (Map.Entry<String, RecoverySnapshot> entry : recovery.cohortSnapshots().entrySet()) {
            requireDigest(entry.getValue().beforeSha256(), entry.getKey() + " before snapshot");
            requireDigest(entry.getValue().afterSha256(), entry.getKey() + " after snapshot");
            requireEquals(entry.getValue().beforeSha256(), entry.getValue().afterSha256(),
                    entry.getKey() + " recovery snapshot");
        }
        requireDigest(recovery.preRecoveryEvidenceSha256(), "pre-recovery evidence");
        requireDigest(recovery.postRecoveryEvidenceSha256(), "post-recovery evidence");
    }

    private static void validateRecoveryEvidence(
            String correctionContent, String recoveryContent) {
        JsonNode correction;
        JsonNode recovery;
        try {
            correction = JSON.readTree(correctionContent);
            recovery = JSON.readTree(recoveryContent);
        } catch (IOException exception) {
            throw refuse("the recovery evidence correction is malformed or duplicated");
        }
        requireExactFields(
                correction, ALLOWED_RECOVERY_EVIDENCE_FIELDS,
                "recovery evidence correction");
        requireEquals(1, correction.path("schemaVersion").asInt(),
                "recovery evidence schema version");
        requireEquals("RECOVERY_EVIDENCE_CORRECTED",
                correction.path("provisioningState").asText(),
                "recovery evidence state");
        requireEquals("OWNER_CONTROLLED_SECURITY_RECOVERY_20261025",
                correction.path("authorityReference").asText(),
                "recovery evidence authority");
        requireEquals(RUN_UUID, correction.path("runUuid").asText(),
                "recovery evidence run UUID");
        requireEquals(sha256(recoveryContent.getBytes(StandardCharsets.UTF_8)),
                correction.path("baseRecoverySha256").asText(),
                "recovery evidence custody binding");
        requireEquals(RECOVERY_EVIDENCE_DIRECTORY,
                correction.path("evidenceDirectory").asText(),
                "recovery evidence directory");
        String manifestSha256 = verifyEvidenceManifest();

        JsonNode superseded = correction.path("supersededEvidence");
        requireExactFields(
                superseded, ALLOWED_SUPERSEDED_EVIDENCE_FIELDS,
                "superseded recovery evidence");
        JsonNode supersededSnapshots = superseded.path("cohortSnapshots");
        requireExactFields(
                supersededSnapshots, Set.of("clean", "upgrade", "business"),
                "superseded recovery snapshots");
        JsonNode recoverySnapshots = recovery.path("cohortSnapshots");
        for (String cohort : List.of("clean", "upgrade", "business")) {
            requireExactFields(
                    supersededSnapshots.path(cohort),
                    ALLOWED_RECOVERY_SNAPSHOT_FIELDS,
                    cohort + " superseded recovery snapshot");
            requireEquals(
                    recoverySnapshots.path(cohort), supersededSnapshots.path(cohort),
                    cohort + " immutable recovery evidence claims");
        }
        for (String field : List.of(
                "preRecoveryEvidenceSha256", "postRecoveryEvidenceSha256")) {
            requireEquals(recovery.path(field).asText(), superseded.path(field).asText(),
                    field + " immutable recovery claim");
        }

        JsonNode actual = correction.path("actualEvidence");
        requireExactFields(actual, ALLOWED_ACTUAL_EVIDENCE_FIELDS,
                "actual recovery evidence");
        requireEquals(manifestSha256, actual.path("manifestSha256").asText(),
                "actual recovery evidence manifest");
        JsonNode snapshots = actual.path("cohortSnapshots");
        requireExactFields(snapshots, Set.of("clean", "upgrade", "business"),
                "actual recovery snapshots");
        Path evidence = evidenceDirectory();
        for (String cohort : List.of("clean", "upgrade", "business")) {
            JsonNode snapshot = snapshots.path(cohort);
            requireExactFields(snapshot, ALLOWED_ACTUAL_SNAPSHOT_FIELDS,
                    cohort + " actual recovery snapshot");
            for (String side : List.of("before", "after")) {
                String expectedFile = ("before".equals(side) ? "pre-" : "post-")
                        + cohort + ".json";
                requireEquals(expectedFile, snapshot.path(side + "File").asText(),
                        cohort + " actual evidence filename");
                String expectedDigest = snapshot.path(side + "Sha256").asText();
                requireDigest(expectedDigest, cohort + " actual evidence digest");
                requireEquals(expectedDigest, sha256EvidenceFile(evidence.resolve(expectedFile)),
                        cohort + " actual evidence bytes");
            }
            requireEquals(snapshot.path("beforeSha256").asText(),
                    snapshot.path("afterSha256").asText(),
                    cohort + " actual database snapshot");
        }
    }

    private static Path evidenceDirectory() {
        Path runRoot = Path.of(RUN_ROOT);
        if (Files.isSymbolicLink(runRoot)) {
            throw refuse("the run root is a symlink");
        }
        Path directory = runRoot.resolve(RECOVERY_EVIDENCE_DIRECTORY).normalize();
        if (!directory.equals(runRoot.resolve(RECOVERY_EVIDENCE_DIRECTORY))) {
            throw refuse("the recovery evidence directory is not literal");
        }
        Path current = runRoot;
        for (Path part : Path.of(RECOVERY_EVIDENCE_DIRECTORY)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw refuse("the recovery evidence path contains a symlink");
            }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("the recovery evidence directory is unavailable");
        }
        requireOwnerAndMode(directory, 0700, "recovery evidence directory");
        return directory;
    }

    private static String verifyEvidenceManifest() {
        Path evidence = evidenceDirectory();
        Path manifest = evidence.resolve("SHA256SUMS");
        requireEvidenceManifest(manifest);
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw refuse("the recovery evidence manifest could not be read");
        }
        if (lines.isEmpty()) {
            throw refuse("the recovery evidence manifest is empty");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            var matcher = EVIDENCE_LINE.matcher(line);
            if (!matcher.matches()) {
                throw refuse("the recovery evidence manifest line is malformed");
            }
            String expectedDigest = matcher.group(1);
            String relative = matcher.group(2);
            Path relativePath = Path.of(relative);
            if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")
                    || !seen.add(relative)) {
                throw refuse("the recovery evidence manifest path is unsafe or duplicated");
            }
            Path resolved = evidence;
            for (Path component : relativePath) {
                resolved = resolved.resolve(component);
                if (Files.isSymbolicLink(resolved)) {
                    throw refuse("the recovery evidence manifest references a symlink");
                }
            }
            requireEquals(expectedDigest,
                    sha256EvidenceFile(resolved),
                    "recovery evidence file " + relative);
        }
        return sha256EvidenceFile(manifest);
    }

    private static void requireEvidenceManifest(Path path) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("recovery evidence manifest is not a regular file");
        }
        try {
            int actualMode = posixMode(path);
            if (actualMode != 0600 && actualMode != 0644) {
                throw refuse("recovery evidence manifest mode is unsafe");
            }
            Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Object custodyOwner = Files.getAttribute(
                    Path.of(RUN_ROOT, "custody.json"), "unix:uid", LinkOption.NOFOLLOW_LINKS);
            requireEquals(custodyOwner, owner, "recovery evidence manifest owner");
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number number) || number.longValue() != 1) {
                throw refuse("recovery evidence manifest hard-link count is unsafe");
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw refuse("recovery evidence manifest metadata is unavailable");
        }
    }

    private static int posixMode(Path path) throws IOException {
        return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .stream()
                .mapToInt(permission -> switch (permission) {
                    case OWNER_READ -> 0400;
                    case OWNER_WRITE -> 0200;
                    case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040;
                    case GROUP_WRITE -> 0020;
                    case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004;
                    case OTHERS_WRITE -> 0002;
                    case OTHERS_EXECUTE -> 0001;
                })
                .reduce(0, (left, right) -> left | right);
    }

    private static String sha256EvidenceFile(Path path) {
        requireEvidenceFile(path);
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException | SecurityException exception) {
            throw refuse("the recovery evidence file could not be read");
        }
    }

    private static void requireEvidenceFile(Path path) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("recovery evidence file is not a regular file");
        }
        try {
            int actualMode = posixMode(path);
            if (actualMode != 0600 && actualMode != 0644 && actualMode != 0700) {
                throw refuse("recovery evidence file mode is unsafe");
            }
            Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Object custodyOwner = Files.getAttribute(
                    Path.of(RUN_ROOT, "custody.json"), "unix:uid", LinkOption.NOFOLLOW_LINKS);
            requireEquals(custodyOwner, owner, "recovery evidence file owner");
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number number) || number.longValue() != 1) {
                throw refuse("recovery evidence file hard-link count is unsafe");
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw refuse("recovery evidence file metadata is unavailable");
        }
    }

    private static void requireOwnerAndMode(Path path, int mode, String label) {
        try {
            int actualMode = posixMode(path);
            if (actualMode != mode) {
                throw refuse(label + " mode is not " + Integer.toOctalString(mode));
            }
            Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Object custodyOwner = Files.getAttribute(
                    Path.of(RUN_ROOT, "custody.json"), "unix:uid", LinkOption.NOFOLLOW_LINKS);
            requireEquals(custodyOwner, owner, label + " owner");
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw refuse(label + " ownership or mode is unavailable");
        }
    }

    static void validateLiveAttestation(
            String attestationJson,
            Manifest manifest,
            String networkId,
            String custodySha256,
            String recoverySha256,
            String recoveryEvidenceSha256,
            Instant now) {
        long ownerUid;
        try {
            Object owner = Files.getAttribute(
                    Path.of(RUN_ROOT, "custody.json"), "unix:uid",
                    LinkOption.NOFOLLOW_LINKS);
            if (!(owner instanceof Number number)) {
                throw refuse("the custody owner UID is unavailable");
            }
            ownerUid = number.longValue();
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw refuse("the custody owner UID could not be verified");
        }
        validateLiveAttestation(
                attestationJson, manifest, networkId, custodySha256, recoverySha256,
                recoveryEvidenceSha256, now, ownerUid);
    }

    static void validateLiveAttestation(
            String attestationJson,
            Manifest manifest,
            String networkId,
            String custodySha256,
            String recoverySha256,
            String recoveryEvidenceSha256,
            Instant now,
            long expectedOwnerUid) {
        JsonNode live;
        try {
            live = JSON.readTree(attestationJson);
        } catch (IOException exception) {
            throw refuse("the live attestation is malformed or duplicated");
        }
        requireExactFields(live, ALLOWED_LIVE_FIELDS, "live attestation");
        requireEquals(1, live.path("schemaVersion").asInt(),
                "live attestation schema version");
        requireEquals("LIVE_ATTESTED", live.path("status").asText(),
                "live attestation status");
        requireEquals(RUN_UUID, live.path("runUuid").asText(), "live attestation run UUID");
        requireEquals(custodySha256, live.path("custodySha256").asText(),
                "live custody digest");
        requireEquals(recoverySha256, live.path("recoveryCustodySha256").asText(),
                "live recovery custody digest");
        requireEquals(recoveryEvidenceSha256,
                live.path("recoveryEvidenceSha256").asText(),
                "live recovery evidence digest");
        requireEquals(ORIGINAL_CONTAINER_ID, live.path("originalContainerId").asText(),
                "live original container");
        requireEquals(manifest.containerId(), live.path("activeContainerId").asText(),
                "live active container");
        if (networkId == null || !CONTAINER_ID.matcher(networkId).matches()) {
            requireEquals(live.path("networkId").asText(), networkId,
                    "live recovered network");
            throw refuse("the live attestation lacks a valid recovered network identity");
        }
        requireEquals(networkId, live.path("networkId").asText(),
                "live recovered network");
        requireEquals(manifest.containerServerAddress(),
                live.path("containerServerAddress").asText(), "live server address");
        requireEquals(manifest.pgSystemIdentifier(),
                live.path("pgSystemIdentifier").asText(), "live system identifier");

        JsonNode tunnel = live.path("tunnel");
        requireExactFields(tunnel, ALLOWED_TUNNEL_FIELDS, "live tunnel attestation");
        requireEquals("SSH_LOCAL_FORWARD", tunnel.path("mode").asText(),
                "live tunnel mode");
        requireEquals(HOST + ":" + HOST_PORT, tunnel.path("listener").asText(),
                "live tunnel listener");
        requireEquals(HOST + ":" + HOST_PORT, tunnel.path("target").asText(),
                "live tunnel target");
        requireEquals(manifest.containerServerAddress(),
                tunnel.path("containerAddress").asText(), "live tunnel peer");
        requireEquals(manifest.containerId(), tunnel.path("containerId").asText(),
                "live tunnel container");
        requireEquals(networkId, tunnel.path("networkId").asText(),
                "live tunnel network");
        if (!tunnel.path("dockerPublishedPorts").isObject()
                || tunnel.path("dockerPublishedPorts").size() != 1
                || !tunnel.path("dockerPublishedPorts").path("5432/tcp").isNull()) {
            throw refuse("the live tunnel must replace the Docker host-port mapping");
        }
        if (!tunnel.path("ephemeral").asBoolean()
                || tunnel.path("pid").asInt() <= 1) {
            throw refuse("the live tunnel process facts are invalid");
        }
        if (tunnel.path("uid").asLong() != expectedOwnerUid) {
            throw refuse("the live tunnel UID differs from the custody owner");
        }
        Instant attestedAt;
        try {
            attestedAt = OffsetDateTime.parse(live.path("attestedAt").asText()).toInstant();
        } catch (DateTimeParseException exception) {
            throw refuse("the live attestation timestamp is malformed");
        }
        Duration age = Duration.between(attestedAt, now);
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(10)) > 0) {
            throw refuse("the live attestation is stale or from the future");
        }
    }

    private static Path latestLiveAttestation() {
        Path directory = Path.of(RUN_ROOT, "evidence", "live-attestations");
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("the append-only live-attestation directory is unavailable");
        }
        requireOwnerAndMode(directory, 0700, "live-attestation directory");
        List<Path> candidates = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            entries.filter(path -> LIVE_ATTESTATION_FILE.matcher(
                            path.getFileName().toString()).matches())
                    .forEach(candidates::add);
        } catch (IOException | SecurityException exception) {
            throw refuse("the live-attestation directory could not be read");
        }
        if (candidates.isEmpty()) {
            throw refuse("no append-only live attestation exists");
        }
        candidates.sort((left, right) -> right.getFileName().toString()
                .compareTo(left.getFileName().toString()));
        Path latest = candidates.get(0);
        if (Files.isSymbolicLink(latest)
                || !Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("the latest live attestation is not a regular file");
        }
        return latest;
    }

    private static void requireLiveAttestation(
            Path livePath,
            String custodyContent,
            Path recoveryPath,
            Path recoveryEvidencePath,
            Manifest manifest,
            String networkId) {
        requirePrivateFile(livePath, "live attestation");
        String live;
        try {
            live = Files.readString(livePath, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw refuse("the live attestation could not be read safely");
        }
        String recoverySha = "0".repeat(64);
        String evidenceSha = "0".repeat(64);
        if (Files.exists(recoveryPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                recoverySha = sha256(Files.readAllBytes(recoveryPath));
                evidenceSha = sha256(Files.readAllBytes(recoveryEvidencePath));
            } catch (IOException | SecurityException exception) {
                throw refuse("recovery evidence bytes could not be bound to live attestation");
            }
        }
        validateLiveAttestation(
                live,
                manifest,
                networkId,
                sha256(custodyContent.getBytes(StandardCharsets.UTF_8)),
                recoverySha,
                evidenceSha,
                Instant.now());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw refuse("SHA-256 is unavailable");
        }
    }

    public static void verifyConnection(Connection connection, Target target) throws SQLException {
        try {
            verifyConnectionFacts(connection, target);
        } catch (RefusedException exception) {
            throw new SQLException(exception.getMessage(), exception);
        }
    }

    /**
     * Requires the Gate I migrator's exact source/history completion receipt before runtime
     * configuration can create a pool or start Spring.
     */
    public static MigrationReceipt requireMigrationReceipt(Target target) {
        Objects.requireNonNull(target, "target");
        if (target.cohort() != Cohort.BUSINESS || !RUNTIME_ROLE.equals(target.role())) {
            throw refuse("only the business runtime can consume a migration completion receipt");
        }
        Path receiptPath = Path.of(RUN_ROOT, "evidence", "migrator-v60-completion.json");
        requirePrivateFile(receiptPath, "migration completion receipt");
        MigrationReceipt receipt;
        try {
            JsonNode root = JSON.readTree(Files.readString(receiptPath, StandardCharsets.UTF_8));
            requireExactFields(root, ALLOWED_RECEIPT_FIELDS, "migration completion receipt");
            requireExactFields(root.path("sourcePins"), ALLOWED_SOURCE_FIELDS,
                    "receipt source pins");
            receipt = JSON.treeToValue(root, MigrationReceipt.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw refuse("the migration completion receipt is malformed or duplicated");
        }
        validateReceipt(receipt, target);
        return receipt;
    }

    static void validateReceipt(MigrationReceipt receipt, Target target) {
        requireEquals(1, receipt.schemaVersion(), "receipt schema version");
        requireEquals("I", receipt.stage(), "receipt stage");
        requireEquals(RUN_UUID, receipt.runUuid(), "receipt run identity");
        requireEquals(target.manifest().approvalManifestSha256(), receipt.manifestSha256(),
                "receipt manifest digest");
        requireEquals(target.cohort().key(), receipt.cohort(), "receipt cohort");
        requireEquals(target.database(), receipt.database(), "receipt database");
        requireEquals(MIGRATOR_ROLE, receipt.migratorRole(), "receipt migrator role");
        requireEquals("public", receipt.applicationSchema(), "receipt application schema");
        requireEquals(1, receipt.baselineVersion(), "receipt baseline version");
        requireEquals(60, receipt.targetVersion(), "receipt target version");
        requireEquals(59, receipt.migrationCount(), "receipt migration count");
        if (!receipt.historyValidated()) {
            throw refuse("migration history was not validated");
        }
        requireEquals(target.manifest().custodianReference(), receipt.custodianReference(),
                "receipt custodian reference");
        receipt.sourcePins().requireApprovedPins();
        requireEquals(V60_MIGRATIONS_SHA256, receipt.currentMigrationsSha256(),
                "receipt current migration manifest");
        requireEquals(V58_SHA256, receipt.v58Sha256(), "receipt V58 source");
        requireEquals(V59_SHA256, receipt.v59Sha256(), "receipt V59 source");
        requireEquals(V60_SHA256, receipt.v60Sha256(), "receipt V60 source");
        if (receipt.completedAt() == null || receipt.completedAt().isBlank()) {
            throw refuse("migration completion timestamp is absent");
        }
    }

    private static void verifyConnectionFacts(Connection connection, Target target)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(target, "target");
        Manifest manifest = target.manifest();
        try (Statement settings = connection.createStatement();
             ResultSet timeouts = settings.executeQuery(
                     "select current_setting('statement_timeout'), "
                             + "current_setting('lock_timeout')")) {
            if (!timeouts.next()) {
                throw refuse("timeout binding facts were unavailable");
            }
            requireEquals("15s", timeouts.getString(1), "statement timeout");
            requireEquals("5s", timeouts.getString(2), "lock timeout");
        }
        boolean migrator = MIGRATOR_ROLE.equals(target.role());
        boolean metadata = METADATA_ROLE.equals(target.role());
        boolean runtime = RUNTIME_ROLE.equals(target.role());
        if (!(migrator || metadata || runtime)) {
            throw refuse("the selected role is not an approved fresh guard role");
        }
        String cleanupPlanCount = migrator || metadata
                ? "(select count(*) from it_control.cleanup_plan)"
                : "0";
        String sql = """
                select current_database() as database,
                       session_user as session_user,
                       current_user as current_user,
                       current_schema() as current_schema,
                       current_setting('search_path') as search_path,
                       coalesce(host(inet_server_addr()), '') as server_address,
                       coalesce(inet_server_port(), 0) as server_port,
                       (select system_identifier::text from pg_control_system())
                           as server_system_identifier,
                       (select pg_get_userbyid(datdba) from pg_database
                         where datname=current_database()) as database_owner,
                       (select pg_get_userbyid(nspowner) from pg_namespace
                         where nspname='public') as public_schema_owner,
                       (select count(*) from it_control.run_identity)
                           as run_identity_count,
                       (select count(*) from it_control.schema_bindings
                         where cohort_name=current_database()) as binding_count,
                       %s as cleanup_plan_count,
                       (select count(*) from pg_extension ext
                          join pg_namespace nsp on nsp.oid=ext.extnamespace
                         where nsp.nspname='it_extensions'
                           and ext.extname in ('pg_trgm','btree_gist'))
                           as extension_count,
                       (select rolsuper from pg_roles where rolname=current_user)
                           as current_super,
                       (select rolcreatedb from pg_roles where rolname=current_user)
                           as current_createdb,
                       (select rolcreaterole from pg_roles where rolname=current_user)
                           as current_createrole,
                       (select rolinherit from pg_roles where rolname=current_user)
                           as current_inherit,
                       (select rolcanlogin from pg_roles where rolname=current_user)
                           as current_login,
                       (select rolcanlogin from pg_roles
                         where rolname='itp_20261025_01_cleanup') as cleanup_login,
                       (select rolsuper from pg_roles
                         where rolname='itp_20261025_01_cleanup') as cleanup_super,
                       (select count(*) from pg_auth_members member
                          join pg_roles selected_role on selected_role.oid=member.member
                         where selected_role.rolname=current_user) as membership_count,
                       has_database_privilege(current_user, current_database(), 'TEMP')
                           as database_temp,
                       has_database_privilege(current_user, current_database(), 'CREATE')
                           as database_create,
                       has_database_privilege(current_user, current_database(),
                           'CREATE WITH GRANT OPTION') as database_create_grant,
                       has_schema_privilege(current_user, 'public', 'USAGE')
                           as public_schema_usage,
                       has_schema_privilege(current_user, 'public', 'CREATE')
                           as public_schema_create,
                       has_schema_privilege(current_user, 'it_control', 'USAGE')
                           as control_schema_usage,
                       has_schema_privilege(current_user, 'it_control', 'CREATE')
                           as control_schema_create,
                       has_schema_privilege(current_user, 'it_control',
                           'CREATE WITH GRANT OPTION') as control_schema_create_grant,
                       has_schema_privilege(current_user, 'it_extensions', 'USAGE')
                           as extensions_schema_usage,
                       has_schema_privilege(current_user, 'it_extensions', 'CREATE')
                           as extensions_schema_create,
                       has_table_privilege(current_user,
                           'it_control.run_identity', 'SELECT') as run_select,
                       has_table_privilege(current_user,
                           'it_control.schema_bindings', 'SELECT') as binding_select,
                       has_table_privilege(current_user,
                           'it_control.cleanup_plan', 'SELECT') as cleanup_select,
                       has_table_privilege('itp_20261025_01_migrator',
                           'it_control.cleanup_plan',
                           'SELECT WITH GRANT OPTION') as migrator_cleanup_grant,
                       has_function_privilege('itp_20261025_01_cleanup',
                           'it_control.refuse_immutable_control_mutation()',
                           'EXECUTE') as cleanup_function_execute,
                       has_database_privilege(current_user,
                           'pathome_it_20261025_01_clean', 'CONNECT') as clean_connect,
                       has_database_privilege(current_user,
                           'pathome_it_20261025_01_upgrade', 'CONNECT') as upgrade_connect,
                       has_database_privilege(current_user,
                           'pathome_it_20261025_01_business', 'CONNECT') as business_connect,
                       has_database_privilege('itp_20261025_01_cleanup',
                           current_database(), 'CONNECT') as cleanup_connect,
                       coalesce((select run_uuid::text from it_control.run_identity
                         limit 1), '') as run_uuid,
                       coalesce((select disposable_marker from it_control.run_identity
                         limit 1), '') as disposable_marker,
                       coalesce((select cohort_name from it_control.run_identity
                         limit 1), '') as cohort_name,
                       coalesce((select manifest_sha256 from it_control.run_identity
                         limit 1), '') as manifest_sha256,
                       coalesce((select provisioning_state from it_control.run_identity
                         limit 1), '') as provisioning_state,
                       coalesce((select container_id::text from it_control.run_identity
                         limit 1), '') as sentinel_container_id
                """.formatted(cleanupPlanCount);
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw refuse("connection binding facts were unavailable");
            }
            requireEquals(target.database(), row.getString("database"), "database");
            requireEquals(target.role(), row.getString("session_user"), "session_user");
            requireEquals(target.role(), row.getString("current_user"), "current_user");
            if (metadata) {
                requireEquals("it_extensions", row.getString("current_schema"),
                        "metadata current schema");
            } else {
                requireEquals("public", row.getString("current_schema"), "current_schema");
            }
            if (!canonicalSearchPath(row.getString("search_path"))
                    .equals("public,it_extensions,pg_catalog")) {
                throw refuse("search_path is not the exact approved runtime/migration path");
            }
            requireEquals(manifest.containerServerAddress(),
                    row.getString("server_address"), "server address");
            requireEquals(manifest.containerServerPort(),
                    row.getInt("server_port"), "server port");
            requireEquals(manifest.pgSystemIdentifier(),
                    row.getString("server_system_identifier"), "server system identifier");
            if (target.networkId() != null) {
                if (!CONTAINER_ID.matcher(target.networkId()).matches()) {
                    throw refuse("target recovery network ID is malformed");
                }
            }
            requireEquals(DATABASE_OWNER_ROLE,
                    row.getString("database_owner"), "database owner");
            requireEquals(MIGRATOR_ROLE,
                    row.getString("public_schema_owner"), "public schema owner");
            requireEquals(1, row.getInt("run_identity_count"), "run identity row count");
            requireEquals(3, row.getInt("binding_count"), "schema binding row count");
            requireEquals(0, row.getInt("cleanup_plan_count"), "cleanup plan row count");
            requireEquals(2, row.getInt("extension_count"), "extension count");
            if (row.getBoolean("current_super") || row.getBoolean("current_createdb")
                    || row.getBoolean("current_createrole")
                    || row.getBoolean("current_inherit")
                    || !row.getBoolean("current_login")) {
                throw refuse("the selected role has forbidden cluster attributes");
            }
            if (row.getBoolean("cleanup_login") || row.getBoolean("cleanup_super")
                    || row.getInt("membership_count") != 0) {
                throw refuse("cleanup login or responsibility-role membership is active");
            }
            requireEquals(migrator, row.getBoolean("database_temp"), "TEMP privilege");
            requireEquals(!metadata, row.getBoolean("public_schema_usage"),
                    "public schema USAGE");
            requireEquals(migrator, row.getBoolean("public_schema_create"),
                    "public schema CREATE privilege");
            for (String forbidden : List.of(
                    "database_create", "database_create_grant",
                    "control_schema_create", "control_schema_create_grant",
                    "extensions_schema_create", "migrator_cleanup_grant",
                    "cleanup_function_execute", "cleanup_connect")) {
                if (row.getBoolean(forbidden)) {
                    throw refuse("selected role has forbidden privilege " + forbidden);
                }
            }
            requireEquals(true, row.getBoolean("control_schema_usage"),
                    "control schema USAGE");
            requireEquals(true, row.getBoolean("extensions_schema_usage"),
                    "extension schema USAGE");
            requireEquals(true, row.getBoolean("run_select"), "run identity SELECT");
            requireEquals(true, row.getBoolean("binding_select"), "schema binding SELECT");
            requireEquals(!runtime, row.getBoolean("cleanup_select"),
                    "cleanup plan SELECT");
            requireEquals(migrator || metadata,
                    row.getBoolean("clean_connect"), "clean CONNECT privilege");
            requireEquals(migrator || metadata,
                    row.getBoolean("upgrade_connect"), "upgrade CONNECT privilege");
            requireEquals(true, row.getBoolean("business_connect"),
                    "business CONNECT privilege");
            if (runtime && (row.getBoolean("clean_connect")
                    || row.getBoolean("upgrade_connect"))) {
                throw refuse("runtime can connect beyond the business cohort");
            }
            requireEquals(RUN_UUID, row.getString("run_uuid"), "run identity");
            requireEquals(MARKER, row.getString("disposable_marker"), "disposable marker");
            requireEquals(target.database(), row.getString("cohort_name"), "cohort binding");
            requireEquals(manifest.approvalManifestSha256(),
                    row.getString("manifest_sha256"),
                    "approval manifest digest");
            requireEquals("PREPARED", row.getString("provisioning_state"),
                    "provisioning state");
            requireEquals(ORIGINAL_CONTAINER_ID, row.getString("sentinel_container_id"),
                    "immutable original container provenance");
        }
    }

    private static void validateManifest(Manifest manifest, Stage requestedStage) {
        if (manifest.schemaVersion() != 1) {
            throw refuse("manifest schemaVersion must be 1");
        }
        requireEquals("PREPARED", manifest.provisioningState(), "provisioning state");
        if (!manifest.permittedStages().contains(requestedStage.code())) {
            throw refuse("the manifest does not authorize stage " + requestedStage.code());
        }
        if (!manifest.permittedStages().equals(List.of("A", "I"))) {
            throw refuse("manifest stages must be exactly Gate A and Gate I");
        }
        requireEquals(RUN_NAME, manifest.runName(), "run name");
        requireEquals(RUN_UUID, manifest.runUuid(), "run UUID");
        try {
            UUID.fromString(manifest.runUuid());
        } catch (IllegalArgumentException exception) {
            throw refuse("run UUID is malformed");
        }
        requireEquals(CONTAINER, manifest.container(), "container");
        requireEquals(NETWORK, manifest.network(), "network");
        requireEquals(VOLUME, manifest.volume(), "volume");
        requireEquals(HOST, manifest.host(), "host");
        requireEquals(HOST_PORT, manifest.port(), "host port");
        requireEquals(CONTAINER_PORT, manifest.containerServerPort(), "container server port");
        if (!isIpv4(manifest.containerServerAddress())
                || manifest.containerServerAddress().equals(HOST)) {
            throw refuse("container server address must be the observed non-host container address");
        }
        if (manifest.containerId() == null || !CONTAINER_ID.matcher(manifest.containerId()).matches()) {
            throw refuse("container ID must be the observed 64-character Docker ID");
        }
        requireEquals(ORIGINAL_CONTAINER_ID, manifest.containerId(),
                "immutable original container identity");
        if (manifest.imageDigest() == null || !IMAGE_DIGEST.matcher(manifest.imageDigest()).matches()) {
            throw refuse("image digest must be one immutable sha256 digest");
        }
        requireEquals(APPROVED_IMAGE_DIGEST, manifest.imageDigest(),
                "owner-approved image digest");
        if (manifest.volumeId() == null || manifest.volumeId().isBlank()
                || manifest.pgSystemIdentifier() == null
                || !manifest.pgSystemIdentifier().matches("^[0-9]{10,20}$")) {
            throw refuse("volume and PostgreSQL system identities are required");
        }
        if (manifest.serverVersion() == null
                || !manifest.serverVersion().matches("^16\\.[0-9]+.*$")) {
            throw refuse("server version must identify the approved PostgreSQL 16 family");
        }
        requireDigest(manifest.approvalManifestSha256(), "approval manifest digest");
        requireEquals(APPROVAL_MANIFEST_SHA256, manifest.approvalManifestSha256(),
                "static approval manifest");
        if (manifest.custodianReference() == null || manifest.custodianReference().isBlank()) {
            throw refuse("a nonsecret custodian/authority reference is required");
        }
        requireExactRoles(manifest.roles());
        requireExactCohorts(manifest.cohorts());
        manifest.sourcePins().requireApprovedPins();
    }

    private static void requireExactRoles(Map<String, String> roles) {
        requireEquals(PROVISIONER_ROLE, roles.get("provisioner"), "provisioner role");
        requireEquals(DATABASE_OWNER_ROLE, roles.get("databaseOwner"), "database owner role");
        requireEquals(METADATA_ROLE, roles.get("metadata"), "metadata role");
        requireEquals(MIGRATOR_ROLE, roles.get("migrator"), "migrator role");
        requireEquals(RUNTIME_ROLE, roles.get("runtime"), "runtime role");
        requireEquals(CLEANUP_ROLE, roles.get("cleanup"), "cleanup role");
        for (String role : roles.values()) {
            if (role == null || !ROLE_NAME.matcher(role).matches()) {
                throw refuse("a manifest role name is outside the approved naming policy");
            }
        }
    }

    private static void requireExactCohorts(Map<String, String> cohorts) {
        requireEquals(CLEAN_DATABASE, cohorts.get("clean"), "clean cohort");
        requireEquals(UPGRADE_DATABASE, cohorts.get("upgrade"), "upgrade cohort");
        requireEquals(BUSINESS_DATABASE, cohorts.get("business"), "business cohort");
        for (String database : cohorts.values()) {
            if (database == null || !DATABASE_NAME.matcher(database).matches()) {
                throw refuse("a manifest database name is outside the approved naming policy");
            }
        }
    }

    private static Selection select(Manifest manifest, Stage requestedStage, Cohort cohort,
                                    String requiredRole) {
        Objects.requireNonNull(cohort, "cohort");
        String database = manifest.cohorts().get(cohort.key());
        requireEquals(cohort.database(), database, "selected cohort database");
        String defaultRole = switch (requestedStage) {
            case PREPARATION -> MIGRATOR_ROLE;
            case ATTESTATION -> METADATA_ROLE;
            case INTEGRATION -> cohort == Cohort.BUSINESS ? RUNTIME_ROLE : MIGRATOR_ROLE;
        };
        if (requiredRole == null || !requiredRole.equals(defaultRole)) {
            boolean attestationRoleProbe = requestedStage == Stage.ATTESTATION
                    && (MIGRATOR_ROLE.equals(requiredRole)
                    || (RUNTIME_ROLE.equals(requiredRole) && cohort == Cohort.BUSINESS));
            boolean integrationMigrator = requestedStage == Stage.INTEGRATION
                    && MIGRATOR_ROLE.equals(requiredRole);
            if (!attestationRoleProbe && !integrationMigrator) {
                throw refuse("the requested role is not allowed for this stage and cohort");
            }
        }
        if (requestedStage == Stage.INTEGRATION && RUNTIME_ROLE.equals(requiredRole)
                && cohort != Cohort.BUSINESS) {
            throw refuse("the runtime integration client is bound only to the business cohort");
        }
        Path secret = Path.of(RUN_ROOT, "secrets", requiredRole + ".password").normalize();
        return new Selection(manifest, cohort, database, requiredRole, secret);
    }

    private static String defaultRole(Stage requestedStage, Cohort cohort) {
        return switch (requestedStage) {
            case PREPARATION -> MIGRATOR_ROLE;
            case ATTESTATION -> METADATA_ROLE;
            case INTEGRATION -> cohort == Cohort.BUSINESS ? RUNTIME_ROLE : MIGRATOR_ROLE;
        };
    }

    private static void requireCleanEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw refuse("no environment was supplied");
        }
        for (String name : FORBIDDEN_ENVIRONMENT_NAMES) {
            if (environment.containsKey(name)) {
                throw refuse("inherited test/database/service setting " + name + " is forbidden");
            }
        }
    }

    private static void requirePrivateFile(Path path, String label) {
        try {
            Path current = path.getRoot();
            for (Path component : path) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw refuse(label + " path contains a symlink component");
                }
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw refuse(label + " is absent, a symlink, or not a regular file");
            }
            if (Files.size(path) > 65_536) {
                throw refuse(label + " exceeds the bounded nonsecret-file size");
            }
            Path parent = path.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw refuse(label + " parent directory is unavailable");
            }
            Set<PosixFilePermission> parentPermissions = Files.getPosixFilePermissions(
                    parent, LinkOption.NOFOLLOW_LINKS);
            if (parentPermissions.contains(PosixFilePermission.GROUP_READ)
                    || parentPermissions.contains(PosixFilePermission.OTHERS_READ)
                    || parentPermissions.contains(PosixFilePermission.GROUP_WRITE)
                    || parentPermissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw refuse(label + " parent directory is not private");
            }
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> allowed = PosixFilePermissions.fromString("rw-------");
            if (!allowed.containsAll(actual)
                    || actual.contains(PosixFilePermission.GROUP_READ)
                    || actual.contains(PosixFilePermission.OTHERS_READ)
                    || actual.contains(PosixFilePermission.GROUP_WRITE)
                    || actual.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw refuse(label + " permissions are broader than 0600");
            }
            Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Object currentUid = Files.getAttribute(
                    Path.of(RUN_ROOT, "custody.json"), "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(owner, currentUid)) {
                throw refuse(label + " is not owned by the custody manifest owner");
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw refuse(label + " ownership or mode could not be verified");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
        if (node == null || !node.isObject()) {
            throw refuse(label + " must be a JSON object");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw refuse(label + " fields do not exactly match the approved schema");
        }
    }

    private static Map<String, String> immutableCopy(Map<String, String> input) {
        if (input == null) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw refuse(label + " is not a lowercase SHA-256 digest");
        }
    }

    private static void requireEquals(Object expected, Object actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw refuse(field + " does not match the approved binding");
        }
    }

    private static boolean isIpv4(String value) {
        if (value == null || !value.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) {
            return false;
        }
        for (String part : value.split("\\.")) {
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static String canonicalSearchPath(String searchPath) {
        if (searchPath == null) return "";
        String[] parts = searchPath.split(",");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) normalized.add(value);
        }
        return String.join(",", normalized);
    }

    private static RefusedException refuse(String reason) {
        return new RefusedException(reason);
    }

    public static final class RefusedException extends IllegalStateException {
        public RefusedException(String reason) {
            super("REFUSED fresh integration boundary: " + reason);
        }
    }

    public static final class StrictConnectionPolicy implements ConnectionPolicy {
        private final Target target;

        private StrictConnectionPolicy(Target target) {
            this.target = target;
        }

        @Override
        public void prepare(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set statement_timeout to 15000");
                statement.execute("set lock_timeout to 5000");
                statement.execute("set search_path to public, it_extensions, pg_catalog");
            }
            verify(connection);
        }

        @Override
        public void beforeStatement(Connection connection) throws SQLException {
            verify(connection);
        }

        private void verify(Connection connection) throws SQLException {
            try {
                FreshIntegrationTarget.verifyConnection(connection, target);
            } catch (RefusedException exception) {
                throw new SQLException(exception.getMessage(), exception);
            }
        }
    }

    /** Convenience factory used by future explicitly approved integration configuration. */
    public static DataSource address(DataSource guarded, Target target) {
        return new RunOwnedSchemaDataSource(guarded, target.policy());
    }
}
