package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestedLocatorTest {

    private final RecordingStubs.Driver driver = new RecordingStubs.Driver();

    private RecordingStubs.Element el() { return new RecordingStubs.Element("el", driver.log); }

    @Test
    void fallbackEscapesDataTestAndAriaLabel() {
        // The stub driver's executeScript throws, so the attribute-only fallback runs.
        assertEquals(LocatorSuggestion.of("css", "[data-test=\"it's \\\"q\\\"\"]"),
                SuggestedLocator.suggest(driver, el().attr("data-test", "it's \"q\""), Framework.SELENIUM));
        assertEquals(LocatorSuggestion.of("css", "[aria-label=\"a\\\\b\"]"),
                SuggestedLocator.suggest(driver, el().attr("aria-label", "a\\b"), Framework.SELENIUM));
    }

    @Test
    void scriptResultIsUsedWhenItRuns() {
        driver.js = script -> {
            assertTrue(script.startsWith("return ("), script);
            return Map.of("within", List.of("shadow=#host"), "kind", "css", "value", "div > button");
        };
        assertEquals(LocatorSuggestion.of("css", "div > button", List.of("shadow=#host")),
                SuggestedLocator.suggest(driver, el().attr("id", "ignored"), Framework.SELENIUM));
    }

    @Test
    void appiumKeepsTheMobileSuggestion() {
        driver.js = script -> { throw new AssertionError("no script for native Appium"); };
        assertEquals(LocatorSuggestion.of("accessibilityId", "go"),
                SuggestedLocator.suggest(driver, el().attr("content-desc", "go"), Framework.APPIUM));
    }
}
