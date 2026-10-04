package tech.ishabbi.graft.cache;

import org.junit.jupiter.api.Test;
import tech.ishabbi.graft.LocatorSuggestion;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheJsonTest {

    @Test
    void entryWithoutWithinKeepsTheOldShape() {
        StoredEntry e = new StoredEntry(LocatorSuggestion.of("css", "b"), "Page.java:1", "2026-01-01T00:00:00Z", "SELENIUM");
        assertEquals("{\"kind\":\"css\",\"value\":\"b\",\"origin\":\"Page.java:1\","
                + "\"framework\":\"SELENIUM\",\"learnedAt\":\"2026-01-01T00:00:00Z\"}", CacheJson.entry(e));
        assertFalse(CacheJson.entry(e).contains("within"));
    }

    @Test
    void withinIsARealJsonArrayAndRoundTrips() {
        StoredEntry e = new StoredEntry(LocatorSuggestion.of("css", "b", List.of("frame=#x", "shadow=y")),
                "Page.java:1", "2026-01-01T00:00:00Z", null);
        String json = CacheJson.entry(e);
        assertTrue(json.contains("\"within\":[\"frame=#x\",\"shadow=y\"]"), json);

        StoredEntry back = CacheJson.parseEntry(json);
        assertEquals(List.of("frame=#x", "shadow=y"), back.suggestion().within());
        assertEquals(List.of("frame=#x", "shadow=y"), CacheJson.parseSuggestion(json).within());

        Map<String, StoredEntry> snap = CacheJson.parseSnapshot(CacheJson.snapshot(Map.of("k", e)));
        assertEquals(List.of("frame=#x", "shadow=y"), snap.get("k").suggestion().within());
    }

    @Test
    void missingWithinIsTopLevel() {
        String json = "{\"kind\":\"css\",\"value\":\"b\"}";
        assertEquals(List.of(), CacheJson.parseSuggestion(json).within());
        assertEquals(List.of(), CacheJson.parseEntry(json).suggestion().within());
    }
}
