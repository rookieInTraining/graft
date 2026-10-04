package tech.ishabbi.graft;

import tech.ishabbi.graft.cache.CachePaths;
import tech.ishabbi.graft.cache.HttpLocatorStore;
import tech.ishabbi.graft.cache.LearnedLocatorStore;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Runtime knobs for healing. Immutable; build with {@link #builder()} or take {@link #defaults()}.
 *
 * <p>Environment overrides (handy in CI without touching code):
 * <ul>
 *   <li>{@code GRAFT_ENABLED=false} — disable healing globally (locator failures surface as-is)</li>
 *   <li>{@code GRAFT_STRICT=true} — heal, record the suggestion, then fail the step</li>
 *   <li>{@code GRAFT_TIMEOUT_MS=3000} — how long the primary locator gets before healing</li>
 *   <li>{@code GRAFT_REPORT=path.json} — where the heal report is written ({@code none} to disable)</li>
 *   <li>{@code GRAFT_LEARNED=path.json} — learned-locator file ({@code none} disables the file)</li>
 *   <li>{@code GRAFT_LEARNED_URL} — HTTP cache base URL; when set, wins over the file</li>
 *   <li>{@code GRAFT_NAMESPACE} — cache namespace (default {@code default})</li>
 *   <li>{@code GRAFT_CACHE_TOKEN} — bearer token for the cache</li>
 *   <li>{@code GRAFT_LEARNED_TIMEOUT_MS} — cache HTTP timeout (default 500)</li>
 * </ul>
 */
public final class HealingConfig {

    private final boolean enabled;
    private final boolean strict;
    private final Duration locatorTimeout;
    private final Duration pollInterval;
    private final int maxHealsPerElement;
    private final Path reportPath;
    private final Path learnedLocatorsPath;
    private final LearnedLocatorStore learnedStore;
    private final URI learnedUrl;
    private final String namespace;
    private final String cacheToken;
    private final Duration learnedTimeout;
    private final List<HealListener> listeners;

    private HealingConfig(Builder b) {
        this.enabled = b.enabled;
        this.strict = b.strict;
        this.locatorTimeout = b.locatorTimeout;
        this.pollInterval = b.pollInterval;
        this.maxHealsPerElement = b.maxHealsPerElement;
        this.reportPath = b.reportPath;
        this.learnedLocatorsPath = b.learnedLocatorsPath;
        this.learnedStore = b.learnedStore;
        this.learnedUrl = b.learnedUrl;
        this.namespace = b.namespace;
        this.cacheToken = b.cacheToken;
        this.learnedTimeout = b.learnedTimeout;
        this.listeners = Collections.unmodifiableList(new ArrayList<>(b.listeners));
    }

    public static Builder builder() { return new Builder(); }

    public static HealingConfig defaults() { return builder().build(); }

    /** Healing switched on. When off, every healer behaves like the plain framework. */
    public boolean enabled() { return enabled; }

    /**
     * Strict mode: the element is still healed (so the report contains a suggested locator)
     * but the access then fails with {@link HealingException}. Use on PR pipelines to force
     * locator fixes while nightly runs keep going.
     */
    public boolean strict() { return strict; }

    /** How long the primary locator is retried before Alumnium is consulted. */
    public Duration locatorTimeout() { return locatorTimeout; }

    public Duration pollInterval() { return pollInterval; }

    /** Upper bound on Alumnium lookups per element per session; protects against LLM-cost loops. */
    public int maxHealsPerElement() { return maxHealsPerElement; }

    /** JSON heal report location, or {@code null} when disabled. */
    public Path reportPath() { return reportPath; }

    /** Learned-locator store location, or {@code null} when the tier is off. */
    public Path learnedLocatorsPath() { return learnedLocatorsPath; }

    /**
     * The learned-locator tier. An explicit store wins, then {@code GRAFT_LEARNED_URL},
     * then the file. A null file path with no URL disables the tier.
     */
    public LearnedLocators learnedLocators() {
        if (learnedStore != null) return LearnedLocators.over(learnedStore);
        if (learnedUrl != null) return LearnedLocators.over(new HttpLocatorStore(learnedUrl, namespace, cacheToken, learnedTimeout));
        return learnedLocatorsPath == null ? LearnedLocators.disabled() : LearnedLocators.at(learnedLocatorsPath);
    }

    public List<HealListener> listeners() { return listeners; }

    public static final class Builder {
        private boolean enabled = envBool("GRAFT_ENABLED", true);
        private boolean strict = envBool("GRAFT_STRICT", false);
        private Duration locatorTimeout = Duration.ofMillis(envLong("GRAFT_TIMEOUT_MS", 5_000));
        private Duration pollInterval = Duration.ofMillis(250);
        private int maxHealsPerElement = 5;
        private Path reportPath = envPath("GRAFT_REPORT", Path.of(".graft", "heal-report.json"));
        private Path learnedLocatorsPath = envPath("GRAFT_LEARNED", Path.of(".graft", "learned-locators.json"));
        private LearnedLocatorStore learnedStore;
        private URI learnedUrl = envUri("GRAFT_LEARNED_URL");
        private String namespace = envNamespace();
        private String cacheToken = blankToNull(System.getenv("GRAFT_CACHE_TOKEN"));
        private Duration learnedTimeout = Duration.ofMillis(envLong("GRAFT_LEARNED_TIMEOUT_MS", 500));
        private final List<HealListener> listeners = new ArrayList<>();

        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder strict(boolean strict) { this.strict = strict; return this; }
        public Builder locatorTimeout(Duration d) { this.locatorTimeout = Objects.requireNonNull(d); return this; }
        public Builder pollInterval(Duration d) { this.pollInterval = Objects.requireNonNull(d); return this; }
        public Builder maxHealsPerElement(int n) { this.maxHealsPerElement = Math.max(0, n); return this; }
        /** {@code null} disables the report. */
        public Builder reportPath(Path p) { this.reportPath = p; return this; }
        /** {@code null} disables the file. A URL or {@link #learnedStore} still enables the tier. */
        public Builder learnedLocatorsPath(Path p) { this.learnedLocatorsPath = p; return this; }
        /** When set, this store is used instead of the file and {@code GRAFT_LEARNED_URL}. */
        public Builder learnedStore(LearnedLocatorStore store) { this.learnedStore = store; return this; }
        public Builder addListener(HealListener l) { this.listeners.add(Objects.requireNonNull(l)); return this; }

        public HealingConfig build() { return new HealingConfig(this); }
    }

    // ---- env helpers -------------------------------------------------------------------

    private static boolean envBool(String name, boolean def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off"));
    }

    private static long envLong(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Path envPath(String name, Path def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        return v.equalsIgnoreCase("none") ? null : Path.of(v.trim());
    }

    private static URI envUri(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return null;
        return URI.create(v.trim());
    }

    private static String envNamespace() {
        String v = System.getenv("GRAFT_NAMESPACE");
        if (v == null || v.isBlank()) return "default";
        String trimmed = v.trim();
        if (!CachePaths.namespaceOk(trimmed)) {
            throw new IllegalArgumentException("Invalid GRAFT_NAMESPACE: " + trimmed);
        }
        return trimmed;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
