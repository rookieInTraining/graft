package tech.rookieintraining.graft.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.rookieintraining.graft.LocatorSuggestion;

class AnchoredLocatorScriptTest {

    @Test
    void sourceIsAFunctionExpressionLoadedOnce() {
        String src = AnchoredLocatorScript.source();
        assertTrue(src.startsWith("(el, opts) =>"), src.substring(0, Math.min(40, src.length())));
        assertSame(src, AnchoredLocatorScript.source());
    }

    @Test
    void convertsAWellFormedResult() {
        LocatorSuggestion s = AnchoredLocatorScript.toSuggestion(
                Map.of("kind", "css", "value", "#a > b", "within", List.of("shadow=#host")));
        assertEquals(new LocatorSuggestion("css", "#a > b", List.of("shadow=#host")), s);
    }

    @Test
    void missingWithinMeansTopLevel() {
        assertEquals(LocatorSuggestion.of("testId", "go"),
                AnchoredLocatorScript.toSuggestion(Map.of("kind", "testId", "value", "go")));
    }

    @Test
    void nullOrMalformedResultsGiveNull() {
        assertNull(AnchoredLocatorScript.toSuggestion(null));
        assertNull(AnchoredLocatorScript.toSuggestion("css=#a"));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("value", "#a")));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", "css")));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", 1, "value", "#a")));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", "css", "value", " ")));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", "css", "value", "#a", "within", "shadow=#h")));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", "css", "value", "#a", "within", List.of("bogus"))));
        assertNull(AnchoredLocatorScript.toSuggestion(Map.of("kind", "css", "value", "#a", "within", List.of(7))));
        Map<String, Object> nullHop = new HashMap<>(Map.of("kind", "css", "value", "#a"));
        nullHop.put("within", Arrays.asList("shadow=#h", null));
        assertNull(AnchoredLocatorScript.toSuggestion(nullHop));
    }
}
