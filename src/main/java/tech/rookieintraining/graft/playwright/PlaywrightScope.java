package tech.rookieintraining.graft.playwright;

import tech.rookieintraining.graft.Within;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Where a Playwright search runs: the page, an iframe ({@link FrameLocator}) or a shadow host
 * ({@link Locator}). The three types share no interface, so this adapter holds exactly one of them.
 *
 * <p>{@link #enter} applies a {@code within} chain outside-in: {@code frame=<css>} becomes
 * {@code scope.frameLocator(css)}, {@code shadow=<css>} becomes {@code scope.locator(css)}.
 * Playwright CSS, {@code getByTestId} and {@code getByText} pierce open shadow roots, so the host
 * locator scopes the descendant search. An empty chain leaves the search on the page, making the
 * same calls as a plain {@code page.locator(...)}.
 */
final class PlaywrightScope {

    private final Page page;
    private final FrameLocator frame;
    private final Locator host;
    private final boolean underShadow;

    private PlaywrightScope(Page page, FrameLocator frame, Locator host, boolean underShadow) {
        this.page = page;
        this.frame = frame;
        this.host = host;
        this.underShadow = underShadow;
    }

    /** The scope left by applying {@code within} to the top-level document of {@code page}. */
    static PlaywrightScope enter(Page page, List<Within.Hop> within) {
        PlaywrightScope scope = new PlaywrightScope(page, null, null, false);
        for (Within.Hop hop : within) {
            scope = switch (hop.type()) {
                case FRAME -> new PlaywrightScope(null, scope.frameLocator(hop.css()), null, scope.underShadow);
                case SHADOW -> new PlaywrightScope(null, null, scope.locator(hop.css()), true);
            };
        }
        return scope;
    }

    Locator locator(String selector) {
        if (page != null) return page.locator(selector);
        if (frame != null) return frame.locator(selector);
        return host.locator(selector);
    }

    Locator getByTestId(String testId) {
        if (page != null) return page.getByTestId(testId);
        if (frame != null) return frame.getByTestId(testId);
        return host.getByTestId(testId);
    }

    Locator getByText(String text, boolean exact) {
        if (page != null) return page.getByText(text, new Page.GetByTextOptions().setExact(exact));
        if (frame != null) return frame.getByText(text, new FrameLocator.GetByTextOptions().setExact(exact));
        return host.getByText(text, new Locator.GetByTextOptions().setExact(exact));
    }

    FrameLocator frameLocator(String css) {
        if (page != null) return page.frameLocator(css);
        if (frame != null) return frame.frameLocator(css);
        return host.frameLocator(css);
    }

    /**
     * The locator for an {@code @Element} / learned-suggestion kind ({@code id, css, xpath, name,
     * text, testId}) in this scope, or {@code null} for a kind Playwright cannot replay. Shared by
     * inline {@code @Element} mapping and learned replay.
     *
     * @throws IllegalArgumentException for {@code xpath} when the chain has a shadow hop
     */
    Locator find(String kind, String value) {
        switch (kind) {
            case "id":     return locator("[id=" + quote(value) + "]");
            case "css":    return locator(value);
            case "xpath":
                if (underShadow) {
                    throw new IllegalArgumentException("xpath cannot be used with a shadow= hop in within: "
                            + "Playwright XPath does not pierce shadow roots; use css, id, name, text or testId");
                }
                return locator("xpath=" + value);
            case "name":   return locator("[name=" + quote(value) + "]");
            case "text":   return getByText(value, true);
            case "testId": return getByTestId(value);
            default:       return null;
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
