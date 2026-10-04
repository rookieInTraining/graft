package tech.ishabbi.graft.selenium;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FrameState} and {@link FramePath#find} against recording stubs (no browser). */
class FrameStateTest {

    private final RecordingStubs.Driver driver = new RecordingStubs.Driver();
    private final List<String> log = driver.log;
    /** The iframes each frame's deep query returns, by frame name. */
    private final Map<String, List<WebElement>> iframes = new HashMap<>();
    private final List<String> scripts = new ArrayList<>();
    private String marked = "none";   // the frame whose document carries FramePath tests' marker "x"
    /** Capture markers: attribute name to the frame whose document carries it. */
    private final Map<String, String> marks = new HashMap<>();

    private RecordingStubs.Element frame(String name, String parent) {
        RecordingStubs.Element el = new RecordingStubs.Element(name, log);
        iframes.computeIfAbsent(parent, k -> new ArrayList<>()).add(el);
        return el;
    }

    /** A page: the top-level check, the deep iframe query, and per-attribute document markers. */
    private void scriptedPage() {
        driver.js = script -> {
            scripts.add(script);
            if (script.equals(FrameState.IS_TOP_JS)) return driver.frame.equals("top");
            if (script.equals(FramePath.IFRAMES_JS)) return iframes.getOrDefault(driver.frame, List.of());
            Matcher has = Pattern.compile("hasAttribute\\('([^']+)'\\)").matcher(script);
            if (has.find()) {
                String attr = has.group(1);
                if (attr.equals("data-graft-frame-x")) return driver.frame.equals(marked);
                return driver.frame.equals(marks.get(attr));
            }
            if (script.contains("setAttribute(arguments[0]")) { marks.put((String) driver.args[0], driver.frame); return null; }
            if (script.contains("removeAttribute(arguments[0]")) {
                assertEquals(driver.frame, marks.remove((String) driver.args[0]), "removed in the marked document");
                return null;
            }
            throw new AssertionError("unexpected script " + script);
        };
    }

    @Test
    void atTheTopCaptureIsOneScriptAndRestoreIsDefaultContent() {
        scriptedPage();
        FrameState state = FrameState.capture(driver);
        assertEquals(List.of(FrameState.IS_TOP_JS), scripts);

        state.restore();
        assertEquals(List.of("defaultContent"), log);
    }

    @Test
    void insideAFrameCaptureFindsThePathAndLeavesNoMarker() {
        scriptedPage();
        RecordingStubs.Element a = frame("a", "top");
        frame("b", "a");
        driver.switchTo().frame(a);
        driver.switchTo().frame(iframes.get("a").get(0));   // the test sits in a > b
        FrameState state = FrameState.capture(driver);
        assertEquals("b", driver.frame, "capture ends where it started");
        assertEquals(Map.of(), marks, "the marker is removed at once");

        driver.switchTo().defaultContent();                  // something else moved the driver
        log.clear();
        scripts.clear();
        state.restore();

        assertEquals("b", driver.frame);
        assertEquals(List.of("defaultContent", "frame(a)", "frame(b)"), log);
        assertEquals(List.of(), scripts, "restore needs no scripts");
        state.restore();
        assertEquals("b", driver.frame, "restore can run again");
    }

    @Test
    void nestedCapturesInOneDocumentDoNotClobberEachOther() {
        scriptedPage();
        RecordingStubs.Element a = frame("a", "top");
        frame("other", "top");
        driver.switchTo().frame(a);
        FrameState outer = FrameState.capture(driver);
        FrameState inner = FrameState.capture(driver);       // same document, e.g. a proxy call inside a proxy call
        driver.switchTo().defaultContent();
        inner.restore();
        driver.switchTo().frame(iframes.get("top").get(1));
        outer.restore();

        assertEquals("a", driver.frame);
        assertEquals(Map.of(), marks);
    }

    @Test
    void whenTheFrameCannotBeFoundRestoreGoesToTheTop() {
        scriptedPage();
        RecordingStubs.Element a = frame("a", "top");
        driver.switchTo().frame(a);
        iframes.clear();                                      // the frame search cannot reach "a"
        FrameState state = FrameState.capture(driver);
        driver.switchTo().frame(a);

        assertDoesNotThrow(state::restore);
        assertEquals("top", driver.frame);
    }

    @Test
    void restoreNeverThrows() {
        HealingByTest.StubDriver stub = new HealingByTest.StubDriver();   // switchTo() throws
        FrameState top = FrameState.capture(stub);
        assertDoesNotThrow(top::restore);

        stub.jsContains = false;                                          // "not the top window"
        FrameState inner = FrameState.capture(stub);
        assertDoesNotThrow(inner::restore);
    }

    @Test
    void withoutJavaScriptRestoreGoesToTheTop() {
        driver.frame = "somewhere";
        FrameState state = FrameState.capture(driver);   // the stub's executeScript throws
        state.restore();
        assertEquals("top", driver.frame);
    }

    // ---- FramePath.find ------------------------------------------------------------------------

    @Test
    void findSearchesDepthFirstAndBacktracksWithParentFrame() {
        scriptedPage();
        frame("b", "top");
        RecordingStubs.Element a = frame("a", "top");
        RecordingStubs.Element a1 = frame("a1", "a");
        marked = "a1";

        Optional<List<WebElement>> path = FramePath.find(driver, FrameState.markerJs("x"));

        assertEquals(Optional.of(List.of(a, a1)), path);
        assertEquals("a1", driver.frame, "the driver is left in the matched frame");
        assertEquals(List.of("defaultContent", "frame(b)", "parentFrame", "frame(a)", "frame(a1)"), log);
    }

    @Test
    void findStopsAtTheDepthCap() {
        scriptedPage();
        String parent = "top";
        for (int i = 1; i <= 8; i++) {
            frame("f" + i, parent);
            parent = "f" + i;
        }
        marked = "f7";   // one level deeper than the cap of 6
        assertEquals(Optional.empty(), FramePath.find(driver, FrameState.markerJs("x")));
        assertTrue(log.stream().noneMatch(l -> l.equals("frame(f7)")), log.toString());

        marked = "f6";
        assertTrue(FramePath.find(driver, FrameState.markerJs("x")).isPresent());
    }

    @Test
    void findVisitsAtMost64Frames() {
        scriptedPage();
        for (int i = 1; i <= 70; i++) frame("f" + i, "top");
        marked = "f65";
        assertEquals(Optional.empty(), FramePath.find(driver, FrameState.markerJs("x")));
        assertEquals("top", driver.frame);

        marked = "f64";
        assertTrue(FramePath.find(driver, FrameState.markerJs("x")).isPresent());
    }
}
