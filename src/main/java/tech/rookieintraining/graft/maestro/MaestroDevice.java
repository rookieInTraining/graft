package tech.rookieintraining.graft.maestro;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The "driver" for Maestro. Maestro has no programmatic API, so this wraps the CLI: each call
 * renders a tiny flow file (no {@code launchApp}, so it runs against the app's current screen)
 * and executes {@code maestro test} on it.
 *
 * <p>Remote devices: pass the ADB-connected serial / simulator UDID as {@code device}; it is also
 * what Alumnium's Maestro session receives, so both sides drive the same device. {@link Builder#remote}
 * forwards {@code --host/--port} for farms that expose the Maestro driver over the network.
 *
 * <p>Trade-off: a JVM start per step (~2–3 s). Fine for healing-first page objects; if you have
 * long flows, run the bulk through your existing YAML and use {@link MaestroElement} only for
 * the steps whose selectors churn. Batching with resume-from-failed-step is the obvious next step
 * (see README).
 */
public final class MaestroDevice {

    private static final Pattern ELEMENT_NOT_FOUND = Pattern.compile(
            "(?is)element\\s+not\\s+found|no\\s+element\\s+(?:matching|found)|unable\\s+to\\s+find");
    private static final Pattern ASSERTION_FAILED = Pattern.compile("(?is)assertion\\s+is\\s+false");

    private final String appId;
    private final String platform;
    private final String maestroBinary;
    private final String deviceId;
    private final String host;
    private final Integer port;
    private final Path flowDir;
    private final Duration commandTimeout;

    private MaestroDevice(Builder b) {
        this.appId = b.appId;
        this.platform = b.platform;
        this.maestroBinary = b.maestroBinary;
        this.deviceId = b.deviceId;
        this.host = b.host;
        this.port = b.port;
        this.flowDir = b.flowDir;
        this.commandTimeout = b.commandTimeout;
    }

    public static Builder builder(String appId, String platform) {
        return new Builder(appId, platform);
    }

    public String appId() { return appId; }

    /** {@code "ios"} or {@code "android"}; also used as {@code platformName} for the Alumnium session. */
    public String platform() { return platform; }

    public String deviceId() { return deviceId; }

    /** Remote device-driver host (maps to {@code maestro --host}); {@code null} for local devices. */
    public String host() { return host; }

    public Integer port() { return port; }

    /** Runs a single Maestro command (already rendered as a YAML list item). */
    public MaestroResult run(String yamlCommand) {
        return runFlow(List.of(yamlCommand));
    }

    /** Runs the commands as one flow, in order. */
    public MaestroResult runFlow(List<String> yamlCommands) {
        StringBuilder flow = new StringBuilder();
        flow.append("appId: ").append(appId).append('\n').append("---\n");
        for (String c : yamlCommands) flow.append(c.stripTrailing()).append('\n');

        Path file;
        try {
            Files.createDirectories(flowDir);
            file = Files.createTempFile(flowDir, "heal-", ".yaml");
            Files.writeString(file, flow.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write Maestro flow", e);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(maestroBinary);
        if (deviceId != null) {
            cmd.add("--device");
            cmd.add(deviceId);
        }
        if (host != null) {
            cmd.add("--host");
            cmd.add(host);
        }
        if (port != null) {
            cmd.add("--port");
            cmd.add(String.valueOf(port));
        }
        cmd.add("test");
        cmd.add(file.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new MaestroResult(-1, "maestro timed out after " + commandTimeout + "\n"
                        + new String(out, StandardCharsets.UTF_8), file, flow.toString());
            }
            return new MaestroResult(p.exitValue(), new String(out, StandardCharsets.UTF_8), file, flow.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run '" + maestroBinary + "'. Install the Maestro CLI "
                    + "or set the binary path on MaestroDevice.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running Maestro", e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    /** Outcome of one {@code maestro test} run. */
    public record MaestroResult(int exitCode, String output, Path flowFile, String flowYaml) {
        public boolean success() { return exitCode == 0; }

        /** Maestro could not find the element — the case healing is for. */
        public boolean elementNotFound() { return !success() && ELEMENT_NOT_FOUND.matcher(output).find(); }

        public boolean assertionFailed() { return !success() && ASSERTION_FAILED.matcher(output).find(); }

        public String failureSummary() {
            String[] lines = output.strip().split("\n");
            for (String line : lines) {
                if (ELEMENT_NOT_FOUND.matcher(line).find() || ASSERTION_FAILED.matcher(line).find()) {
                    return line.strip();
                }
            }
            return lines.length == 0 ? "exit " + exitCode : lines[lines.length - 1].strip();
        }
    }

    public static final class Builder {
        private final String appId;
        private final String platform;
        private String maestroBinary = envOr("MAESTRO_BINARY", "maestro");
        private String deviceId;
        private String host;
        private Integer port;
        private Path flowDir = Path.of(".graft", "maestro-flows");
        private Duration commandTimeout = Duration.ofMinutes(2);

        private Builder(String appId, String platform) {
            this.appId = Objects.requireNonNull(appId, "appId");
            String p = Objects.requireNonNull(platform, "platform").toLowerCase();
            if (!p.equals("ios") && !p.equals("android")) {
                throw new IllegalArgumentException("platform must be \"ios\" or \"android\", got " + platform);
            }
            this.platform = p;
        }

        public Builder maestroBinary(String path) { this.maestroBinary = Objects.requireNonNull(path); return this; }
        /** UDID / serial / simulator name passed as {@code maestro --device}. */
        public Builder device(String deviceId) { this.deviceId = deviceId; return this; }
        /**
         * Device farm / remote emulator: the host (and port) the Maestro driver should connect to.
         * For ADB-over-network devices prefer {@code adb connect host:port} and pass the resulting
         * serial to {@link #device(String)} — that keeps Maestro and Alumnium on the same identifier.
         */
        public Builder remote(String host, Integer port) { this.host = host; this.port = port; return this; }
        public Builder flowDir(Path dir) { this.flowDir = Objects.requireNonNull(dir); return this; }
        public Builder commandTimeout(Duration d) { this.commandTimeout = Objects.requireNonNull(d); return this; }

        public MaestroDevice build() { return new MaestroDevice(this); }
    }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
