package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Database-free launcher for the genuine Lead browser runner.
 *
 * The child receives only approved runtime variables and the explicit fixture contract. No
 * inherited PATHOME_* value can redirect the runner. Fixture-control resources are closed on every
 * exit path, including admission refusal, timeout, interruption and launch failure.
 */
public final class DbA01BrowserLauncher {
    public static final List<String> REQUIRED_FIXTURE_ENVIRONMENT = List.of(
            "PATHOME_BROWSER_BACKEND_PORT",
            "PATHOME_BROWSER_OWNER_TOKEN",
            "PATHOME_BROWSER_OWNER_ID",
            "PATHOME_BROWSER_OWNER_GRANT_ID",
            "PATHOME_BROWSER_GRANT_REVOKE_URL",
            "PATHOME_BROWSER_GRANT_REVOKE_TOKEN",
            "PATHOME_BROWSER_PEER_TOKEN",
            "PATHOME_BROWSER_PEER_ID",
            "PATHOME_BROWSER_SUPERVISOR_TOKEN",
            "PATHOME_BROWSER_SUPERVISOR_ID",
            "PATHOME_BROWSER_ADMIN_TOKEN",
            "PATHOME_BROWSER_ADMIN_ID",
            "PATHOME_BROWSER_EXPECTED_PROPERTY_ID",
            "PATHOME_BROWSER_EVIDENCE_DIR"
    );

    private static final List<String> OPTIONAL_FIXTURE_ENVIRONMENT = List.of(
            "PATHOME_BROWSER_CHROMIUM_EXECUTABLE",
            "PATHOME_BROWSER_STRICT_GENUINE",
            "PATHOME_BROWSER_INTAKE_TOKEN",
            "PATHOME_BROWSER_INTAKE_ID",
            "PATHOME_BROWSER_INTAKE_LEAD_ID"
    );

    private static final List<String> RUNTIME_ENVIRONMENT = List.of(
            "PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ"
    );

    private DbA01BrowserLauncher() {}

    public static DbA01BrowserProcess.Result launch(
            Path repository,
            Path script,
            Map<String, String> fixtureEnvironment,
            AutoCloseable fixtureControl,
            Duration timeout
    ) throws IOException, InterruptedException {
        return launchWithBuilderFactory(
                repository,
                script,
                fixtureEnvironment,
                fixtureControl,
                timeout,
                candidate -> new ProcessBuilder("node", candidate.toString())
        );
    }

    static DbA01BrowserProcess.Result launchWithBuilderFactory(
            Path repository,
            Path script,
            Map<String, String> fixtureEnvironment,
            AutoCloseable fixtureControl,
            Duration timeout,
            ProcessBuilderFactory builderFactory
    ) throws IOException, InterruptedException {
        return withControl(fixtureControl, () -> {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(script, "script");
            if (!Files.isDirectory(repository)) {
                throw new IllegalStateException("Browser working directory is unavailable");
            }
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("Browser runner is missing");
            }
            ProcessBuilder builder = Objects.requireNonNull(
                    builderFactory.create(script),
                    "process builder"
            );
            builder.directory(repository.toFile());
            return executeSanitized(builder, fixtureEnvironment, timeout);
        });
    }

    static DbA01BrowserProcess.Result runSanitized(
            ProcessBuilder builder,
            Map<String, String> fixtureEnvironment,
            AutoCloseable fixtureControl,
            Duration timeout
    ) throws IOException, InterruptedException {
        return withControl(
                fixtureControl,
                () -> executeSanitized(builder, fixtureEnvironment, timeout)
        );
    }

    private static DbA01BrowserProcess.Result executeSanitized(
            ProcessBuilder builder,
            Map<String, String> fixtureEnvironment,
            Duration timeout
    ) throws IOException, InterruptedException {
        Map<String, String> fixtures = validateAndCopy(fixtureEnvironment);
        sanitizeEnvironment(builder, fixtures);
        return DbA01BrowserProcess.run(builder, timeout);
    }

    private static DbA01BrowserProcess.Result withControl(
            AutoCloseable fixtureControl,
            LaunchAction action
    ) throws IOException, InterruptedException {
        Throwable primary = null;
        try {
            return action.run();
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            closeControl(fixtureControl, primary);
        }
    }

    @FunctionalInterface
    private interface LaunchAction {
        DbA01BrowserProcess.Result run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ProcessBuilderFactory {
        ProcessBuilder create(Path script);
    }

    private static Map<String, String> validateAndCopy(Map<String, String> fixtureEnvironment) {
        if (fixtureEnvironment == null) {
            throw new IllegalStateException("Browser fixture environment is missing");
        }
        for (String key : REQUIRED_FIXTURE_ENVIRONMENT) {
            String value = fixtureEnvironment.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Browser fixture environment is missing " + key);
            }
        }
        for (String key : fixtureEnvironment.keySet()) {
            if (!REQUIRED_FIXTURE_ENVIRONMENT.contains(key)
                    && !OPTIONAL_FIXTURE_ENVIRONMENT.contains(key)) {
                throw new IllegalStateException(
                        "Browser fixture environment contains an unapproved key " + key);
            }
        }
        return Map.copyOf(fixtureEnvironment);
    }

    private static void sanitizeEnvironment(
            ProcessBuilder builder,
            Map<String, String> fixtureEnvironment
    ) {
        Map<String, String> child = new HashMap<>();
        for (String key : RUNTIME_ENVIRONMENT) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) child.put(key, value);
        }
        child.putAll(fixtureEnvironment);
        builder.environment().clear();
        builder.environment().putAll(child);
    }

    private static void closeControl(AutoCloseable fixtureControl, Throwable primary) {
        if (fixtureControl == null) return;
        try {
            fixtureControl.close();
        } catch (Exception closeFailure) {
            if (primary != null) {
                primary.addSuppressed(closeFailure);
                return;
            }
            throw new IllegalStateException("Browser fixture control did not close", closeFailure);
        }
    }
}
