package tech.rookieintraining.graft.selenium;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tech.rookieintraining.graft.AiFinder;
import tech.rookieintraining.graft.Element;
import tech.rookieintraining.graft.HealEvent;
import tech.rookieintraining.graft.HealListener;
import tech.rookieintraining.graft.HealingConfig;
import tech.rookieintraining.graft.HealingPageFactory;
import tech.rookieintraining.graft.LocatorSuggestion;
import tech.rookieintraining.graft.cache.MemoryLocatorStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenium heals into iframes (same-origin, cross-origin, and a shadow host holding an iframe
 * holding a shadow host) with a fake Alumnium that, like the real one, switches into the target's
 * frame chain and leaves the driver there. Each fixture checks the learned {@code within}, that
 * the driver is restored after proxied calls (also when the test sits in another iframe), replay
 * without the finder, an inline {@code within}, and that a raw {@code HealingBy} leaves the driver
 * in the element's frame.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_BROWSER", matches = "true")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SeleniumFrameTest {

    /** The unrelated iframe B every fixture has, and its content. */
    private static final String OTHER = "<iframe id=\"other\" srcdoc=\"&lt;p id=&quot;b-content&quot;&gt;B&lt;/p&gt;\"></iframe>";

    private static final String SAME = "<h1 id=\"title\">Shop</h1>"
            + "<iframe id=\"pay\" srcdoc=\"&lt;form id=&quot;card&quot;&gt;"
            + "&lt;button type=&quot;button&quot; data-testid=&quot;pay-now&quot;&gt;Pay&lt;/button&gt;"
            + "&lt;span class=&quot;hint&quot;&gt;One&lt;/span&gt;&lt;span class=&quot;hint&quot;&gt;Two&lt;/span&gt;"
            + "&lt;/form&gt;\"></iframe>"
            + OTHER;

    private static final String INNER = "<x-pay id=\"pay\"><button>light</button></x-pay>"
            + "<script>document.getElementById('pay').attachShadow({mode:'open'}).innerHTML ="
            + " '<slot></slot><button aria-label=\"Confirm\">Confirm</button>';</script>";

    private static final String INTERLEAVED = "<h1 id=\"title\">Shop</h1><x-shell id=\"shell\"></x-shell>" + OTHER
            + "<script>const f = document.createElement('iframe');"
            + "f.setAttribute('data-testid', 'pay-frame');"
            + "f.srcdoc = " + js(INNER) + ";"
            + "document.getElementById('shell').attachShadow({mode:'open'}).appendChild(f);</script>";

    private static final String CHILD = "<p>Card</p><button name=\"card-number\">Card number</button>";

    private static WebDriver driver;
    private static HttpServer server;
    private static int port;

    private MemoryLocatorStore store;
    private final List<HealEvent> events = new ArrayList<>();
    private final List<SeleniumHealer> open = new ArrayList<>();

    /** One fixture: its page, how Alumnium reaches the target, and what should be learned. */
    private record Fixture(String path, Function<WebDriver, WebElement> alumnium, LocatorSuggestion expected,
                           String text, By inFrame) {}

    private static final Fixture SAME_ORIGIN = new Fixture("/same",
            d -> {
                d.switchTo().frame(d.findElement(By.id("pay")));
                return d.findElement(By.cssSelector("[data-testid=pay-now]"));
            },
            new LocatorSuggestion("testId", "pay-now", List.of("frame=#pay")),
            "Pay", By.cssSelector("[data-testid=pay-now]"));

    private static final Fixture CROSS_ORIGIN = new Fixture("/cross",
            d -> {
                d.switchTo().frame(d.findElement(By.name("checkout")));
                return d.findElement(By.name("card-number"));
            },
            new LocatorSuggestion("name", "card-number", List.of("frame=[name=\"checkout\"]")),
            "Card number", By.name("card-number"));

    private static final Fixture SHADOW_FRAME_SHADOW = new Fixture("/interleaved",
            d -> {
                d.switchTo().frame(d.findElement(By.id("shell")).getShadowRoot().findElement(By.cssSelector("iframe")));
                return d.findElement(By.id("pay")).getShadowRoot().findElement(By.cssSelector("[aria-label=Confirm]"));
            },
            new LocatorSuggestion("css", "[aria-label=\"Confirm\"]",
                    List.of("shadow=#shell", "frame=[data-testid=\"pay-frame\"]", "shadow=#pay")),
            "Confirm", By.id("pay"));

    @BeforeAll
    static void launch() throws IOException {
        driver = BrowserDrivers.headlessChrome();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", ex -> respond(ex, switch (ex.getRequestURI().getPath()) {
            case "/same" -> SAME;
            case "/interleaved" -> INTERLEAVED;
            case "/cross" -> "<h1 id=\"title\">Shop</h1><iframe name=\"checkout\" src=\"http://127.0.0.1:" + port
                    + "/child\"></iframe>" + OTHER;
            case "/child" -> CHILD;
            default -> "";
        }));
        server.start();
    }

    @AfterAll
    static void quit() {
        if (driver != null) driver.quit();
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void fresh() {
        store = new MemoryLocatorStore();
        events.clear();
    }

    @AfterEach
    void closeHealers() {
        open.forEach(SeleniumHealer::close);
        open.clear();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    @Test
    void sameOriginIframe() {
        load(SAME_ORIGIN);
        runChecks(SAME_ORIGIN, HealingBy.of(By.cssSelector("[data-testid=pay-now]"), "inline pay").within("frame=#pay"));

        // @Element(within = "frame=#pay", ...) through a page-object proxy.
        AtomicInteger calls = new AtomicInteger();
        PayPage page = new PayPage();
        HealingPageFactory.initElements(page, healer(throwing(calls)));
        assertEquals("Pay", page.pay.getText());
        assertAtTop();
        assertEquals(0, calls.get());
        assertNoGraftMarkers();
    }

    @Test
    void childrenOfAFramedProxyStayUsable() {
        load(SAME_ORIGIN);
        AtomicInteger calls = new AtomicInteger();
        healer(throwing(calls));
        WebElement card = driver.findElement(
                HealingBy.of(By.id("card"), "the card form").within("frame=#pay").proxied());

        // Container -> child: the child is usable after the driver is back at the top, and from B.
        WebElement pay = card.findElement(By.cssSelector("[data-testid=pay-now]"));
        assertAtTop();
        assertEquals("Pay", pay.getText());
        pay.click();
        assertAtTop();
        enterOther();
        assertEquals("Pay", pay.getText());
        assertInOther();
        driver.switchTo().defaultContent();

        // findElements children, and a child of a child.
        List<WebElement> hints = card.findElements(By.cssSelector(".hint"));
        assertAtTop();
        assertEquals(List.of("One", "Two"), hints.stream().map(WebElement::getText).toList());
        assertAtTop();
        WebElement form = pay.findElement(By.xpath(".."));
        assertEquals("form", form.getTagName());
        assertAtTop();

        // A raw HealingBy through the proxy comes back frame-bound too.
        WebElement viaHealingBy = card.findElement(
                HealingBy.of(By.cssSelector("[data-testid=pay-now]"), "the pay button in the card"));
        assertAtTop();
        assertEquals("Pay", viaHealingBy.getText());
        assertAtTop();
        assertEquals(0, calls.get());
        assertNoGraftMarkers();
    }

    @Test
    void aNestedHealInsideAProxyCallReturnsToTheTestsFrame() {
        load(SAME_ORIGIN);
        AtomicInteger heals = new AtomicInteger();
        healer(alumnium(SAME_ORIGIN, heals));
        WebElement card = driver.findElement(
                HealingBy.of(By.id("card"), "the card form").within("frame=#pay").proxied());
        enterPay();   // the test itself sits in iframe A, the card's own frame

        // The proxy captures A, and the raw HealingBy heal inside the call captures A again.
        WebElement pay = card.findElement(HealingBy.of(By.cssSelector("#gone"), "the pay button, healed in the card"));
        assertEquals(1, heals.get());
        assertInPay();
        assertEquals("Pay", pay.getText());
        assertInPay();
        driver.switchTo().defaultContent();
        assertNoGraftMarkers();
    }

    @Test
    void crossOriginIframe() {
        load(CROSS_ORIGIN);
        assertEquals(Boolean.FALSE, ((JavascriptExecutor) driver).executeScript(
                "return !!document.querySelector('iframe[name=checkout]').contentDocument;"),
                "the iframe must be cross-origin");
        runChecks(CROSS_ORIGIN, HealingBy.of(By.name("card-number"), "inline card").within("frame=[name=\"checkout\"]"));
    }

    @Test
    void shadowFrameShadowInterleaved() {
        load(SHADOW_FRAME_SHADOW);
        runChecks(SHADOW_FRAME_SHADOW, HealingBy.of(By.cssSelector("[aria-label=Confirm]"), "inline confirm")
                .within("shadow=#shell", "frame=[data-testid=\"pay-frame\"]", "shadow=#pay"));
    }

    /** Removes iframe A and re-adds it, now or after {@code arguments[0]} ms, with new content. */
    private static final String REPLACE_PAY_JS = "const old = document.getElementById('pay');"
            + " const parent = old.parentNode; const next = old.nextSibling; old.remove();"
            + " const add = () => { const f = document.createElement('iframe'); f.id = 'pay';"
            + " f.srcdoc = '<form id=\"card\"><button type=\"button\" data-testid=\"pay-now\">Pay again</button></form>';"
            + " parent.insertBefore(f, next); };"
            + " if (arguments[0] > 0) setTimeout(add, arguments[0]); else add();";

    @Test
    void aCachedHealWhoseIframeIsReplacedRecovers() {
        cachedHealRecoversAfterTheIframeIsReAdded(0);
    }

    @Test
    void aCachedHealWhoseIframeComesBackLateRecovers() {
        cachedHealRecoversAfterTheIframeIsReAdded(700);
    }

    private void cachedHealRecoversAfterTheIframeIsReAdded(long delayMillis) {
        load(SAME_ORIGIN);
        AtomicInteger heals = new AtomicInteger();
        healer(patientAlumnium(heals));
        WebElement el = driver.findElement(HealingBy.of(By.cssSelector("#gone"), "the target").proxied());
        assertEquals("Pay", el.getText());
        assertEquals(1, heals.get());
        assertAtTop();

        ((JavascriptExecutor) driver).executeScript(REPLACE_PAY_JS, delayMillis);

        // The cached element's iframe is gone (or not back yet): the proxy re-resolves, it does not fail.
        assertEquals("Pay again", el.getText());
        assertAtTop();
        assertEquals("Pay again", el.getText(), "the fresh element is cached");
        assertAtTop();
        assertTrue(heals.get() <= 2, "at most one more Alumnium call: " + heals.get());
        assertEquals(LocatorSuggestion.of("testId", "pay-now", List.of("frame=#pay")),
                store.get(HealingBy.of(By.cssSelector("#gone"), "the target").key()).orElseThrow().suggestion());

        // The test sits in iframe B: the recovered proxy still comes back to B.
        enterOther();
        el.click();
        assertInOther();
        driver.switchTo().defaultContent();
        assertNoGraftMarkers();
    }

    @Test
    void aFrameBoundChildGoesStaleWhenItsIframeIsReplacedOrRemoved() {
        load(SAME_ORIGIN);
        healer(throwing(new AtomicInteger()));
        WebElement card = driver.findElement(
                HealingBy.of(By.id("card"), "the card form").within("frame=#pay").proxied());
        WebElement pay = card.findElement(By.cssSelector("[data-testid=pay-now]"));
        assertFalse(ExpectedConditions.stalenessOf(pay).apply(driver));
        // A missing child of a live frame-bound element is still a plain NoSuchElementException.
        assertThrows(org.openqa.selenium.NoSuchElementException.class, () -> pay.findElement(By.id("nothing")));
        assertAtTop();

        ((JavascriptExecutor) driver).executeScript(REPLACE_PAY_JS, 0);
        assertTrue(ExpectedConditions.stalenessOf(pay).apply(driver), "replaced iframe");
        assertAtTop();

        new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> (Boolean) ((JavascriptExecutor) d).executeScript(
                "const f = document.getElementById('pay');"
                        + " return !!(f && f.contentDocument && f.contentDocument.querySelector('[data-testid=pay-now]'));"));
        WebElement again = card.findElement(By.cssSelector("[data-testid=pay-now]"));
        assertEquals("Pay again", again.getText());
        ((JavascriptExecutor) driver).executeScript("document.getElementById('pay').remove();");
        assertTrue(ExpectedConditions.stalenessOf(again).apply(driver), "removed iframe");
        assertAtTop();
        assertNoGraftMarkers();
    }

    static class PayPage {
        @Element(value = "the pay button", within = {"frame=#pay"}, testId = "pay-now")
        WebElement pay;
    }

    static class TargetPage {
        @Element(value = "the target, healed from inside iframe B", css = "#gone")
        WebElement target;
    }

    // ---- checks --------------------------------------------------------------------------------

    private void runChecks(Fixture f, HealingBy inline) {
        // 1 + 2: a proxied heal learns the exact within, and the driver is back at the top afterwards.
        HealingBy by = HealingBy.of(By.cssSelector("#gone"), "the target").proxied();
        AtomicInteger heals = new AtomicInteger();
        SeleniumHealer first = healer(alumnium(f, heals));
        WebElement el = driver.findElement(by);
        assertEquals(f.text(), el.getText());
        assertEquals(1, heals.get());
        assertAtTop();
        assertEquals(1, events.size(), String.valueOf(events));
        assertEquals(f.expected(), events.get(0).suggestion());
        assertEquals(f.expected(), store.get(by.key()).orElseThrow().suggestion());

        // 3: the test sits in an unrelated iframe B; the cached proxy enters A and comes back to B.
        enterOther();
        assertEquals(f.text(), el.getText());
        assertInOther();
        el.click();
        assertInOther();

        // 3 (heal): an @Element heal started from inside B also returns to B.
        TargetPage page = new TargetPage();
        HealingPageFactory.initElements(page, first);
        assertEquals(f.text(), page.target.getText());
        assertEquals(2, heals.get());
        assertInOther();
        driver.switchTo().defaultContent();
        assertNoGraftMarkers();
        first.close();
        open.remove(first);

        // 4: a fresh healer replays the learned within without the finder, and restores the driver.
        AtomicInteger calls = new AtomicInteger();
        healer(throwing(calls));
        WebElement replayed = driver.findElement(by);
        assertEquals(f.text(), replayed.getText());
        replayed.click();
        assertAtTop();
        enterOther();
        assertEquals(f.text(), replayed.getText());
        assertInOther();
        driver.switchTo().defaultContent();
        assertEquals(0, calls.get(), "replay must not ask the finder");

        // 5: an inline within resolves through the proxy, and the driver is restored.
        assertEquals(f.text(), driver.findElement(inline.proxied()).getText());
        assertAtTop();
        assertEquals(0, calls.get());

        // 6: raw HealingBy.findElement leaves the driver in the element's frame (documented).
        WebElement raw = driver.findElement(inline);
        assertEquals(f.text(), raw.getText());
        assertTrue(driver.findElements(By.id("title")).isEmpty(), "the driver stays in the element's frame");
        assertFalse(driver.findElements(f.inFrame()).isEmpty(), "the frame's own content is findable");
        driver.switchTo().defaultContent();
        assertEquals(0, calls.get());

        // 6 (from B): raw lookups and a raw heal started inside B leave no frame markers behind.
        enterOther();
        assertEquals(f.text(), driver.findElement(inline).getText());
        AtomicInteger rawHeals = new AtomicInteger();
        healer(alumnium(f, rawHeals));
        enterOther();
        assertEquals(f.text(), driver.findElement(HealingBy.of(By.cssSelector("#gone-raw"), "the target, raw")).getText());
        assertEquals(1, rawHeals.get());
        driver.switchTo().defaultContent();
        assertNoGraftMarkers();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void load(Fixture f) {
        driver.switchTo().defaultContent();
        driver.get("http://localhost:" + port + f.path());
        assertAtTop();
    }

    private static void assertAtTop() {
        assertEquals("Shop", driver.findElement(By.id("title")).getText(), "the driver is back at the top");
    }

    private static void enterOther() {
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(By.id("other")));
        assertInOther();
    }

    private static void enterPay() {
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(By.id("pay")));
        assertInPay();
    }

    private static void assertInPay() {
        assertFalse(driver.findElements(By.id("card")).isEmpty(), "the driver is in iframe A");
        assertTrue(driver.findElements(By.id("title")).isEmpty(), "the driver is in iframe A");
    }

    /** Every {@code data-graft-*} attribute in a document and its open shadow roots. */
    private static final String GRAFT_ATTRS_JS = "const bad = [];"
            + " const walk = (root) => { for (const n of root.querySelectorAll('*')) {"
            + " for (const a of n.getAttributeNames()) if (a.startsWith('data-graft-')) bad.push(n.localName + '[' + a + ']');"
            + " if (n.shadowRoot) walk(n.shadowRoot); } };"
            + " walk(document); return bad;";

    /** No probe or frame marker is left in any reachable document. Ends at the top. */
    private static void assertNoGraftMarkers() {
        driver.switchTo().defaultContent();
        List<String> found = new ArrayList<>();
        collectGraftAttributes(found, "top");
        driver.switchTo().defaultContent();
        assertEquals(List.of(), found, "leftover Graft attributes");
    }

    private static void collectGraftAttributes(List<String> found, String where) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        for (Object o : (List<?>) js.executeScript(GRAFT_ATTRS_JS)) found.add(where + ": " + o);
        List<?> frames = (List<?>) js.executeScript(FramePath.IFRAMES_JS);
        for (int i = 0; i < frames.size(); i++) {
            driver.switchTo().frame((WebElement) frames.get(i));
            collectGraftAttributes(found, where + " > iframe[" + i + "]");
            driver.switchTo().parentFrame();
        }
    }

    private static void assertInOther() {
        assertEquals("B", driver.findElement(By.id("b-content")).getText(), "the driver is back in iframe B");
    }

    private SeleniumHealer healer(AiFinder finder) {
        HealListener listener = events::add;
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(store)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).addListener(listener).build();
        SeleniumHealer h = SeleniumHealer.withFinder(driver, finder, config);
        open.add(h);
        return h;
    }

    /** Like Alumnium: start at the top, switch into the target's frame chain, and stay there. */
    private static AiFinder alumnium(Fixture f, AtomicInteger calls) {
        return description -> {
            calls.incrementAndGet();
            driver.switchTo().defaultContent();
            return f.alumnium().apply(driver);
        };
    }

    /**
     * Like Alumnium on a page that is still changing: waits (up to 10 s) for iframe A and its pay
     * button, then switches into it and stays there.
     */
    private static AiFinder patientAlumnium(AtomicInteger calls) {
        return description -> {
            calls.incrementAndGet();
            driver.switchTo().defaultContent();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            WebElement frame = wait.until(d -> d.findElements(By.id("pay")).stream().findFirst().orElse(null));
            driver.switchTo().frame(frame);
            return wait.until(d -> d.findElements(By.cssSelector("[data-testid=pay-now]")).stream().findFirst().orElse(null));
        };
    }

    private static AiFinder throwing(AtomicInteger calls) {
        return description -> {
            calls.incrementAndGet();
            throw new IllegalStateException("finder must not be called for \"" + description + "\"");
        };
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] bytes = ("<!doctype html><html><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A single-quoted JS string literal that is safe inside a script element. */
    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("</", "<\\/") + "'";
    }
}
