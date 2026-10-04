package tech.ishabbi.graft.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.Within;
import tech.ishabbi.graft.internal.AnchoredLocatorScript;

/**
 * Runs the shared anchored-locator script in headless Chromium through
 * {@link PlaywrightSuggestedLocator#suggest}. The target element carries {@code data-graft-probe="t"},
 * an attribute the script ignores, so the test can find it without influencing the suggestion.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_BROWSER", matches = "true")
class AnchoredSuggestionTest {

    private static final String TARGET = "[data-graft-probe=t]";
    private static final Map<String, Object> PLAYWRIGHT_OPTS = Map.of("cssOnlyInShadow", false, "pierce", true);
    private static final Map<String, Object> SELENIUM_OPTS = Map.of("cssOnlyInShadow", true, "pierce", false);

    private static Playwright playwright;
    private static Browser browser;
    private Page page;

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void newPage() {
        page = browser.newPage();
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void uniqueTestIdGivesTestId() {
        LocatorSuggestion s = suggestFor("<div><button data-testid='login-submit' data-graft-probe='t'>Go</button></div>");
        assertSuggestion(s, "testId", "login-submit", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void duplicateIdFallsToALaterTier() {
        LocatorSuggestion s = suggestFor("<input id='email' name='email' data-graft-probe='t'><input id='email'>");
        assertSuggestion(s, "name", "email", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void duplicateTestIdFallsToALaterTier() {
        LocatorSuggestion s = suggestFor(
                "<button data-testid='save' id='save-profile' data-graft-probe='t'>Save</button>"
                        + "<button data-testid='save'>Save</button>");
        assertSuggestion(s, "id", "save-profile", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void dataTestGivesQuotedCss() {
        LocatorSuggestion s = suggestFor("<a data-test='nav-home' data-graft-probe='t'>Home</a>");
        assertSuggestion(s, "css", "[data-test=\"nav-home\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void ariaLabelWithQuotesIsEscapedAndMatches() {
        LocatorSuggestion s = suggestFor(
                "<button aria-label='Say \"hi\" it&apos;s a \\ test' data-graft-probe='t'>x</button><button>x</button>");
        assertSuggestion(s, "css", "[aria-label=\"Say \\\"hi\\\" it's a \\\\ test\"]", List.of());
        assertEquals(1, page.locator(s.value()).count());
        assertResolvesToTarget(s);
    }

    @Test
    void elementWithoutStableAttributesIsAnchoredOnAncestor() {
        LocatorSuggestion s = suggestFor(
                "<form id='login'><div><input type='text' data-graft-probe='t'></div><button type='submit'>Go</button></form>");
        assertSuggestion(s, "css", "#login > div > input[type=\"text\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void ambiguousSiblingsGetNthOfTypeOnlyWhereNeeded() {
        LocatorSuggestion s = suggestFor(
                "<div id='list'>"
                        + "<div class='row'><input type='checkbox'></div>"
                        + "<div class='row'><label>x</label><input type='checkbox' data-graft-probe='t'></div>"
                        + "</div>");
        assertSuggestion(s, "css", "#list > div:nth-of-type(2) > input[type=\"checkbox\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void siblingsWithDifferentQualifiersNeedNoNthOfType() {
        LocatorSuggestion s = suggestFor(
                "<form id='f'><input type='text'><input type='password' data-graft-probe='t'></form>");
        assertSuggestion(s, "css", "#f > input[type=\"password\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void sameTagAndQualifierSiblingsGetNthOfType() {
        LocatorSuggestion s = suggestFor(
                "<ul id='menu'><li role='menuitem'>A</li><li role='menuitem' data-graft-probe='t'>B</li></ul>");
        assertSuggestion(s, "css", "#menu > li[role=\"menuitem\"]:nth-of-type(2)", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void elementInOpenShadowRootGetsShadowHop() {
        LocatorSuggestion s = suggestFor(
                "<card-form id='card'></card-form>"
                        + shadow("document.getElementById('card')", "open", "<input name=\"cc\" data-graft-probe=\"t\">"));
        assertSuggestion(s, "name", "cc", List.of("shadow=#card"));
        // Playwright CSS pierces open shadow roots, so the element's own locator replays at top level.
        assertResolvesToTarget(s);
    }

    @Test
    void shadowHostWithoutStableAttributesIsAnchoredOnAncestor() {
        LocatorSuggestion s = suggestFor(
                "<div id='wrap'><x-widget></x-widget></div>"
                        + shadow("document.querySelector('x-widget')", "open", "<button data-testid=\"go\" data-graft-probe=\"t\">Go</button>"));
        assertSuggestion(s, "testId", "go", List.of("shadow=#wrap > x-widget"));
        assertResolvesToTarget(s);
    }

    @Test
    void nestedShadowRootsGiveTwoHopsOutermostFirst() {
        LocatorSuggestion s = suggestFor(
                "<outer-host data-testid='outer'></outer-host>"
                        + "<script>"
                        + "const o = document.querySelector('outer-host').attachShadow({mode:'open'});"
                        + "o.innerHTML = '<inner-host id=\"inner\"></inner-host>';"
                        + "const i = o.querySelector('inner-host').attachShadow({mode:'open'});"
                        + "i.innerHTML = '<button aria-label=\"Pay\" data-graft-probe=\"t\">Pay</button>';"
                        + "</script>");
        assertSuggestion(s, "css", "[aria-label=\"Pay\"]",
                List.of("shadow=[data-testid=\"outer\"]", "shadow=#inner"));
        assertResolvesToTarget(s);
    }

    @Test
    void closedShadowRootGivesNull() {
        page.setContent("<secret-box id='box'></secret-box>"
                + "<script>"
                + "const r = document.getElementById('box').attachShadow({mode:'closed'});"
                + "r.innerHTML = '<button id=\"hidden\">Hidden</button>';"
                + "window.__target = r.querySelector('button');"
                + "</script>");
        ElementHandle target = page.evaluateHandle("() => window.__target").asElement();
        assertNotNull(target);
        Object result = target.evaluate(AnchoredLocatorScript.source(), PLAYWRIGHT_OPTS);
        assertNull(result);
        assertNull(AnchoredLocatorScript.toSuggestion(result));
    }

    @Test
    void probeAndNoisyAttributesNeverAppear() {
        LocatorSuggestion s = suggestFor(
                "<section id='ember5678'><form id='profile'>"
                        + "<button id='ember1234' data-alumnium-id='42' class='css-1x2y3z' data-graft-probe='t'>Save</button>"
                        + "</form></section>");
        assertSuggestion(s, "css", "#profile > button", List.of());
        String all = s.toString();
        assertFalse(all.contains("ember"), all);
        assertFalse(all.contains("alumnium"), all);
        assertFalse(all.contains("42"), all);
        assertFalse(all.contains("css-"), all);
        assertResolvesToTarget(s);
    }

    @Test
    void documentWithoutAnchorsPrefersUniqueText() {
        LocatorSuggestion s = suggestFor("<div><p>a</p></div><div><p data-graft-probe='t'>Unique   words</p></div>");
        assertSuggestion(s, "text", "Unique words", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void textTierUsesDomTextNotRenderedText() {
        LocatorSuggestion s = suggestFor(
                "<div><p>a</p></div><div><p style='text-transform:uppercase' data-graft-probe='t'>Shout</p></div>");
        assertSuggestion(s, "text", "Shout", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void textFromDescendantElementsIsNotUsed() {
        LocatorSuggestion s = suggestFor(
                "<div><p>a</p></div><div><p data-graft-probe='t'>Hello <b>world</b></p></div>");
        assertSuggestion(s, "css", ":root > body > div:nth-of-type(2) > p", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void textSplitAcrossLineBreakIsNotUsed() {
        LocatorSuggestion s = suggestFor("<div><p>a</p></div><div><p data-graft-probe='t'>one<br>two</p></div>");
        assertSuggestion(s, "css", ":root > body > div:nth-of-type(2) > p", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void hiddenTextIsNotUsed() {
        LocatorSuggestion s = suggestFor(
                "<div><p>a</p></div><div><p style='visibility:hidden' data-graft-probe='t'>Ghost</p></div>");
        assertSuggestion(s, "css", ":root > body > div:nth-of-type(2) > p", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void pierceRejectsIdDuplicatedInsideOpenShadowRoot() {
        String html = "<button id='dup' name='buy' data-graft-probe='t'>Buy</button><x-host id='h'></x-host>"
                + shadow("document.getElementById('h')", "open", "<span id=\"dup\">x</span>");
        LocatorSuggestion s = suggestFor(html);
        assertSuggestion(s, "name", "buy", List.of());
        assertResolvesToTarget(s);

        // Without pierce (Selenium), the document-scoped id is unique.
        Object noPierce = page.locator(TARGET).evaluate(AnchoredLocatorScript.source(), SELENIUM_OPTS);
        assertSuggestion(AnchoredLocatorScript.toSuggestion(noPierce), "id", "dup", List.of());
    }

    @Test
    void pierceRejectsTextDuplicatedInsideOpenShadowRoot() {
        LocatorSuggestion s = suggestFor("<div><p data-graft-probe='t'>Hi</p></div><x-host id='h'></x-host>"
                + shadow("document.getElementById('h')", "open", "<span>Hi</span>"));
        assertSuggestion(s, "css", ":root > body > div > p", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void fallbackEscapesAriaLabel() {
        page.setContent("<button aria-label='Say \"hi\" \\ now' data-graft-probe='t'>x</button>");
        LocatorSuggestion s = PlaywrightSuggestedLocator.attributeOnly(page.locator(TARGET));
        assertSuggestion(s, "css", "[aria-label=\"Say \\\"hi\\\" \\\\ now\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void duplicateTextFallsBackToRootPath() {
        LocatorSuggestion s = suggestFor("<div><p>same</p></div><div><p data-graft-probe='t'>same</p></div>");
        assertSuggestion(s, "css", ":root > body > div:nth-of-type(2) > p", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void documentWithoutAnchorsOrTextFallsBackToRootPath() {
        LocatorSuggestion s = suggestFor(
                "<div><input type='text'></div><div><input type='text' data-graft-probe='t'></div>");
        assertSuggestion(s, "css", ":root > body > div:nth-of-type(2) > input[type=\"text\"]", List.of());
        assertResolvesToTarget(s);
    }

    @Test
    void shadowRootPathIsTheLastResort() {
        LocatorSuggestion s = suggestFor("<x-box id='box'></x-box>"
                + shadow("document.getElementById('box')", "open",
                        "<div><input type=\"checkbox\" data-graft-probe=\"t\"></div>"));
        assertSuggestion(s, "css", "div > input[type=\"checkbox\"]", List.of("shadow=#box"));
        assertResolvesToTarget(s);
    }

    @Test
    void textTierInShadowRootAndCssOnlySkip() {
        page.setContent("<x-panel id='panel'></x-panel>"
                + shadow("document.getElementById('panel')", "open",
                        "<div><span><b>Go</b></span></div><span><b data-graft-probe=\"t\">Hi</b></span>"));
        Locator target = page.locator(TARGET);

        LocatorSuggestion s = PlaywrightSuggestedLocator.suggest(target);
        assertSuggestion(s, "text", "Hi", List.of("shadow=#panel"));
        assertResolvesToTarget(s);

        Object cssOnly = target.evaluate(AnchoredLocatorScript.source(), SELENIUM_OPTS);
        LocatorSuggestion selenium = AnchoredLocatorScript.toSuggestion(cssOnly);
        assertTrue(selenium == null || !"text".equals(selenium.kind()), String.valueOf(selenium));
    }

    @Test
    void cssOnlyInShadowKeepsOwnAttributeTiers() {
        page.setContent("<card-form id='card'></card-form>"
                + shadow("document.getElementById('card')", "open", "<input name=\"cc\" data-graft-probe=\"t\">"));
        Object result = page.locator(TARGET).evaluate(AnchoredLocatorScript.source(), SELENIUM_OPTS);
        assertSuggestion(AnchoredLocatorScript.toSuggestion(result), "name", "cc", List.of("shadow=#card"));
    }

    @Test
    void hostLightDomCountsTowardsShadowUniqueness() {
        // The host's slotted light child has the same text as the shadow target. A shadow= hop replays
        // as host.locator(...), which also sees light descendants, so the text tier must not apply.
        page.setContent("<x-box id='box'><button>Go</button></x-box>"
                + shadow("document.getElementById('box')", "open",
                        "<slot></slot><div><button data-graft-probe=\"t\">Go</button></div>"));
        LocatorSuggestion s = PlaywrightSuggestedLocator.suggest(page.locator(TARGET));
        assertSuggestion(s, "css", "div > button", List.of("shadow=#box"));
        Locator replay = PlaywrightScope.enter(page, Within.parseAll(s.within())).find(s.kind(), s.value());
        assertEquals(1, replay.count(), "replay of " + s);
        assertEquals("t", replay.getAttribute("data-graft-probe"), "replay of " + s);
    }

    @Test
    void cssOnlyForcesCssForTheElementItself() {
        Map<String, Object> cssOnly = Map.of("cssOnly", true, "pierce", true);
        page.setContent("<iframe data-testid='pay' data-graft-probe='t'></iframe>"
                + "<iframe id='pay.frame:1' data-graft-probe='u'></iframe>"
                + "<iframe name='checkout' data-graft-probe='v'></iframe>"
                + "<div><p>x</p></div><div><p data-graft-probe='w'>Only text</p></div>");
        assertSuggestion(cssOnlyAt(TARGET, cssOnly), "css", "[data-testid=\"pay\"]", List.of());
        // The id needs CSS.escape.
        LocatorSuggestion escaped = cssOnlyAt("[data-graft-probe=u]", cssOnly);
        assertSuggestion(escaped, "css", "#pay\\.frame\\:1", List.of());
        assertEquals(1, page.locator(escaped.value()).count());
        assertSuggestion(cssOnlyAt("[data-graft-probe=v]", cssOnly), "css", "[name=\"checkout\"]", List.of());
        // No text tier with cssOnly: the structural path is used instead.
        assertSuggestion(cssOnlyAt("[data-graft-probe=w]", cssOnly), "css",
                ":root > body > div:nth-of-type(2) > p", List.of());
    }

    private LocatorSuggestion cssOnlyAt(String css, Map<String, Object> opts) {
        return AnchoredLocatorScript.toSuggestion(page.locator(css).evaluate(AnchoredLocatorScript.source(), opts));
    }

    private LocatorSuggestion suggestFor(String html) {
        page.setContent(html);
        return PlaywrightSuggestedLocator.suggest(page.locator(TARGET));
    }

    private static String shadow(String hostExpr, String mode, String innerHtml) {
        return "<script>"
                + "const root = " + hostExpr + ".attachShadow({mode:'" + mode + "'});"
                + "root.innerHTML = '" + innerHtml + "';"
                + "</script>";
    }

    private static void assertSuggestion(LocatorSuggestion s, String kind, String value, List<String> within) {
        assertNotNull(s, "expected a suggestion");
        assertEquals(kind, s.kind(), s.toString());
        assertEquals(value, s.value(), s.toString());
        assertEquals(within, s.within(), s.toString());
    }

    /** The element's own locator, replayed on the page, resolves to exactly the target. */
    private void assertResolvesToTarget(LocatorSuggestion s) {
        Locator l = switch (s.kind()) {
            case "testId" -> page.getByTestId(s.value());
            case "text" -> page.getByText(s.value(), new Page.GetByTextOptions().setExact(true));
            case "id" -> page.locator("[id=\"" + s.value() + "\"]");
            case "name" -> page.locator("[name=\"" + s.value() + "\"]");
            case "css" -> page.locator(s.value());
            default -> throw new AssertionError("unexpected kind " + s.kind());
        };
        assertEquals(1, l.count(), "replay of " + s);
        assertEquals("t", l.getAttribute("data-graft-probe"), "replay of " + s);
    }
}
