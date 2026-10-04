package tech.ishabbi.graft.playwright;

import tech.ishabbi.graft.AnchoredLocatorScript;
import tech.ishabbi.graft.LocatorSuggestion;
import com.microsoft.playwright.Locator;
import java.util.Map;

/** Proposes a page-object locator from the {@link Locator} Alumnium resolved. */
final class PlaywrightSuggestedLocator {

    private static final System.Logger LOG = System.getLogger(PlaywrightSuggestedLocator.class.getName());

    /** Playwright replay pierces open shadow DOM, so uniqueness is counted across it. */
    private static final Map<String, Object> SCRIPT_OPTS = Map.of("cssOnlyInShadow", false, "pierce", true);

    private PlaywrightSuggestedLocator() {}

    static String describe(Locator loc) {
        String text = text(loc);
        return String.valueOf(loc) + (text.isEmpty() ? "" : " \"" + abbreviate(text) + "\"");
    }

    /**
     * Runs the shared {@link AnchoredLocatorScript} on the element: a uniqueness-checked locator plus
     * its {@code shadow=} hops, or {@code null} when none is stable. Falls back to the element's own
     * attributes if the script cannot run.
     */
    static LocatorSuggestion suggest(Locator loc) {
        Object result;
        try {
            result = loc.evaluate(AnchoredLocatorScript.source(), SCRIPT_OPTS);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Anchored-locator script failed for " + loc + "; falling back to the element's own attributes", e);
            return attributeOnly(loc);
        }
        return AnchoredLocatorScript.toSuggestion(result);
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
