package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocatorSuggestionTest {

    @Test
    void twoArgConstructorAndOfAreTopLevel() {
        LocatorSuggestion s = new LocatorSuggestion("css", "button");
        assertEquals(List.of(), s.within());
        assertEquals(s, LocatorSuggestion.of("css", "button"));
        assertNull(LocatorSuggestion.of(null, "x"));
        assertNull(LocatorSuggestion.of("css", null));
        assertNull(LocatorSuggestion.of("css", "  "));
        assertNull(LocatorSuggestion.of("css", " ", List.of("frame=#x")));
    }

    @Test
    void withoutWithinRendersAsBefore() {
        LocatorSuggestion s = LocatorSuggestion.of("testId", "go");
        assertEquals("@Element(testId = \"go\")", s.toAnnotation());
        assertEquals("testId=go", s.toString());
    }

    @Test
    void withWithinRendersHops() {
        LocatorSuggestion s = LocatorSuggestion.of("css", "button", List.of("frame=#x", "shadow=y"));
        assertEquals("@Element(within = {\"frame=#x\", \"shadow=y\"}, css = \"button\")", s.toAnnotation());
        assertEquals("frame=#x > shadow=y > css=button", s.toString());
    }

    @Test
    void escapesQuotesInsideHops() {
        LocatorSuggestion s = LocatorSuggestion.of("css", "b", List.of("frame=iframe[name=\"pay\"]"));
        assertEquals("@Element(within = {\"frame=iframe[name=\\\"pay\\\"]\"}, css = \"b\")", s.toAnnotation());
    }

    @Test
    void nullWithinBecomesEmptyAndListIsImmutable() {
        assertEquals(List.of(), new LocatorSuggestion("css", "b", null).within());
        assertThrows(UnsupportedOperationException.class,
                () -> LocatorSuggestion.of("css", "b", List.of("frame=#x")).within().add("frame=#y"));
    }

    @Test
    void invalidHopThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocatorSuggestion("css", "b", List.of("iframe=#x")));
    }
}
