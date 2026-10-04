package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementSpecTest {

    static class Page {
        @Element(value = "Sign in button", css = "#login") Object inline;
        @Element("Cookie banner accept button") Object descriptionOnly;
        @Element(value = "Bad", id = "a", css = "b") Object twoLocators;
        @Element("   ") Object blank;
        @Element(value = "Slow one", xpath = "//x", timeoutMs = 250, heal = false) Object tuned;
        @Element(value = "Pay", css = "button", within = {"frame=#pay", "shadow=card"}) Object inFrame;
        @Element(value = "Pay", within = "frame=#pay") Object withinNoLocator;
        @Element(value = "Pay", css = "button", within = "iframe=#pay") Object badHop;
    }

    private static ElementSpec spec(String field) throws NoSuchFieldException {
        return ElementSpec.of(Page.class, Page.class.getDeclaredField(field));
    }

    @Test
    void parsesInlineLocator() throws Exception {
        ElementSpec s = spec("inline");
        assertEquals("Sign in button", s.description());
        assertEquals(ElementSpec.LocatorKind.CSS, s.locatorKind().orElseThrow());
        assertEquals("#login", s.locatorValue());
        assertEquals(Page.class.getName() + "#inline", s.key());
    }

    @Test
    void descriptionOnlyHasNoLocator() throws Exception {
        ElementSpec s = spec("descriptionOnly");
        assertFalse(s.hasInlineLocator());
        assertTrue(s.locatorKind().isEmpty());
    }

    @Test
    void rejectsMultipleLocators() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> spec("twoLocators"));
        assertTrue(e.getMessage().contains("more than one locator"));
    }

    @Test
    void rejectsBlankDescription() {
        assertThrows(IllegalStateException.class, () -> spec("blank"));
    }

    @Test
    void perElementOverrides() throws Exception {
        ElementSpec s = spec("tuned");
        assertFalse(s.healEnabled());
        assertEquals(Duration.ofMillis(250), s.locatorTimeout(HealingConfig.defaults()));
        assertEquals(HealingConfig.defaults().locatorTimeout(), spec("inline").locatorTimeout(HealingConfig.defaults()));
    }

    @Test
    void parsesWithin() throws Exception {
        assertEquals(List.of(), spec("inline").within());
        List<Within.Hop> hops = spec("inFrame").within();
        assertEquals(List.of("frame=#pay", "shadow=card"), Within.formatAll(hops));
        assertEquals(Page.class.getName() + "#inFrame", spec("inFrame").key());
    }

    @Test
    void rejectsWithinWithoutALocator() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> spec("withinNoLocator"));
        assertTrue(e.getMessage().contains("Page.withinNoLocator"));
        assertTrue(e.getMessage().contains("within requires a locator attribute"));
    }

    @Test
    void rejectsInvalidHopNamingTheField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> spec("badHop"));
        assertTrue(e.getMessage().contains("Page.badHop"));
        assertTrue(e.getMessage().contains("iframe=#pay"));
    }
}
