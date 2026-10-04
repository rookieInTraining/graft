package tech.rookieintraining.graft.selenium;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The browsing context (frame) the test's driver is in, captured so it can be restored after
 * Graft switched frames. Selenium's frame state is global to the driver, so every path that
 * switches captures one first and restores it afterwards.
 *
 * <p>Capture is one script round-trip in the common case: at the top-level document, restore is
 * {@code defaultContent()}. Inside a frame, capture marks the document with an attribute of its
 * own ({@code data-graft-frame-<nonce>}, so nested captures never clobber each other), finds the
 * iframe path to it with the frame search ({@link FramePath#find}, which ends back in this
 * document) and removes the marker straight away. Restore switches through that path. Nothing is
 * left in the page, whether or not the state is ever restored.
 *
 * <p>Capture and {@link #restore()} never throw. When the current frame cannot be found again
 * (beyond the frame search's caps), capture leaves the driver at the top and the marker in place;
 * that is unavoidable, since the frame is unreachable. Restore is best-effort: when switching
 * fails, it logs at DEBUG and leaves the driver at {@code defaultContent()}. A driver that cannot
 * run JavaScript is treated as being at the top.
 */
final class FrameState {

    private static final System.Logger LOG = System.getLogger(FrameState.class.getName());

    static final String IS_TOP_JS = "return window.top === window;";
    private static final String STAMP_JS = "document.documentElement.setAttribute(arguments[0], '');";
    private static final String UNSTAMP_JS = "document.documentElement.removeAttribute(arguments[0]);";

    private static final FrameState NONE = new FrameState(null, List.of());

    private final WebDriver driver;          // null → nothing to restore (native mobile)
    private final List<WebElement> path;     // iframes from the top; empty → the top-level document

    private FrameState(WebDriver driver, List<WebElement> path) {
        this.driver = driver;
        this.path = path;
    }

    /** A state whose restore does nothing, for drivers without frames (native Appium). */
    static FrameState none() {
        return NONE;
    }

    static FrameState capture(WebDriver driver) {
        JavascriptExecutor js = js(driver);
        if (js == null) return new FrameState(driver, List.of());
        try {
            if (Boolean.TRUE.equals(js.executeScript(IS_TOP_JS))) return new FrameState(driver, List.of());
            String nonce = UUID.randomUUID().toString();
            js.executeScript(STAMP_JS, attribute(nonce));
            Optional<List<WebElement>> found = FramePath.find(driver, markerJs(nonce));
            if (found.isEmpty()) {
                LOG.log(System.Logger.Level.DEBUG, "Could not find the current frame again; restoring will go to the top");
                return new FrameState(driver, List.of());
            }
            js.executeScript(UNSTAMP_JS, attribute(nonce));   // the search ended in the marked document
            return new FrameState(driver, found.get());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not capture the current frame; restoring will go to the top: {0}",
                    e.getMessage());
            return new FrameState(driver, List.of());
        }
    }

    private static String attribute(String nonce) {
        return "data-graft-frame-" + nonce;
    }

    /** The marker check run in each document by the frame search. The nonce is a UUID, safe to inline. */
    static String markerJs(String nonce) {
        return "return document.documentElement.hasAttribute('" + attribute(nonce) + "');";
    }

    /** Switches the driver back to the captured frame. Never throws; may be called more than once. */
    void restore() {
        if (driver == null) return;
        try {
            driver.switchTo().defaultContent();
            for (WebElement iframe : path) driver.switchTo().frame(iframe);
            return;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not restore the test''s frame; leaving the driver at the top: {0}",
                    e.getMessage());
        }
        try {
            driver.switchTo().defaultContent();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "defaultContent() failed while restoring the frame: {0}", e.getMessage());
        }
    }

    /** The driver's script executor (unwrapping decorators), or {@code null}. */
    static JavascriptExecutor js(WebDriver driver) {
        WebDriver d = DriverRegistry.unwrapDriver(driver);
        if (d instanceof JavascriptExecutor js) return js;
        return driver instanceof JavascriptExecutor js ? js : null;
    }
}
