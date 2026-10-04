package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        scripts(script -> {
            assertTrue(script.startsWith("return ("), script);
            return Map.of("within", List.of("shadow=#host"), "kind", "css", "value", "div > button");
        });
        assertEquals(LocatorSuggestion.of("css", "div > button", List.of("shadow=#host")),
                SuggestedLocator.suggest(driver, el().attr("id", "ignored"), Framework.SELENIUM));
    }

    /** The anchored script answers {@code anchored}; the frame check says "top-level document". */
    private void scripts(Function<String, Object> anchored) {
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : anchored.apply(script);
    }

    /** Runs {@code r} and returns the WARNING records {@link SuggestedLocator} logged. */
    private static List<LogRecord> warnings(Runnable r) {
        Logger logger = Logger.getLogger(SuggestedLocator.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) records.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            r.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @Test
    void nullScriptResultFallsBackToAttributesWithoutAWarning() {
        scripts(script -> null);
        List<LogRecord> logged = warnings(() -> assertEquals(LocatorSuggestion.of("id", "save"),
                SuggestedLocator.suggest(driver, el().attr("id", "save"), Framework.SELENIUM)));
        assertEquals(List.of(), logged);
    }

    @Test
    void malformedScriptResultWarnsOnOneLineWithoutAStackTrace() {
        scripts(script -> Boolean.TRUE);
        List<LogRecord> logged = warnings(() -> assertEquals(LocatorSuggestion.of("id", "save"),
                SuggestedLocator.suggest(driver, el().attr("id", "save"), Framework.SELENIUM)));
        assertEquals(1, logged.size());
        assertNull(logged.get(0).getThrown());
        assertTrue(logged.get(0).getMessage().contains("true"), logged.get(0).getMessage());
    }

    @Test
    void scriptExceptionWarnsWithItsStackTrace() {
        scripts(script -> { throw new IllegalStateException("boom"); });
        List<LogRecord> logged = warnings(() -> assertEquals(LocatorSuggestion.of("id", "save"),
                SuggestedLocator.suggest(driver, el().attr("id", "save"), Framework.SELENIUM)));
        assertEquals(1, logged.size());
        assertNotNull(logged.get(0).getThrown());
    }

    @Test
    void frameHopsArePrependedToTheInDocumentSuggestion() {
        LocatorSuggestion inDoc = LocatorSuggestion.of("css", "div > button", List.of("shadow=#card"));
        assertEquals(LocatorSuggestion.of("css", "div > button", List.of("shadow=#shell", "frame=#pay", "shadow=#card")),
                SuggestedLocator.withFrames(inDoc, List.of("shadow=#shell", "frame=#pay")));
        assertEquals(inDoc, SuggestedLocator.withFrames(inDoc, List.of()));
        assertNull(SuggestedLocator.withFrames(inDoc, null), "an unusable frame path learns nothing");
        assertNull(SuggestedLocator.withFrames(null, List.of("frame=#pay")));
    }

    @Test
    void appiumKeepsTheMobileSuggestion() {
        driver.js = script -> { throw new AssertionError("no script for native Appium"); };
        assertEquals(LocatorSuggestion.of("accessibilityId", "go"),
                SuggestedLocator.suggest(driver, el().attr("content-desc", "go"), Framework.APPIUM));
    }
}
