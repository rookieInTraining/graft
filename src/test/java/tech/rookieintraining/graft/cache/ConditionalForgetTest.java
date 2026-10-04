package tech.rookieintraining.graft.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tech.rookieintraining.graft.cache.ConditionalForget.Decision.ABSENT;
import static tech.rookieintraining.graft.cache.ConditionalForget.Decision.DELETE;
import static tech.rookieintraining.graft.cache.ConditionalForget.Decision.KEEP;

class ConditionalForgetTest {

    @Test
    void missingRowIsAbsent() {
        assertEquals(ABSENT, ConditionalForget.decide(null, "2026-10-02T00:00:00Z"));
        assertEquals(ABSENT, ConditionalForget.decide("  ", "2026-10-02T00:00:00Z"));
    }

    @Test
    void newerRowIsKept() {
        assertEquals(KEEP, ConditionalForget.decide("2026-10-02T00:00:02Z", "2026-10-02T00:00:01Z"));
    }

    @Test
    void equalOrOlderRowIsDeleted() {
        assertEquals(DELETE, ConditionalForget.decide("2026-10-02T00:00:01Z", "2026-10-02T00:00:01Z"));
        assertEquals(DELETE, ConditionalForget.decide("2026-10-02T00:00:01Z", "2026-10-02T00:00:02Z"));
    }

    @Test
    void equalInstantsMatchAcrossIsoFormats() {
        assertEquals(DELETE, ConditionalForget.decide(
                "2026-10-02T00:00:00.000000000Z", "2026-10-02T00:00:00Z"));
    }
}
