package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;

import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
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
        // A driver that cannot run JavaScript: the attribute-only fallback runs, silently.
        WebDriver noJs = (WebDriver) Proxy.newProxyInstance(WebDriver.class.getClassLoader(),
                new Class<?>[] {WebDriver.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        List<LogRecord> logged = warnings(() -> {
            assertEquals(LocatorSuggestion.of("css", "[data-test=\"it's \\\"q\\\"\"]"),
                    SuggestedLocator.suggest(noJs, el().attr("data-test", "it's \"q\""), Framework.SELENIUM));
            assertEquals(LocatorSuggestion.of("css", "[aria-label=\"a\\\\b\"]"),
                    SuggestedLocator.suggest(noJs, el().attr("aria-label", "a\\b"), Framework.SELENIUM));
        });
        assertEquals(List.of(), logged);
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
    void nullScriptResultMeansNoSuggestionAndNoWarning() {
        scripts(script -> null);
        List<LogRecord> logged = warnings(() -> assertNull(
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

    // ---- a text suggestion must replay to the target (R24) --------------------------------------

    private static final org.openqa.selenium.By EMAIL_XPATH =
            org.openqa.selenium.By.xpath(LocatorBuilder.textXPath("Email"));

    /** The anchored script: text on the first run, {@code fallback} when asked to skip text. */
    private List<Map<?, ?>> textThen(Map<String, Object> fallback) {
        List<Map<?, ?>> opts = new ArrayList<>();
        scripts(script -> {
            Map<?, ?> o = (Map<?, ?>) driver.args[1];
            opts.add(o);
            return Boolean.TRUE.equals(o.get("noText")) ? fallback : Map.of("kind", "text", "value", "Email");
        });
        return opts;
    }

    @Test
    void aTextSuggestionThatReplaysToTheTargetIsKept() {
        RecordingStubs.Element target = el();
        driver.put("top", EMAIL_XPATH, target);
        List<Map<?, ?>> opts = textThen(Map.of("kind", "css", "value", ":root > body > label"));
        assertEquals(LocatorSuggestion.of("text", "Email"), SuggestedLocator.suggest(driver, target, Framework.SELENIUM, List.of()));
        assertEquals(1, opts.size(), "verified: no second run");
    }

    @Test
    void aTextSuggestionThatReplaysToAnotherElementAsksForTheNextTier() {
        RecordingStubs.Element target = el();
        driver.put("top", EMAIL_XPATH, el());   // the replay XPath finds something else first
        List<Map<?, ?>> opts = textThen(Map.of("kind", "css", "value", ":root > body > label"));
        assertEquals(LocatorSuggestion.of("css", ":root > body > label", List.of("frame=#pay")),
                SuggestedLocator.suggest(driver, target, Framework.SELENIUM, List.of("frame=#pay")));
        assertEquals(2, opts.size());
        assertEquals(Boolean.TRUE, opts.get(1).get("noText"));
        assertEquals(Boolean.FALSE, opts.get(1).get("pierce"), "the other options are kept");
    }

    @Test
    void aTextSuggestionThatReplaysToNothingAsksForTheNextTier() {
        RecordingStubs.Element target = el();
        List<Map<?, ?>> opts = textThen(null);   // nothing else is unique
        assertNull(SuggestedLocator.suggest(driver, target, Framework.SELENIUM, List.of()));
        assertEquals(2, opts.size());
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

    @Test
    void appiumSuggestsTheAnchoredXPathFromThePageSource() {
        driver.pageSource = () -> NativeAnchoredLocatorTest.ANDROID;
        RecordingStubs.Element buy = el().attr("resource-id", "com.app:id/buy").attr("className", "android.widget.Button")
                .rect(new org.openqa.selenium.Rectangle(500, 900, 100, 580));
        assertEquals(LocatorSuggestion.of("xpath",
                        "//*[@resource-id='com.app:id/list']/android.view.ViewGroup[2]/android.widget.Button"),
                SuggestedLocator.suggest(driver, buy, Framework.APPIUM));
    }

    @Test
    void appiumNothingUniqueIsNullWithoutFallingBackToAttributes() {
        driver.pageSource = () -> NativeAnchoredLocatorTest.ANDROID_BARE;
        RecordingStubs.Element ok = el().attr("text", "OK").attr("className", "android.widget.Button")
                .rect(new org.openqa.selenium.Rectangle(0, 200, 100, 500));
        assertNull(SuggestedLocator.suggest(driver, ok, Framework.APPIUM));
    }

    @Test
    void appiumFallsBackToAttributesWhenThePageSourceCannotBeRead() {
        driver.pageSource = () -> { throw new IllegalStateException("flaky"); };
        assertEquals(LocatorSuggestion.of("accessibilityId", "go"),
                SuggestedLocator.suggest(driver, el().attr("content-desc", "go"), Framework.APPIUM));
    }

    @Test
    void appiumFallsBackToAttributesWhenTheTargetIsNotInThePageSource() {
        driver.pageSource = () -> NativeAnchoredLocatorTest.ANDROID;
        assertEquals(LocatorSuggestion.of("text", "elsewhere"),
                SuggestedLocator.suggest(driver, el().attr("text", "elsewhere"), Framework.APPIUM));
    }
}
