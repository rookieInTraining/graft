package tech.ishabbi.graft.playwright;

import tech.ishabbi.graft.AnchoredLocatorScript;
import tech.ishabbi.graft.LocatorSuggestion;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Proposes a page-object locator from the {@link Locator} Alumnium resolved. */
final class PlaywrightSuggestedLocator {

    private static final System.Logger LOG = System.getLogger(PlaywrightSuggestedLocator.class.getName());

    /** Playwright replay pierces open shadow DOM, so uniqueness is counted across it. */
    private static final Map<String, Object> SCRIPT_OPTS = Map.of("cssOnlyInShadow", false, "pierce", true);
    /** For an iframe element: its frame= hop must be CSS. */
    private static final Map<String, Object> FRAME_OPTS = Map.of("cssOnlyInShadow", false, "pierce", true, "cssOnly", true);

    private PlaywrightSuggestedLocator() {}

    static String describe(Locator loc) {
        String text = text(loc);
        return String.valueOf(loc) + (text.isEmpty() ? "" : " \"" + abbreviate(text) + "\"");
    }

    /**
     * Runs the shared {@link AnchoredLocatorScript} on the element: a uniqueness-checked locator plus
     * its {@code shadow=} hops, or {@code null} when none is stable. Falls back to the element's own
     * attributes if the script cannot run. When the element sits in an iframe, the frame path
     * ({@code frame=} hops, and the shadow hops of each iframe element) is prepended.
     */
    static LocatorSuggestion suggest(Locator loc) {
        LocatorSuggestion own;
        try {
            own = AnchoredLocatorScript.toSuggestion(loc.evaluate(AnchoredLocatorScript.source(), SCRIPT_OPTS));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Anchored-locator script failed for " + loc + "; falling back to the element's own attributes", e);
            own = attributeOnly(loc);
        }
        return own == null ? null : withFramePath(loc, own);
    }

    /**
     * Prepends the hops from the top-level document down to the element's own document: for each
     * ancestor frame, the iframe element's {@code shadow=} hops then {@code frame=<css>}, outside-in.
     * {@code null} if any step fails, since a suggestion without its frames would not replay.
     */
    private static LocatorSuggestion withFramePath(Locator loc, LocatorSuggestion own) {
        List<ElementHandle> handles = new ArrayList<>();
        try {
            ElementHandle element = loc.elementHandle();
            handles.add(element);
            Frame frame = element.ownerFrame();
            if (frame == null) return null; // detached
            List<String> outer = new ArrayList<>();
            while (frame.parentFrame() != null) {
                ElementHandle iframe = frame.frameElement();
                handles.add(iframe);
                LocatorSuggestion hop = AnchoredLocatorScript.toSuggestion(
                        iframe.evaluate(AnchoredLocatorScript.source(), FRAME_OPTS));
                if (hop == null || !"css".equals(hop.kind())) {
                    LOG.log(System.Logger.Level.INFO, "No stable CSS for the iframe of " + loc + "; no suggestion");
                    return null;
                }
                List<String> hops = new ArrayList<>(hop.within());
                hops.add("frame=" + hop.value());
                outer.addAll(0, hops);
                frame = frame.parentFrame();
            }
            if (outer.isEmpty()) return own;
            outer.addAll(own.within());
            return LocatorSuggestion.of(own.kind(), own.value(), outer);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not derive the frame path of " + loc + "; no suggestion", e);
            return null;
        } finally {
            for (ElementHandle h : handles) {
                try {
                    h.dispose();
                } catch (RuntimeException ignored) {
                    // already gone with its frame
                }
            }
        }
    }

    /** The unchecked fallback: the element's own attributes, then its text. Package-private for tests. */
    static LocatorSuggestion attributeOnly(Locator loc) {
        String v;
        if ((v = attr(loc, "data-testid")) != null) return LocatorSuggestion.of("testId", v);
        if ((v = attr(loc, "id")) != null) return LocatorSuggestion.of("id", v);
        if ((v = attr(loc, "name")) != null) return LocatorSuggestion.of("name", v);
        if ((v = attr(loc, "aria-label")) != null) return LocatorSuggestion.of("css", "[aria-label=" + cssString(v) + "]");
        String text = text(loc);
        if (!text.isEmpty() && text.length() <= 60) return LocatorSuggestion.of("text", text);
        return null;
    }

    private static String attr(Locator loc, String name) {
        try {
            String v = loc.getAttribute(name);
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Locator loc) {
        try {
            String t = loc.innerText();
            return t == null ? "" : t.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A double-quoted CSS string with {@code "} and {@code \} escaped. */
    private static String cssString(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }

}
