package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link RedisLocatorStore} reads one stored row, without a live Redis: {@code get} and
 * {@code snapshot} both go through {@link RedisLocatorStore#parseRow}.
 */
class RedisRowTest {

    @Test
    void aGoodRowParses() {
        Optional<StoredEntry> row = RedisLocatorStore.parseRow("k", "{\"kind\":\"css\",\"value\":\"b\","
                + "\"within\":[\"frame=#pay\"],\"origin\":\"P.java:1\",\"learnedAt\":\"2026-01-01T00:00:00Z\"}");
        assertEquals(LocatorSuggestion.of("css", "b", List.of("frame=#pay")), row.orElseThrow().suggestion());
    }

    @Test
    void aRowWithAnUnknownHopIsAbsentWithAOneLineWarning() {
        List<LogRecord> warnings = new ArrayList<>();
        Optional<StoredEntry> row = withWarnings(warnings, () -> RedisLocatorStore.parseRow("k1",
                "{\"kind\":\"css\",\"value\":\"b\",\"within\":[\"portal=#x\"],\"learnedAt\":\"2026-01-01T00:00:00Z\"}"));
        assertTrue(row.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getMessage().contains("k1"), warnings.get(0).getMessage());
        assertNull(warnings.get(0).getThrown(), "no stack trace");
    }

    @Test
    void unparseableJsonAndAMissingKindAreAbsent() {
        assertTrue(withWarnings(new ArrayList<>(), () -> RedisLocatorStore.parseRow("k", "{not json")).isEmpty());
        assertTrue(withWarnings(new ArrayList<>(), () -> RedisLocatorStore.parseRow("k", "{\"value\":\"b\"}")).isEmpty());
    }

    private static <T> T withWarnings(List<LogRecord> records, java.util.function.Supplier<T> s) {
        Logger logger = Logger.getLogger(RedisLocatorStore.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) records.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        boolean parent = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);   // expected warnings stay out of the build output
        try {
            return s.get();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parent);
        }
    }
}
