package tech.ishabbi.graft.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tech.ishabbi.graft.AiFinder;
import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.HealEvent;
import tech.ishabbi.graft.HealListener;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingPageFactory;
import tech.ishabbi.graft.HealingSelector;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.Within;
import tech.ishabbi.graft.cache.MemoryLocatorStore;

/**
 * Heals into iframes (same-origin, cross-origin out-of-process, and interleaved with shadow roots)
 * with a fake Alumnium that returns a Frame-scoped locator, the way Alumnium does. Each case checks
 * the learned suggestion, its replay without the finder, an inline {@code within} locator, and that
 * a learned XPath under a shadow hop is a miss.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_BROWSER", matches = "true")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FrameReplayTest {

    /** Per-locator wait: Playwright treats a 0 ms waitFor as "no timeout". */
    private static final Duration MISS = Duration.ofMillis(300);
    private static final String STAMP = "css=[data-alumnium-id='7']";

    /** Shadow host whose LIGHT child is a button, so an XPath scoped to the host would match it. */
    private static final String CARD = "<x-card id=\"card\"><button>light</button></x-card>"
            + "<script>document.getElementById('card').attachShadow({mode:'open'}).innerHTML = '<slot></slot>';"
            + "</script>";

    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private HttpServer server;
    private MemoryLocatorStore store;
    private final List<HealEvent> events = new ArrayList<>();

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
        store = new MemoryLocatorStore();
    }

    @AfterEach
    void closePage() {
        page.close();
        if (server != null) server.stop(0);
    }

    static class PayPage {
        @Element(value = "the pay button", within = {"frame=#pay"}, testId = "pay-now", timeoutMs = 300)
        Locator pay;
    }

    @Test
    void sameOriginIframe() {
        page.setContent("<h1>Shop</h1><iframe id='pay' srcdoc=\""
                + attr("<button data-testid=\"pay-now\">Pay</button>" + CARD) + "\"></iframe>");
        Frame frame = childFrame();
        LocatorSuggestion expected = new LocatorSuggestion("testId", "pay-now", List.of("frame=#pay"));

        healAndReplay(frame, "[data-testid=pay-now]", "Pay", expected);

        // Inline: @Element(within = "frame=#pay", testId = "pay-now") resolves without a heal.
        AtomicInteger calls = new AtomicInteger();
        PayPage pay = new PayPage();
        HealingPageFactory.initElements(pay, healer(throwing(calls)));
        assertEquals("Pay", pay.pay.textContent());
        assertEquals(0, calls.get());

        learnedXpathUnderShadowMisses(frame, List.of("frame=#pay", "shadow=#card"), "Pay", expected);
    }

    @Test
    void crossOriginIframe() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        String child = "<p>Card</p><button name=\"card-number\">Card number</button>" + CARD;
        String parent = "<h1>Shop</h1><iframe name='checkout' src='http://127.0.0.1:" + port + "/child'></iframe>";
        server.createContext("/", ex -> respond(ex, "/child".equals(ex.getRequestURI().getPath()) ? child : parent));
        server.start();

        page.navigate("http://localhost:" + port + "/");
        Frame frame = childFrame();
        assertEquals("http://127.0.0.1:" + port + "/child", frame.url());
        assertFalse((Boolean) page.evaluate("() => !!document.querySelector('iframe').contentDocument"),
                "the iframe must be cross-origin");
        LocatorSuggestion expected = new LocatorSuggestion("name", "card-number", List.of("frame=[name=\"checkout\"]"));

        healAndReplay(frame, "[name=card-number]", "Card number", expected);
        inlineResolves(HealingSelector.of("[name=card-number]", "the card number button")
                .within("frame=[name=\"checkout\"]"), "Card number");
        learnedXpathUnderShadowMisses(frame, List.of("frame=[name=\"checkout\"]", "shadow=#card"), "Card number",
                expected);
    }

    @Test
    void shadowFrameShadowInterleaved() {
        String inner = "<x-pay id=\"pay\"><button>light</button></x-pay>"
                + "<script>document.getElementById('pay').attachShadow({mode:'open'}).innerHTML ="
                + " '<slot></slot><button aria-label=\"Confirm\">Confirm</button>';</script>";
        page.setContent("<h1>Shop</h1><x-shell id='shell'></x-shell><script>"
                + "const f = document.createElement('iframe');"
                + "f.setAttribute('data-testid', 'pay-frame');"
                + "f.srcdoc = " + js(inner) + ";"
                + "document.getElementById('shell').attachShadow({mode:'open'}).appendChild(f);"
                + "</script>");
        Frame frame = childFrame();
        List<String> within = List.of("shadow=#shell", "frame=[data-testid=\"pay-frame\"]", "shadow=#pay");
        LocatorSuggestion expected = new LocatorSuggestion("css", "[aria-label=\"Confirm\"]", within);

        healAndReplay(frame, "[aria-label=Confirm]", "Confirm", expected);
        inlineResolves(HealingSelector.of("[aria-label=Confirm]", "the confirm button")
                .within(within.toArray(String[]::new)), "Confirm");
        learnedXpathUnderShadowMisses(frame, within, "Confirm", expected);
    }

    // ---- checks ------------------------------------------------------------------------------

    /** Checks 1 and 2: the heal learns {@code expected}; a fresh healer replays it without the finder. */
    private void healAndReplay(Frame frame, String targetCss, String text, LocatorSuggestion expected) {
        Locator target = frame.locator(targetCss);
        assertEquals(1, target.count(), "fixture target " + targetCss);
        target.evaluate("e => e.setAttribute('data-alumnium-id', '7')");

        HealingSelector sel = HealingSelector.of("#gone", "the target").timeout(MISS);
        AtomicInteger heals = new AtomicInteger();
        PlaywrightHealer first = healer(d -> {
            heals.incrementAndGet();
            return frame.locator(STAMP);
        });
        assertEquals(text, first.locator(sel).textContent());
        assertEquals(1, heals.get());
        assertEquals(1, events.size(), String.valueOf(events));
        assertEquals(expected, events.get(0).suggestion());
        assertEquals(expected, store.get(sel.key()).orElseThrow().suggestion());
        first.close();

        AtomicInteger calls = new AtomicInteger();
        PlaywrightHealer second = healer(throwing(calls));
        assertEquals(text, second.locator(sel).textContent());
        assertEquals(0, calls.get(), "replay must not ask the finder");
        second.close();
    }

    /** Check 3: an inline {@code within} locator resolves directly, with no heal. */
    private void inlineResolves(HealingSelector selector, String text) {
        AtomicInteger calls = new AtomicInteger();
        PlaywrightHealer h = healer(throwing(calls));
        assertEquals(text, h.locator(selector.timeout(MISS)).textContent());
        assertEquals(0, calls.get());
        h.close();
    }

    /** Check 4: a learned XPath under a shadow hop is a miss (Alumnium runs) though it would match the host's light DOM. */
    private void learnedXpathUnderShadowMisses(Frame frame, List<String> within, String text, LocatorSuggestion relearned) {
        assertTrue(PlaywrightScope.enter(page, Within.parseAll(within)).locator("xpath=.//button").count() > 0,
                "unguarded, the XPath would match the host's light child");
        HealingSelector sel = HealingSelector.of("#also-gone", "the xpath target").timeout(MISS);
        store.learn(sel.key(), new LocatorSuggestion("xpath", ".//button", within), "test", "PLAYWRIGHT");

        AtomicInteger heals = new AtomicInteger();
        PlaywrightHealer h = healer(d -> {
            heals.incrementAndGet();
            return frame.locator(STAMP);
        });
        assertEquals(text, h.locator(sel).textContent());
        assertEquals(1, heals.get(), "the learned XPath must miss, so Alumnium runs");
        assertEquals(relearned, store.get(sel.key()).orElseThrow().suggestion());
        h.close();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private PlaywrightHealer healer(AiFinder finder) {
        HealListener listener = events::add;
        return PlaywrightHealer.withFinder(page, finder, HealingConfig.builder().reportPath(null)
                .learnedStore(store).locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                .addListener(listener).build());
    }

    private static AiFinder throwing(AtomicInteger calls) {
        return d -> {
            calls.incrementAndGet();
            throw new IllegalStateException("finder must not be called for \"" + d + "\"");
        };
    }

    /** The only child frame, loaded. */
    private Frame childFrame() {
        List<Frame> children = page.frames().stream().filter(f -> f.parentFrame() != null).toList();
        assertEquals(1, children.size(), "child frames");
        Frame frame = children.get(0);
        frame.waitForLoadState();
        assertNotNull(frame.parentFrame());
        return frame;
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] bytes = ("<!doctype html><html><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** HTML double-quoted attribute value. */
    private static String attr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    /** A single-quoted JS string literal that is safe inside a script element. */
    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("</", "<\\/") + "'";
    }
}
