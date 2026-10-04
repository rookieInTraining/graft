package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.AnchoredLocatorScript;
import tech.ishabbi.graft.LocatorSuggestion;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds which frame a document or element lives in, by walking the frame tree with the driver.
 *
 * <p>{@link #find} is the frame search: from {@code defaultContent()}, depth-first through the
 * iframes of each document (including iframes inside open shadow roots), running a marker script
 * in each document until one answers {@code true}. It visits at most {@value #MAX_FRAMES} frames,
 * {@value #MAX_DEPTH} levels deep.
 *
 * <p>{@link #hopsFor} turns the frame path of a healed element into {@code within} hops: for each
 * iframe on the path, its own {@code shadow=} hops and a {@code frame=<css>} hop, from the shared
 * anchored-locator script run in the iframe's parent document.
 */
final class FramePath {

    private static final System.Logger LOG = System.getLogger(FramePath.class.getName());

    static final int MAX_DEPTH = 6;
    static final int MAX_FRAMES = 64;

    /** Every iframe/frame element of the current document, including those inside open shadow roots. */
    static final String IFRAMES_JS = "const out = [];"
            + " const walk = (root) => { for (const n of root.querySelectorAll('*')) {"
            + " if (n.localName === 'iframe' || n.localName === 'frame') out.push(n);"
            + " if (n.shadowRoot) walk(n.shadowRoot); } };"
            + " walk(document); return out;";

    private static final String PROBE_JS = "arguments[0].setAttribute('data-graft-probe', arguments[1]);";
    private static final String UNPROBE_JS = "arguments[0].removeAttribute('data-graft-probe');";
    /** For an iframe element: CSS only, shadow hops in its own document, Selenium's non-piercing CSS. */
    private static final Map<String, Object> FRAME_HOP_OPTIONS =
            Map.of("cssOnly", true, "cssOnlyInShadow", true, "pierce", false);

    private FramePath() {}

    /**
     * The iframe elements from the top down to the first document where {@code markerJs} returns
     * {@code true} (empty for the top document itself). Leaves the driver switched into that
     * document; when nothing matches, leaves it at {@code defaultContent()} and returns empty.
     */
    static Optional<List<WebElement>> find(WebDriver driver, String markerJs) {
        JavascriptExecutor js = FrameState.js(driver);
        if (js == null) return Optional.empty();
        driver.switchTo().defaultContent();
        List<WebElement> path = new ArrayList<>();
        if (search(driver, js, markerJs, path, new int[] {0})) return Optional.of(List.copyOf(path));
        driver.switchTo().defaultContent();
        return Optional.empty();
    }

    private static boolean search(WebDriver driver, JavascriptExecutor js, String markerJs,
                                  List<WebElement> path, int[] visited) {
        if (matches(js, markerJs)) return true;
        if (path.size() >= MAX_DEPTH) return false;
        for (WebElement iframe : iframes(js)) {
            if (visited[0] >= MAX_FRAMES) return false;
            visited[0]++;
            try {
                driver.switchTo().frame(iframe);
            } catch (RuntimeException e) {
                continue;   // detached or not switchable: skip it
            }
            path.add(iframe);
            if (search(driver, js, markerJs, path, visited)) return true;
            path.remove(path.size() - 1);
            driver.switchTo().parentFrame();
        }
        return false;
    }

    private static boolean matches(JavascriptExecutor js, String markerJs) {
        try {
            return Boolean.TRUE.equals(js.executeScript(markerJs));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<WebElement> iframes(JavascriptExecutor js) {
        try {
            Object found = js.executeScript(IFRAMES_JS);
            if (!(found instanceof List<?> list)) return List.of();
            List<WebElement> out = new ArrayList<>();
            for (Object o : list) if (o instanceof WebElement el) out.add(el);
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The {@code within} hops (outside-in) of the frames {@code el} sits in: empty for the top-level
     * document, {@code null} when the frame path cannot be found or an iframe on it has no unique CSS.
     * The driver must be in {@code el}'s frame (where Alumnium leaves it) and is returned there,
     * unless the element's frame cannot be found again (then it is left at the top).
     */
    static List<String> hopsFor(WebDriver driver, WebElement el) {
        JavascriptExecutor js = FrameState.js(driver);
        if (js == null) return List.of();
        try {
            if (Boolean.TRUE.equals(js.executeScript(FrameState.IS_TOP_JS))) return List.of();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not tell whether the element is in a frame; assuming the top: {0}",
                    e.getMessage());
            return List.of();
        }
        WebElement raw = SeleniumHealer.unwrap(el);
        String nonce = UUID.randomUUID().toString();
        try {
            js.executeScript(PROBE_JS, raw, nonce);
            Optional<List<WebElement>> path = find(driver, probeJs(nonce));
            if (path.isEmpty()) {
                LOG.log(System.Logger.Level.DEBUG, "Could not find the frame of {0}; no frame hops", el);
                return null;
            }
            List<String> hops = framesHops(driver, js, path.get());
            js.executeScript(UNPROBE_JS, raw);
            return hops;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not derive the frame hops of {0}: {1}", el, e.getMessage());
            return null;
        }
    }

    /** A deep query (through open shadow roots) for the probed element. The nonce is a UUID, safe to inline. */
    private static String probeJs(String nonce) {
        return "const sel = '[data-graft-probe=\"" + nonce + "\"]';"
                + " const has = (root) => { if (root.querySelector(sel)) return true;"
                + " for (const n of root.querySelectorAll('*')) if (n.shadowRoot && has(n.shadowRoot)) return true;"
                + " return false; };"
                + " return has(document);";
    }

    /**
     * Walks the path again from the top: each iframe's hop is computed in its parent document,
     * then the driver switches into it. Ends in the element's frame; {@code null} if any iframe
     * has no unique CSS.
     */
    private static List<String> framesHops(WebDriver driver, JavascriptExecutor js, List<WebElement> path) {
        driver.switchTo().defaultContent();
        List<String> hops = new ArrayList<>();
        boolean usable = true;
        for (WebElement iframe : path) {
            if (usable) {
                LocatorSuggestion hop = frameHop(js, iframe);
                if (hop == null) {
                    usable = false;
                } else {
                    hops.addAll(hop.within());
                    hops.add("frame=" + hop.value());
                }
            }
            driver.switchTo().frame(iframe);
        }
        return usable ? hops : null;
    }

    private static LocatorSuggestion frameHop(JavascriptExecutor js, WebElement iframe) {
        Object result = js.executeScript("return (" + AnchoredLocatorScript.source() + ")(arguments[0], arguments[1]);",
                iframe, FRAME_HOP_OPTIONS);
        LocatorSuggestion s = AnchoredLocatorScript.toSuggestion(result);
        return s != null && "css".equals(s.kind()) ? s : null;
    }
}
