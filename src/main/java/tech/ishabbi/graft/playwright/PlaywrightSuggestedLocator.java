package tech.ishabbi.graft.playwright;

import tech.ishabbi.graft.AnchoredLocatorScript;
import tech.ishabbi.graft.LocatorSuggestion;
import com.microsoft.playwright.Locator;
import java.util.Map;

/** Proposes a page-object locator from the {@link Locator} Alumnium resolved. */
final class PlaywrightSuggestedLocator {

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
            result = loc.evaluate(AnchoredLocatorScript.source(), Map.of("cssOnlyInShadow", false));
        } catch (RuntimeException e) {
            return attributeOnly(loc);
        }
        return AnchoredLocatorScript.toSuggestion(result);
    }

    private static LocatorSuggestion attributeOnly(Locator loc) {
        String v;
        if ((v = attr(loc, "data-testid")) != null) return LocatorSuggestion.of("testId", v);
        if ((v = attr(loc, "id")) != null) return LocatorSuggestion.of("id", v);
        if ((v = attr(loc, "name")) != null) return LocatorSuggestion.of("name", v);
        if ((v = attr(loc, "aria-label")) != null) return LocatorSuggestion.of("css", "[aria-label='" + v + "']");
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

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }

}
