package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HealEventTest {

    private static HealEvent event(LocatorSuggestion s) {
        return new HealEvent(Framework.PLAYWRIGHT, "k", "P.java:1", "d", "orig", "why", "to", s,
                Duration.ofMillis(5), Instant.EPOCH);
    }

    @Test
    void suggestionWithinOnlyWhenNonEmpty() {
        assertFalse(event(LocatorSuggestion.of("css", "b")).toMap().containsKey("suggestionWithin"));
        assertFalse(event(null).toMap().containsKey("suggestionWithin"));
        assertEquals(List.of("frame=#x"),
                event(LocatorSuggestion.of("css", "b", List.of("frame=#x"))).toMap().get("suggestionWithin"));
    }
}
