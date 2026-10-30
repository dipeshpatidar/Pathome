package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DbA01BrowserProcessTest {
    @TempDir Path temporary;

    private ProcessBuilder child(Path target, String tail) throws Exception {
        Path repo = Path.of(System.getProperty("pathome.repository", System.getProperty("user.dir"))).toAbsolutePath();
        if (repo.getFileName().toString().equals("backend")) repo = repo.getParent();
        Path module = repo.resolve("frontend-web/browser-verification/operations-leads/evidence-directory.mjs");
        String code = "import {claimEvidenceDirectory} from '" + module.toUri() + "';"
                + "import {writeFile} from 'node:fs/promises';"
                + "const c=await claimEvidenceDirectory(process.argv[1]);"
                + "await writeFile(c.path+'/browser-results.json','{\"passed\":true}');"
                + "await writeFile(c.path+'/screenshot.png','fake-process fixture bytes');" + tail;
        ProcessBuilder builder = new ProcessBuilder("node", "--input-type=module", "-e", code, target.toString());
        builder.environment().put("PATHOME_BROWSER_EVIDENCE_DIR", target.toString());
        return builder;
    }

    private Map<String, String> snapshot(Path target) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(target)) {
            for (Path path : files.toList()) {
                String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path) ? "directory" : HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                result.put(target.relativize(path).toString(), value);
            }
        }
        System.out.println("FS_SNAPSHOT " + target + " " + result);
        return result;
    }

    private Map<String, String> fixtureEnvironment(Path evidence) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATHOME_BROWSER_BACKEND_PORT", "18080");
        environment.put("PATHOME_BROWSER_OWNER_TOKEN", "explicit-owner-token");
        environment.put("PATHOME_BROWSER_OWNER_ID", "91");
        environment.put("PATHOME_BROWSER_OWNER_GRANT_ID", "401");
        environment.put("PATHOME_BROWSER_GRANT_REVOKE_URL", "http://127.0.0.1:18081/revoke");
        environment.put("PATHOME_BROWSER_GRANT_REVOKE_TOKEN", "explicit-control-token");
        environment.put("PATHOME_BROWSER_PEER_TOKEN", "explicit-peer-token");
        environment.put("PATHOME_BROWSER_PEER_ID", "92");
        environment.put("PATHOME_BROWSER_SUPERVISOR_TOKEN", "explicit-supervisor-token");
        environment.put("PATHOME_BROWSER_SUPERVISOR_ID", "93");
        environment.put("PATHOME_BROWSER_ADMIN_TOKEN", "explicit-admin-token");
        environment.put("PATHOME_BROWSER_ADMIN_ID", "94");
        environment.put("PATHOME_BROWSER_EXPECTED_PROPERTY_ID", "71");
        environment.put("PATHOME_BROWSER_STRICT_GENUINE", "true");
        environment.put("PATHOME_BROWSER_EVIDENCE_DIR", evidence.toString());
        return environment;
    }

    private Map<String, String> fixtureEnvironmentFor(ProcessBuilder builder) {
        String evidence = builder.environment().get("PATHOME_BROWSER_EVIDENCE_DIR");
        return fixtureEnvironment(Path.of(evidence == null
                ? temporary.resolve("launcher-" + UUID.randomUUID()).toString()
                : evidence));
    }

    private static AutoCloseable countingControl(AtomicInteger closes) {
        return closes::incrementAndGet;
    }

    @Test void freshAndSequentialRunsKeepExternalLogsAndPreservePriorBytes() throws Exception {
        Path a = temporary.toRealPath().resolve("a");
        var one = run(child(a, "console.log('completed pid:'+process.pid);"));
        assertEquals(0, one.exitCode()); assertFalse(one.timedOut()); assertTrue(one.pid() > 0);
        assertFalse(one.log().startsWith(a)); assertTrue(Files.readString(one.log()).contains("completed"));
        assertTrue(Files.readString(one.log()).contains("pid:" + one.pid()));
        Files.writeString(a.resolve("SHA256SUMS"), "fake-process fixture manifest");
        var before = snapshot(a);
        var two = run(child(a.resolveSibling("b"), ""));
        assertEquals(0, two.exitCode()); assertNotEquals(one.log(), two.log());
        assertEquals(before, snapshot(a)); assertFalse(Files.exists(a.resolve("browser-run.log")));
    }

    @Test void collisionAfterPrecheckMustNeverWriteForeignDirectory() throws Exception {
        Path target = temporary.toRealPath().resolve("contested");
        assertFalse(Files.exists(target)); // Parent's precheck precedes the competing actor.
        ProcessBuilder builder = child(target, "");
        Files.createDirectory(target);
        Files.writeString(target.resolve("SHA256SUMS"), "foreign sealed manifest\n");
        var before = snapshot(target);
        var result = run(builder);
        // MUTATION_POINT: legacy unconditional parent copy is inserted here in isolated control.
        assertNotEquals(0, result.exitCode()); assertFalse(result.timedOut());
        assertEquals(before, snapshot(target));
        assertFalse(Files.exists(target.resolve("browser-run.log")));
        assertTrue(Files.readString(result.log()).contains("already exists"));
    }

    @Test void twoConcurrentParentsHaveExactlyOneOwnerAndOneRefusal() throws Exception {
        Path target = temporary.toRealPath().resolve("race");
        ProcessBuilder a = child(target, "console.log('winner');");
        ProcessBuilder b = child(target, "console.log('winner');");
        var first = CompletableFuture.supplyAsync(() -> run(a));
        var second = CompletableFuture.supplyAsync(() -> run(b));
        var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
        assertEquals(1, (one.exitCode() == 0 ? 1 : 0) + (two.exitCode() == 0 ? 1 : 0));
        assertEquals(Set.of("RUN_CLAIM.json", "browser-results.json", "screenshot.png"), snapshot(target).keySet().stream()
                .filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("{\"passed\":true}",Files.readString(target.resolve("browser-results.json")));
        var before = snapshot(target);
        assertNotEquals(0, run(child(target, "")).exitCode());
        assertEquals(before, snapshot(target));
    }

    @Test void existingPartialSealedMarkerAndSymlinkTargetsStayByteExact() throws Exception {
        for (String file : List.of("SHA256SUMS", "partial.png", "RUN_CLAIM.json")) {
            Path target = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString())).toRealPath();
            Files.writeString(target.resolve(file), "foreign bytes");
            var before = snapshot(target);
            assertNotEquals(0, run(child(target, "")).exitCode());
            assertEquals(before, snapshot(target));
        }
        Path real = Files.createDirectory(temporary.resolve("real")).toRealPath();
        Files.writeString(real.resolve("foreign"), "protected");
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, real);
        var before = snapshot(real);
        assertNotEquals(0, run(child(alias, "")).exitCode()); assertEquals(before, snapshot(real));
        Path blocker = temporary.resolve("blocked"); Files.writeString(blocker, "file");
        var blockedBefore = snapshot(blocker);
        assertNotEquals(0, run(child(blocker.resolve("child"), "")).exitCode());
        assertEquals(blockedBefore,snapshot(blocker));
        assertNotEquals(0, run(child(Path.of("relative-evidence"), "")).exitCode());
    }

    @Test void floodNonzeroTimeoutLaunchFailureAndInterruptionAreFailuresWithExternalReceipts() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("untouched")).toRealPath();
        Files.writeString(target.resolve("manifest"), "foreign"); var before = snapshot(target);
        var flood = run(new ProcessBuilder("node", "-e", "process.stdout.write('x'.repeat(2000000));process.stderr.write('y'.repeat(2000000));process.exitCode=7"));
        assertEquals(7, flood.exitCode()); assertTrue(Files.size(flood.log()) < 1100000);
        assertTrue(Files.readString(flood.log()).contains("OUTPUT_TRUNCATED"));
        var timeout = runWithTimeout(
                new ProcessBuilder("node", "-e", "setInterval(()=>{},1000)"),
                Duration.ofMillis(100));
        assertTrue(timeout.timedOut()); assertNotEquals(0, timeout.exitCode());
        assertThrows(java.io.IOException.class, () -> runChecked(new ProcessBuilder("/nonexistent/pathome-child")));
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread parent = new Thread(() -> { try { runWithTimeout(
                new ProcessBuilder("node", "-e", "setInterval(()=>{},1000)"),
                Duration.ofSeconds(20)); }
            catch (Throwable ex) { error.set(ex); } });
        parent.start(); Thread.sleep(150); parent.interrupt(); parent.join(7000);
        assertFalse(parent.isAlive()); assertInstanceOf(InterruptedException.class, error.get());
        assertEquals(before, snapshot(target));
    }

    @Test void invalidEvidenceSelectionAndReplacedPathsFailSafely() throws Exception {
        Path repo=temporary.toRealPath(); Path root=repo.resolve("tools/oe-integration-env/evidence");
        Files.createDirectories(root);
        for (String value : java.util.Arrays.asList(null,"","relative",root.toString(),repo.resolve("elsewhere").toString())) {
            assertThrows(IllegalStateException.class,()->DbA01BrowserProcess.requireFreshEvidencePath(repo,value));
        }
        Path target=root.resolve("fresh");
        assertEquals(target,DbA01BrowserProcess.requireFreshEvidencePath(repo,target.toString()));
        Path foreign=Files.createDirectory(repo.resolve("foreign"));
        Files.writeString(foreign.resolve("manifest"),"protected");
        var before=snapshot(foreign);
        Files.createSymbolicLink(target,foreign);
        assertThrows(IllegalStateException.class,()->DbA01BrowserProcess.requireFreshEvidencePath(repo,target.toString()));
        assertNotEquals(0,run(child(target,"")).exitCode());
        assertEquals(before,snapshot(foreign));

        Path dangling = root.resolve("dangling");
        Files.createSymbolicLink(dangling, root.resolve("missing-referent"));
        assertNotEquals(0, run(child(dangling, "")).exitCode());
        assertTrue(Files.isSymbolicLink(dangling));
        assertFalse(Files.exists(dangling.resolveSibling("missing-referent")));

        Path missingParent = root.resolve("missing-parent").resolve("fresh");
        assertNotEquals(0, run(child(missingParent, "")).exitCode());
        assertFalse(Files.exists(missingParent));
    }

    @Test void publicLaunchOwnsControlAcrossValidationAndExecution() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("public-repository")).toRealPath();
        Path script = repository.resolve("runner.mjs");
        Files.writeString(script,
                "import {writeFile} from 'node:fs/promises';"
                        + "await writeFile('child-started.txt','started');");
        Path evidence = temporary.toRealPath().resolve("public-evidence");
        AtomicInteger closes = new AtomicInteger();

        var result = DbA01BrowserLauncher.launch(
                repository, script, fixtureEnvironment(evidence),
                countingControl(closes), Duration.ofSeconds(5));

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(Files.isRegularFile(repository.resolve("child-started.txt")));
        assertEquals(1, closes.get());

        for (Map.Entry<String, Object[]> refusal : Map.of(
                "null repository", new Object[]{null, script},
                "missing repository", new Object[]{
                        repository.resolve("missing-repository"), script
                },
                "file repository", new Object[]{script, script},
                "null script", new Object[]{repository, null},
                "missing script", new Object[]{repository, repository.resolve("missing.mjs")},
                "directory script", new Object[]{repository, repository}
        ).entrySet()) {
            Path refusedEvidence = temporary.toRealPath()
                    .resolve("refused-" + UUID.randomUUID());
            AtomicInteger refusedCloses = new AtomicInteger();
            assertThrows(RuntimeException.class,
                    () -> DbA01BrowserLauncher.launch(
                            (Path) refusal.getValue()[0],
                            (Path) refusal.getValue()[1],
                            fixtureEnvironment(refusedEvidence),
                            countingControl(refusedCloses),
                            Duration.ofSeconds(1)),
                    refusal.getKey());
            assertEquals(1, refusedCloses.get(), refusal.getKey());
            assertFalse(Files.exists(refusedEvidence), refusal.getKey());
        }
    }

    @Test void publicLaunchRefusesFixtureAndClosesBeforeAnyChildWork() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("fixture-repository")).toRealPath();
        Path script = repository.resolve("runner.mjs");
        Files.writeString(script,
                "import {writeFile} from 'node:fs/promises';"
                        + "await writeFile('fixture-child-started.txt','started');");
        Path evidence = temporary.toRealPath().resolve("fixture-evidence");
        Map<String, String> complete = fixtureEnvironment(evidence);
        for (String key : DbA01BrowserLauncher.REQUIRED_FIXTURE_ENVIRONMENT) {
            Map<String, String> incomplete = new LinkedHashMap<>(complete);
            incomplete.remove(key);
            AtomicInteger closes = new AtomicInteger();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> DbA01BrowserLauncher.launch(
                            repository, script, incomplete,
                            countingControl(closes), Duration.ofSeconds(1)));
            assertTrue(failure.getMessage().contains(key), key);
            assertEquals(1, closes.get(), key);
            assertFalse(Files.exists(repository.resolve("fixture-child-started.txt")), key);
            assertFalse(Files.exists(evidence), key);
        }

        AtomicInteger constructionCloses = new AtomicInteger();
        IllegalStateException constructionFailure = assertThrows(
                IllegalStateException.class,
                () -> DbA01BrowserLauncher.launchWithBuilderFactory(
                        repository,
                        script,
                        complete,
                        countingControl(constructionCloses),
                        Duration.ofSeconds(1),
                        ignored -> {
                            throw new IllegalStateException("controlled builder construction failure");
                        }));
        assertTrue(constructionFailure.getMessage().contains("builder construction"));
        assertEquals(1, constructionCloses.get());
        assertFalse(Files.exists(repository.resolve("fixture-child-started.txt")));
    }

    @Test void publicLaunchClosesOnceForSuccessNonzeroTimeoutAndInterruption() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("outcome-repository")).toRealPath();
        Path successScript = repository.resolve("success.mjs");
        Files.writeString(successScript, "");

        AtomicInteger successCloses = new AtomicInteger();
        var success = DbA01BrowserLauncher.launch(
                repository, successScript,
                fixtureEnvironment(temporary.toRealPath().resolve("success-evidence")),
                countingControl(successCloses), Duration.ofSeconds(5));
        assertEquals(0, success.exitCode());
        assertEquals(1, successCloses.get());

        Path nonzeroScript = repository.resolve("nonzero.mjs");
        Files.writeString(nonzeroScript, "process.exitCode=7;");
        AtomicInteger nonzeroCloses = new AtomicInteger();
        var nonzero = DbA01BrowserLauncher.launch(
                repository, nonzeroScript,
                fixtureEnvironment(temporary.toRealPath().resolve("nonzero-evidence")),
                countingControl(nonzeroCloses), Duration.ofSeconds(5));
        assertEquals(7, nonzero.exitCode());
        assertFalse(nonzero.timedOut());
        assertEquals(1, nonzeroCloses.get());

        Path waitingScript = repository.resolve("waiting.mjs");
        Files.writeString(waitingScript, "setInterval(()=>{},1000);");
        AtomicInteger timeoutCloses = new AtomicInteger();
        var timeout = DbA01BrowserLauncher.launch(
                repository, waitingScript,
                fixtureEnvironment(temporary.toRealPath().resolve("timeout-evidence")),
                countingControl(timeoutCloses), Duration.ofMillis(100));
        assertTrue(timeout.timedOut());
        assertEquals(1, timeoutCloses.get());

        AtomicInteger interruptedCloses = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> interruption =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread parent = new Thread(() -> {
            try {
                DbA01BrowserLauncher.launch(
                        repository, waitingScript,
                        fixtureEnvironment(temporary.toRealPath()
                                .resolve("interrupted-evidence")),
                        countingControl(interruptedCloses), Duration.ofSeconds(20));
            } catch (Throwable failure) {
                interruption.set(failure);
            }
        });
        parent.start();
        Thread.sleep(150);
        parent.interrupt();
        parent.join(7000);
        assertFalse(parent.isAlive());
        assertInstanceOf(InterruptedException.class, interruption.get());
        assertEquals(1, interruptedCloses.get());
    }

    @Test void publicLaunchReportsCleanupFailureAndSuppressesItBehindPrimary() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("cleanup-repository")).toRealPath();
        Path script = repository.resolve("runner.mjs");
        Files.writeString(script, "");
        Path evidence = temporary.toRealPath().resolve("cleanup-evidence");
        IOException cleanupFailure = new IOException("controlled cleanup failure");
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable failingCleanup = () -> {
            closes.incrementAndGet();
            throw cleanupFailure;
        };

        IllegalStateException successCleanupFailure = assertThrows(
                IllegalStateException.class,
                () -> DbA01BrowserLauncher.launch(
                        repository, script, fixtureEnvironment(evidence),
                        failingCleanup, Duration.ofSeconds(5)));
        assertSame(cleanupFailure, successCleanupFailure.getCause());
        assertEquals(1, closes.get());

        Map<String, String> incomplete = fixtureEnvironment(evidence);
        incomplete.remove("PATHOME_BROWSER_BACKEND_PORT");
        AtomicInteger primaryCloses = new AtomicInteger();
        IOException secondCleanupFailure = new IOException("second cleanup failure");
        AutoCloseable secondFailingCleanup = () -> {
            primaryCloses.incrementAndGet();
            throw secondCleanupFailure;
        };
        IllegalStateException primary = assertThrows(IllegalStateException.class,
                () -> DbA01BrowserLauncher.launch(
                        repository, script, incomplete,
                        secondFailingCleanup, Duration.ofSeconds(5)));
        assertTrue(primary.getMessage().contains("PATHOME_BROWSER_BACKEND_PORT"));
        assertArrayEquals(new Throwable[]{secondCleanupFailure}, primary.getSuppressed());
        assertEquals(1, primaryCloses.get());
    }

    @Test void publicLaunchSanitizesHostileEnvironmentAndBoundsOutput() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("environment-repository"))
                .toRealPath();
        Path script = repository.resolve("environment.mjs");
        Files.writeString(script,
                "import {writeFile} from 'node:fs/promises';"
                        + "const selected=Object.fromEntries(Object.entries(process.env)"
                        + ".filter(([key])=>key.startsWith('PATHOME_BROWSER_')));"
                        + "await writeFile('child-environment.json',JSON.stringify({"
                        + "owner:selected.PATHOME_BROWSER_OWNER_TOKEN,"
                        + "controlPresent:Boolean(selected.PATHOME_BROWSER_GRANT_REVOKE_TOKEN),"
                        + "strictGenuine:selected.PATHOME_BROWSER_STRICT_GENUINE,"
                        + "unrelated:selected.PATHOME_BROWSER_UNRELATED_SECRET??null}));"
                        + "process.stdout.write('x'.repeat(2000000));");
        Path evidence = temporary.toRealPath().resolve("environment-evidence");
        AtomicInteger closes = new AtomicInteger();

        var result = DbA01BrowserLauncher.launch(
                repository, script, fixtureEnvironment(evidence),
                countingControl(closes), Duration.ofSeconds(5));

        assertEquals(0, result.exitCode());
        assertEquals(1, closes.get());
        String childEnvironment = Files.readString(
                repository.resolve("child-environment.json"));
        assertTrue(childEnvironment.contains(
                "\"owner\":\"explicit-owner-token\""));
        assertTrue(childEnvironment.contains("\"controlPresent\":true"));
        assertTrue(childEnvironment.contains("\"strictGenuine\":\"true\""));
        assertTrue(childEnvironment.contains("\"unrelated\":null"));
        assertFalse(childEnvironment.contains("inherited-override"));
        assertFalse(childEnvironment.contains("must-not-reach-child"));
        assertTrue(Files.size(result.log()) < 1100000);
        String receipt = Files.readString(result.log());
        assertTrue(receipt.contains("OUTPUT_TRUNCATED"));
        assertFalse(receipt.contains("explicit-control-token"));
    }

    @Test void launcherRefusesEachMissingFixtureFieldBeforeChildOutputAndClosesControl()
            throws Exception {
        Path target = temporary.toRealPath().resolve("refused");
        ProcessBuilder builder = child(target, "");
        Map<String, String> complete = fixtureEnvironment(target);
        for (String key : DbA01BrowserLauncher.REQUIRED_FIXTURE_ENVIRONMENT) {
            Map<String, String> incomplete = new LinkedHashMap<>(complete);
            incomplete.remove(key);
            AtomicInteger closes = new AtomicInteger();
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> DbA01BrowserLauncher.runSanitized(
                            builder, incomplete, countingControl(closes), Duration.ofSeconds(2)));
            assertTrue(refusal.getMessage().contains(key), key);
            assertEquals(1, closes.get(), key);
            assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), key);
        }
    }

    @Test void launcherSanitizesInheritedFixtureVariablesAndClosesOnEveryOutcome()
            throws Exception {
        Path target = temporary.toRealPath().resolve("explicit");
        ProcessBuilder builder = child(target,
                "await writeFile(c.path+'/environment.json',JSON.stringify("
                        + "Object.fromEntries(Object.entries(process.env)"
                        + ".filter(([key])=>key.startsWith('PATHOME_BROWSER_')))));"
                        + "console.log('fixture-present=' + Boolean(process.env.PATHOME_BROWSER_GRANT_REVOKE_TOKEN));");
        builder.environment().put("PATHOME_BROWSER_OWNER_TOKEN", "inherited-override");
        builder.environment().put("PATHOME_BROWSER_UNRELATED_SECRET", "must-not-reach-child");
        AtomicInteger closes = new AtomicInteger();

        var success = DbA01BrowserLauncher.runSanitized(
                builder, fixtureEnvironment(target), countingControl(closes), Duration.ofSeconds(5));
        assertEquals(0, success.exitCode());
        assertEquals(1, closes.get());
        String environment = Files.readString(target.resolve("environment.json"));
        assertTrue(environment.contains("\"PATHOME_BROWSER_OWNER_TOKEN\":\"explicit-owner-token\""));
        assertFalse(environment.contains("inherited-override"));
        assertFalse(environment.contains("PATHOME_BROWSER_UNRELATED_SECRET"));
        String output = Files.readString(success.log());
        assertTrue(output.contains("fixture-present=true"));
        assertFalse(output.contains("explicit-control-token"));

        Path timeoutTarget = temporary.toRealPath().resolve("timeout");
        ProcessBuilder timeoutBuilder = new ProcessBuilder(
                "node", "-e", "setInterval(()=>{},1000)");
        AtomicInteger timeoutCloses = new AtomicInteger();
        var timeout = DbA01BrowserLauncher.runSanitized(
                timeoutBuilder, fixtureEnvironment(timeoutTarget),
                countingControl(timeoutCloses), Duration.ofMillis(100));
        assertTrue(timeout.timedOut());
        assertEquals(1, timeoutCloses.get());

        Path nonzeroTarget = temporary.toRealPath().resolve("nonzero");
        AtomicInteger nonzeroCloses = new AtomicInteger();
        var nonzero = DbA01BrowserLauncher.runSanitized(
                new ProcessBuilder("node", "-e", "process.exit(7)"),
                fixtureEnvironment(nonzeroTarget), countingControl(nonzeroCloses),
                Duration.ofSeconds(2));
        assertEquals(7, nonzero.exitCode());
        assertEquals(1, nonzeroCloses.get());

        Path launchTarget = temporary.toRealPath().resolve("launch-failure");
        AtomicInteger launchCloses = new AtomicInteger();
        assertThrows(java.io.IOException.class,
                () -> DbA01BrowserLauncher.runSanitized(
                        new ProcessBuilder("/nonexistent/pathome-child"),
                        fixtureEnvironment(launchTarget), countingControl(launchCloses),
                        Duration.ofSeconds(2)));
        assertEquals(1, launchCloses.get());

        AtomicInteger interruptedCloses = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> interruption =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread parent = new Thread(() -> {
            try {
                DbA01BrowserLauncher.runSanitized(
                        new ProcessBuilder("node", "-e", "setInterval(()=>{},1000)"),
                        fixtureEnvironment(temporary.resolve("interrupted")),
                        countingControl(interruptedCloses), Duration.ofSeconds(20));
            } catch (Throwable failure) {
                interruption.set(failure);
            }
        });
        parent.start();
        Thread.sleep(150);
        parent.interrupt();
        parent.join(7000);
        assertFalse(parent.isAlive());
        assertInstanceOf(InterruptedException.class, interruption.get());
        assertEquals(1, interruptedCloses.get());
    }

    private DbA01BrowserProcess.Result runChecked(ProcessBuilder builder) throws Exception {
        return runWithTimeout(builder, Duration.ofSeconds(5));
    }

    private DbA01BrowserProcess.Result runWithTimeout(
            ProcessBuilder builder,
            Duration timeout
    ) throws Exception {
        return DbA01BrowserLauncher.runSanitized(
                builder, fixtureEnvironmentFor(builder), () -> {}, timeout);
    }
    private DbA01BrowserProcess.Result run(ProcessBuilder builder) {
        try { return runChecked(builder); } catch (Exception error) { throw new CompletionException(error); }
    }
}
