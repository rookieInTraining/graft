package tech.rookieintraining.graft.cache;

import tech.rookieintraining.graft.LocatorSuggestion;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory store for tests (HTTP server, healer replay). Conditional forget matches the Redis rule. */
public final class MemoryLocatorStore implements LearnedLocatorStore {

    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<StoredEntry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public synchronized void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {
        entries.put(key, new StoredEntry(suggestion, origin, Instant.now().toString(), framework));
    }

    @Override
    public synchronized ForgetResult forget(String key, String ifLearnedAt) {
        StoredEntry current = entries.get(key);
        return switch (ConditionalForget.decide(current == null ? null : current.learnedAt(), ifLearnedAt)) {
            case ABSENT -> ForgetResult.ABSENT;
            case KEEP -> ForgetResult.KEPT;
            case DELETE -> {
                entries.remove(key);
                yield ForgetResult.DELETED;
            }
        };
    }

    @Override
    public synchronized Map<String, StoredEntry> snapshot() {
        return new LinkedHashMap<>(entries);
    }
}
