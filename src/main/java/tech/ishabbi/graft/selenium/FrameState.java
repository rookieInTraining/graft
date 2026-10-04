package tech.ishabbi.graft.selenium;

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
 * {@code defaultContent()}. Inside a frame, the document is stamped with a nonce
 * ({@code data-graft-frame}); restore searches the frame tree for that marker ({@link FramePath#find}),
 * switches back into it and removes the marker.
 *
 * <p>{@link #restore()} is best-effort and never throws: when the frame cannot be found again (or
 * anything fails), it logs at DEBUG and leaves the driver at {@code defaultContent()}. A driver
 * that cannot run JavaScript is treated as being at the top.
 */
final class FrameState {

    private static final System.Logger LOG = System.getLogger(FrameState.class.getName());

    static final String IS_TOP_JS = "return window.top === window;";
    private static final String STAMP_JS = "document.documentElement.setAttribute('data-graft-frame', arguments[0]);";
    private static final String UNSTAMP_JS = "document.documentElement.removeAttribute('data-graft-frame');";

    private static final FrameState NONE = new FrameState(null, null);

    private final WebDriver driver;   // null → nothing to restore (native mobile)
    private final String nonce;       // null → the top-level document
    private List<WebElement> path;    // the iframes to the marked document, once found

    private FrameState(WebDriver driver, String nonce) {
        this.driver = driver;
        this.nonce = nonce;
    }

    /** A state whose restore does nothing, for drivers without frames (native Appium). */
    static FrameState none() {
        return NONE;
    }

    static FrameState capture(WebDriver driver) {
        JavascriptExecutor js = js(driver);
        if (js == null) return new FrameState(driver, null);
        try {
            if (Boolean.TRUE.equals(js.executeScript(IS_TOP_JS))) return new FrameState(driver, null);
            String nonce = UUID.randomUUID().toString();
            js.executeScript(STAMP_JS, nonce);
            return new FrameState(driver, nonce);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not capture the current frame; restoring will go to the top: {0}",
                    e.getMessage());
            return new FrameState(driver, null);
        }
    }

    /** The marker check run in each document by the frame search. The nonce is a UUID, safe to inline. */
    static String markerJs(String nonce) {
        return "return document.documentElement.getAttribute('data-graft-frame') === '" + nonce + "';";
    }

    /**
     * Switches the driver back to the captured frame. Never throws. May be called more than once:
     * the first restore finds the marked document and removes the marker; later ones switch
     * through the iframe elements found then.
     */
    void restore() {
        if (driver == null) return;
        try {
            if (nonce == null) {
                driver.switchTo().defaultContent();
                return;
            }
            if (path != null) {
                driver.switchTo().defaultContent();
                for (WebElement iframe : path) driver.switchTo().frame(iframe);
                return;
            }
            Optional<List<WebElement>> found = FramePath.find(driver, markerJs(nonce));
            if (found.isPresent()) {
                path = found.get();
                try {
                    js(driver).executeScript(UNSTAMP_JS);
                } catch (RuntimeException ignored) {
                    // a leftover attribute is harmless
                }
                return;
            }
            LOG.log(System.Logger.Level.DEBUG, "The test''s frame is gone; leaving the driver at the top");
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
