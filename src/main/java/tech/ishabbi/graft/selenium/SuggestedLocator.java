package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.AnchoredLocatorScript;
import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Looks at the element Alumnium resolved and proposes a locator an engineer can paste into the
 * page object — and that the learned tier can replay. Preference order mirrors what survives UI
 * churn best: test ids, ids, accessibility ids, names/labels, and only then visible text.
 *
 * <p>Every attribute read is best-effort; mobile drivers throw on attributes they don't know.
 */
final class SuggestedLocator {

    private static final System.Logger LOG = System.getLogger(SuggestedLocator.class.getName());
    private static final Map<String, Object> SCRIPT_OPTIONS = Map.of("cssOnlyInShadow", true, "pierce", false);

    private SuggestedLocator() {}

    static String describe(WebElement el) {
        String tag = attr(el, "tagName", "class");
        String text = safeText(el);
        return "<" + (tag == null ? "element" : tag) + ">" + (text.isEmpty() ? "" : " \"" + abbreviate(text) + "\"");
    }

    /**
     * The full suggestion for an element: on the web, its in-document locator with the frame hops
     * of {@link FramePath#hopsFor} prepended. The driver must be in the element's frame.
     */
    static LocatorSuggestion suggest(WebDriver driver, WebElement el, Framework framework) {
        if (framework == Framework.APPIUM) return suggestMobile(el);
        LocatorSuggestion inDocument = suggestWeb(driver, el);
        return inDocument == null ? null : withFrames(inDocument, FramePath.hopsFor(driver, el));
    }

    /**
     * As {@link #suggest(WebDriver, WebElement, Framework)}, with the frame hops already known
     * ({@code null}: the frame path is unusable, so nothing is suggested).
     */
    static LocatorSuggestion suggest(WebDriver driver, WebElement el, Framework framework, List<String> frameHops) {
        if (framework == Framework.APPIUM) return suggestMobile(el);
        if (frameHops == null) return null;
        return withFrames(suggestWeb(driver, el), frameHops);
    }

    /** {@code frameHops} (outside-in) before the suggestion's own hops; {@code null} if either is null. */
    static LocatorSuggestion withFrames(LocatorSuggestion s, List<String> frameHops) {
        if (s == null || frameHops == null) return null;
        if (frameHops.isEmpty()) return s;
        List<String> within = new ArrayList<>(frameHops);
        within.addAll(s.within());
        return new LocatorSuggestion(s.kind(), s.value(), within);
    }

    /**
     * The shared anchored-locator script ({@link AnchoredLocatorScript}): a uniqueness-checked
     * locator plus the {@code shadow=} hops of the open shadow roots the element sits in, within its
     * own document. Selenium CSS does not pierce shadow roots ({@code pierce:false}) and a shadow
     * root takes only CSS ({@code cssOnlyInShadow:true}). Falls back to {@link #suggestByAttributes}
     * when the driver cannot run JS, the script throws (WARNING with the stack trace), returns a
     * malformed result (one-line WARNING) or returns {@code null} (nothing unique; no warning).
     */
    private static LocatorSuggestion suggestWeb(WebDriver driver, WebElement el) {
        WebDriver d = DriverRegistry.unwrapDriver(driver);
        if (d == null) d = driver;
        if (d instanceof JavascriptExecutor js) {
            try {
                Object result = js.executeScript("return (" + AnchoredLocatorScript.source() + ")(arguments[0], arguments[1]);",
                        SeleniumHealer.unwrap(el), SCRIPT_OPTIONS);
                if (result != null) {
                    LocatorSuggestion s = AnchoredLocatorScript.toSuggestion(result);
                    if (s != null) return s;
                    LOG.log(System.Logger.Level.WARNING, "Anchored locator script returned an unexpected result ("
                            + abbreviate(String.valueOf(result)) + "); falling back to attribute-only suggestion");
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Anchored locator script failed; falling back to attribute-only suggestion", e);
            }
        }
        return suggestByAttributes(el);
    }

    private static LocatorSuggestion suggestByAttributes(WebElement el) {
        String v;
        if ((v = dom(el, "data-testid")) != null) return LocatorSuggestion.of("testId", v);
        if ((v = dom(el, "data-test")) != null) {
            return LocatorSuggestion.of("css", "[data-test=" + LocatorBuilder.cssLiteral(v) + "]");
        }
        if ((v = dom(el, "id")) != null) return LocatorSuggestion.of("id", v);
        if ((v = dom(el, "name")) != null) return LocatorSuggestion.of("name", v);
        if ((v = dom(el, "aria-label")) != null) {
            return LocatorSuggestion.of("css", "[aria-label=" + LocatorBuilder.cssLiteral(v) + "]");
        }
        String text = safeText(el);
        if (!text.isEmpty() && text.length() <= 60) return LocatorSuggestion.of("text", text);
        return null;
    }

    private static LocatorSuggestion suggestMobile(WebElement el) {
        String v;
        if ((v = attr(el, "resource-id")) != null) return LocatorSuggestion.of("id", v);
        if ((v = attr(el, "content-desc")) != null) return LocatorSuggestion.of("accessibilityId", v);
        if ((v = attr(el, "name")) != null) return LocatorSuggestion.of("accessibilityId", v);   // iOS
        if ((v = attr(el, "label")) != null) return LocatorSuggestion.of("text", v);             // iOS
        if ((v = attr(el, "text")) != null) return LocatorSuggestion.of("text", v);              // Android
        return null;
    }

    private static String dom(WebElement el, String name) {
        try {
            String v = el.getDomAttribute(name);
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException e) {
            return attr(el, name);
        }
    }

    private static String attr(WebElement el, String... names) {
        for (String name : names) {
            try {
                String v = el.getDomAttribute(name);
                if (v != null && !v.isBlank()) return v;
            } catch (RuntimeException ignored) {
                // driver does not support the attribute
            }
        }
        return null;
    }

    private static String safeText(WebElement el) {
        try {
            String t = el.getText();
            return t == null ? "" : t.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }
}
