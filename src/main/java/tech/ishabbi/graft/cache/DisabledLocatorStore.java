package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;

import java.util.Map;
import java.util.Optional;

/** Store used when the learned tier is switched off. */
public final class DisabledLocatorStore implements LearnedLocatorStore {

    public static final DisabledLocatorStore INSTANCE = new DisabledLocatorStore();

    private DisabledLocatorStore() {}

    @Override
    public Optional<StoredEntry> get(String key) { return Optional.empty(); }

    @Override
    public void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {}

    @Override
    public ForgetResult forget(String key, String ifLearnedAt) { return ForgetResult.ABSENT; }

    @Override
    public Map<String, StoredEntry> snapshot() { return Map.of(); }
}
