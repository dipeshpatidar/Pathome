package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Parent-owned process receipt. The parent never receives a child output path for writing. */
public final class DbA01BrowserProcess {
    private static final int MAX_LOG_BYTES = 1024 * 1024;

    private DbA01BrowserProcess() {}

    public record Result(long pid, int exitCode, boolean timedOut, Path log) {}

    public static Path requireFreshEvidencePath(Path repository, String value) {
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute()) {
            throw new IllegalStateException("DB-A01 browser verification requires a fresh absolute evidence path");
        }
        Path evidence = Path.of(value).normalize();
        Path root = repository.toAbsolutePath().normalize().resolve("tools/oe-integration-env/evidence");
        if (!evidence.startsWith(root) || evidence.equals(root)) {
            throw new IllegalStateException("DB-A01 browser evidence must be a fresh child of the evidence root");
        }
        if (Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("DB-A01 browser evidence directory already exists");
        }
        return evidence;
    }

    public static Result run(ProcessBuilder builder, Duration timeout)
            throws IOException, InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        Path receipt = Files.createTempDirectory("db-a01-browser-receipt-").toRealPath();
        Path log = receipt.resolve("stdout.log");
        Process process = null;
        Thread drain = null;
        AtomicReference<IOException> failure = new AtomicReference<>();
        // Hold the descriptor across launch/drain: never reopen a replaceable log path.
        try (OutputStream output = Files.newOutputStream(log, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            try {
                process = builder.start();
            } catch (IOException exception) {
                output.write("LAUNCH_FAILED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                throw new IOException("Browser process launch failed; external receipt: " + log, exception);
            }
            Process child = process;
            drain = new Thread(() -> {
                try (var input = child.getInputStream()) {
                    byte[] bytes = new byte[8192];
                    int retained = 0, count;
                    boolean truncated = false;
                    while ((count = input.read(bytes)) != -1) {
                        int write = Math.min(count, MAX_LOG_BYTES - retained);
                        if (write > 0) output.write(bytes, 0, write);
                        retained += write;
                        if (write < count && !truncated) {
                            output.write("\n[OUTPUT_TRUNCATED]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            truncated = true;
                        }
                    }
                } catch (IOException exception) { failure.set(exception); }
            }, "db-a01-browser-output");
            drain.setDaemon(true);
            drain.start();
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) terminate(process);
            drain.join(5000);
            if (drain.isAlive()) throw new IOException("Browser output drain incomplete; external receipt: " + log);
            if (failure.get() != null) throw new IOException("Browser output receipt failed: " + log, failure.get());
            return new Result(process.pid(), process.exitValue(), !exited, log);
        } finally {
            if (process != null && process.isAlive()) terminate(process);
            if (drain != null && drain.isAlive()) {
                if (process != null) process.getInputStream().close();
                drain.interrupt();
            }
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Browser process did not terminate: " + process.pid());
        }
    }
}
