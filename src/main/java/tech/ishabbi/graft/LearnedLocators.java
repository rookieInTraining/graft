package tech.ishabbi.graft;

import tech.ishabbi.graft.cache.DisabledLocatorStore;
import tech.ishabbi.graft.cache.FileLocatorStore;
import tech.ishabbi.graft.cache.LearnedLocatorStore;
import tech.ishabbi.graft.cache.StoredEntry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The learned-locator tier: after a heal, the {@link LocatorSuggestion} derived from the element
 * Alumnium found is remembered under the locator's key. On the next resolution the healer tries
 * primary → learned → Alumnium, so a broken locator costs one LLM call per <em>suite</em>, not per
 * run.
 *
 * <p>The default store is the JSON file {@code .graft/learned-locators.json}. Set
 * {@code GRAFT_LEARNED_URL} to use the HTTP cache ({@link tech.ishabbi.graft.cache.CacheServer})
 * instead, which is what shares heals across machines. {@code GRAFT_LEARNED=none} disables the
 * file when no URL is set.
 */
public final class LearnedLocators {

    private static final Map<Path, LearnedLocators> INSTANCES = new LinkedHashMap<>();

    private final LearnedLocatorStore store;

    private LearnedLocators(LearnedLocatorStore store) {
        this.store = store;
    }

    /** Shared instance per file (healers for different drivers in one JVM share one store). */
    public static synchronized LearnedLocators at(Path path) {
        return INSTANCES.computeIfAbsent(path, p -> new LearnedLocators(new FileLocatorStore(p)));
    }

    /** A store that remembers nothing — used when the tier is disabled. */
    public static LearnedLocators disabled() {
        return new LearnedLocators(DisabledLocatorStore.INSTANCE);
    }

    /** Wraps a store the caller already built (tests, or {@code GRAFT_LEARNED_URL}). */
    public static LearnedLocators over(LearnedLocatorStore store) {
        return new LearnedLocators(Objects.requireNonNull(store));
    }

    public Optional<LocatorSuggestion> get(String key) {
        return entry(key).map(Entry::suggestion);
    }

    public Optional<Entry> entry(String key) {
        return store.get(key).map(LearnedLocators::toEntry);
    }

    public void learn(String key, LocatorSuggestion suggestion, String origin) {
        learn(key, suggestion, origin, null);
    }

    public void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {
        if (suggestion == null) return;
        store.learn(key, suggestion, origin, framework);
    }

    public void forget(String key) {
        forget(key, null);
    }

    /** {@code learnedAt} is the instant from the {@link #entry} that just missed. */
    public void forget(String key, String learnedAt) {
        store.forget(key, learnedAt);
    }

    public Map<String, Entry> snapshot() {
        Map<String, Entry> out = new LinkedHashMap<>();
        store.snapshot().forEach((k, e) -> out.put(k, toEntry(e)));
        return out;
    }

    private static Entry toEntry(StoredEntry e) {
        return new Entry(e.suggestion(), e.origin(), e.learnedAt());
    }

    public record Entry(LocatorSuggestion suggestion, String origin, String learnedAt) {}
}
