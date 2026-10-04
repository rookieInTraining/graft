package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;

import java.util.Map;
import java.util.Optional;

/** Where a learned locator is remembered. File, HTTP, or Redis. */
public interface LearnedLocatorStore {

    enum ForgetResult { ABSENT, DELETED, KEPT }

    Optional<StoredEntry> get(String key);

    void learn(String key, LocatorSuggestion suggestion, String origin, String framework);

    /**
     * Removes {@code key} when the stored row is absent or not newer than {@code ifLearnedAt}.
     * A file store may ignore the timestamp and always delete.
     */
    ForgetResult forget(String key, String ifLearnedAt);

    Map<String, StoredEntry> snapshot();
}
