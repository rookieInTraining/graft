package tech.rookieintraining.graft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealingSelectorTest {

    private static HealingSelector base() {
        return HealingSelector.of("#pay", "the pay button");
    }

    @Test
    void keyIsUnchangedWithoutWithin() {
        assertEquals("selector:#pay|the pay button", base().key());
        assertEquals(List.of(), base().within());
    }

    @Test
    void keyGetsSuffixWithWithin() {
        HealingSelector s = base().within("frame=#checkout", "shadow=card-form");
        assertEquals("selector:#pay|the pay button|within=frame=#checkout > shadow=card-form", s.key());
        assertEquals(2, s.within().size());
    }

    @Test
    void withinReturnsACopyAndValidates() {
        HealingSelector plain = base();
        plain.within("frame=#x");
        assertEquals(List.of(), plain.within());
        assertThrows(IllegalArgumentException.class, () -> base().within("nope"));
    }

    @Test
    void descriptionOnlyRejectsWithin() {
        HealingSelector described = HealingSelector.describe("the pay button");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> described.within("frame=#checkout"));
        assertTrue(e.getMessage().contains("the pay button"), e.getMessage());
        assertTrue(e.getMessage().contains("within"), e.getMessage());
        assertEquals(List.of(), described.within(new String[0]).within(), "an empty within stays allowed");
    }

    @Test
    void copiesCarryWithin() {
        HealingSelector s = base().within("frame=#x");
        assertEquals(s.within(), s.heal(false).within());
        assertEquals(s.within(), s.timeout(Duration.ofSeconds(1)).within());
        assertEquals(s.key(), s.heal(false).key());
    }
}
