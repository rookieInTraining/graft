package tech.rookieintraining.graft.cache;

import java.time.Instant;

/**
 * Whether a learned row may be deleted. A row newer than the timestamp the caller observed
 * is kept, so one shard's miss cannot erase another shard's later heal.
 */
public final class ConditionalForget {

    public enum Decision { ABSENT, DELETE, KEEP }

    private ConditionalForget() {}

    public static Decision decide(String storedLearnedAt, String ifLearnedAt) {
        if (storedLearnedAt == null || storedLearnedAt.isBlank()) return Decision.ABSENT;
        Instant stored = Instant.parse(storedLearnedAt);
        Instant cutoff = Instant.parse(ifLearnedAt);
        return stored.isAfter(cutoff) ? Decision.KEEP : Decision.DELETE;
    }
}
