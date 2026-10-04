package tech.ishabbi.graft.selenium;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealingByWithinTest {

    private static HealingBy base() {
        return HealingBy.of(By.id("pay"), "the pay button");
    }

    @Test
    void keyIsUnchangedWithoutWithin() {
        assertEquals("by:By.id: pay|the pay button", base().key());
        assertEquals(List.of(), base().within());
    }

    @Test
    void keyGetsSuffixWithWithin() {
        HealingBy by = base().within("frame=#checkout", "shadow=card-form");
        assertEquals("by:By.id: pay|the pay button|within=frame=#checkout > shadow=card-form", by.key());
        assertEquals(2, by.within().size());
    }

    @Test
    void withinReturnsACopyAndValidates() {
        HealingBy plain = base();
        plain.within("frame=#x");
        assertEquals(List.of(), plain.within());
        assertThrows(IllegalArgumentException.class, () -> base().within("iframe=#x"));
    }

    @Test
    void copiesCarryWithin() {
        HealingBy by = base().within("frame=#x");
        String key = by.key();
        assertEquals(key, by.timeout(Duration.ofSeconds(1)).key());
        assertEquals(key, by.heal(false).key());
        assertEquals(key, by.proxied().key());
        assertEquals(by.within(), by.proxied().timeout(Duration.ofSeconds(1)).heal(false).within());
    }
}
