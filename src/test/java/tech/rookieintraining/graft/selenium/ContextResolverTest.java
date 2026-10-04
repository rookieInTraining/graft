package tech.rookieintraining.graft.selenium;

import tech.rookieintraining.graft.Element;
import tech.rookieintraining.graft.HealingConfig;
import tech.rookieintraining.graft.HealingPageFactory;
import tech.rookieintraining.graft.LocatorSuggestion;
import tech.rookieintraining.graft.Within;
import tech.rookieintraining.graft.cache.MemoryLocatorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ContextResolver} and within-aware resolution, against recording stubs (no browser). */
class ContextResolverTest {

    private final RecordingStubs.Driver driver = new RecordingStubs.Driver();
    private final List<String> log = driver.log;
    private final List<SeleniumHealer> open = new ArrayList<>();

    @AfterEach
    void closeAll() { open.forEach(SeleniumHealer::close); }

    private RecordingStubs.Element el(String name) { return new RecordingStubs.Element(name, log); }

    @Test
    void emptyWithinMakesNoCalls() {
        ContextResolver.Scope scope = ContextResolver.enter(driver, driver, List.of());
        assertSame(driver, scope.context());
        assertFalse(scope.inShadow());

        RecordingStubs.Element parent = el("form");
        assertSame(parent, ContextResolver.enter(driver, parent, List.of()).context());
        assertEquals(List.of(), log);
    }

    @Test
    void driverContextStartsAtTheTopAndAppliesHopsInOrder() {
        RecordingStubs.Element iframe = el("pay");
        RecordingStubs.Element host = el("card");
        RecordingStubs.Shadow shadow = host.attachShadow();
        driver.put("top", By.cssSelector("#pay"), iframe).put("pay", By.cssSelector("x-card"), host);
        driver.frame = "unrelated";   // the test was inside some other frame

        ContextResolver.Scope scope = ContextResolver.enter(driver, driver,
                Within.parseAll("frame=#pay", "shadow=x-card"));

        assertSame(shadow, scope.context());
        assertTrue(scope.inShadow());
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #pay)", "frame(pay)",
                "find(pay, By.cssSelector: x-card)", "getShadowRoot(card)"), log);
    }

    @Test
    void frameInsideAShadowRootLeavesTheShadow() {
        RecordingStubs.Element host = el("shell");
        RecordingStubs.Element iframe = el("inner");
        host.attachShadow().put(By.cssSelector("iframe"), iframe);
        driver.put("top", By.cssSelector("#shell"), host);

        ContextResolver.Scope scope = ContextResolver.enter(driver, driver,
                Within.parseAll("shadow=#shell", "frame=iframe"));

        assertSame(driver, scope.context());
        assertFalse(scope.inShadow());
        assertEquals("inner", driver.frame);
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #shell)", "getShadowRoot(shell)",
                "find(shell#shadow, By.cssSelector: iframe)", "frame(inner)"), log);
    }

    @Test
    void elementContextStartsInTheElementWithoutDefaultContent() {
        RecordingStubs.Element form = el("form");
        RecordingStubs.Element host = el("widget");
        RecordingStubs.Shadow shadow = host.attachShadow();
        form.child(By.cssSelector("x-widget"), host);

        ContextResolver.Scope scope = ContextResolver.enter(driver, form, Within.parseAll("shadow=x-widget"));

        assertSame(shadow, scope.context());
        assertEquals(List.of("find(form, By.cssSelector: x-widget)", "getShadowRoot(widget)"), log);
    }

    @Test
    void missingShadowRootIsANoSuchElement() {
        driver.put("top", By.cssSelector("#plain"), el("plain"));
        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> ContextResolver.enter(driver, driver, Within.parseAll("shadow=#plain")));
        assertTrue(e.getMessage().contains("shadow=#plain"), e.getMessage());
    }

    @Test
    void missingIframeOrHostNamesTheHop() {
        NoSuchElementException frame = assertThrows(NoSuchElementException.class,
                () -> ContextResolver.enter(driver, driver, Within.parseAll("frame=#pay")));
        assertTrue(frame.getMessage().startsWith("Cannot enter frame=#pay: "), frame.getMessage());
        assertTrue(frame.getCause() instanceof NoSuchElementException, String.valueOf(frame.getCause()));

        NoSuchElementException host = assertThrows(NoSuchElementException.class,
                () -> ContextResolver.enter(driver, driver, Within.parseAll("shadow=#card")));
        assertTrue(host.getMessage().startsWith("Cannot enter shadow=#card: "), host.getMessage());
        assertTrue(host.getCause() instanceof NoSuchElementException, String.valueOf(host.getCause()));
    }

    // ---- resolution through within ---------------------------------------------------------

    private SeleniumHealer healer(MemoryLocatorStore store, AtomicInteger finderCalls) {
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(store)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        SeleniumHealer h = SeleniumHealer.withFinder(driver, d -> {
            finderCalls.incrementAndGet();
            throw new IllegalStateException("finder must not be called");
        }, config);
        open.add(h);
        return h;
    }

    @Test
    void healingByResolvesInsideTheShadowRoot() {
        RecordingStubs.Element host = el("host");
        RecordingStubs.Element button = el("x");
        host.attachShadow().put(By.cssSelector("button.x"), button);
        driver.put("top", By.cssSelector("#host"), host);
        AtomicInteger calls = new AtomicInteger();
        healer(new MemoryLocatorStore(), calls);

        WebElement found = driver.findElement(
                HealingBy.of(By.cssSelector("button.x"), "the x button").within("shadow=#host"));

        assertSame(button, found);
        assertEquals(0, calls.get());
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #host)", "getShadowRoot(host)",
                "find(host#shadow, By.cssSelector: button.x)"), log);
    }

    static class PayPage {
        @Element(value = "the pay button", within = {"frame=#pay"}, css = "button.pay")
        WebElement pay;

        @Element(value = "the card number", within = {"shadow=#card"}, id = "number")
        WebElement number;
    }

    @Test
    void elementFieldResolvesThroughWithin() {
        RecordingStubs.Element iframe = el("pay");
        RecordingStubs.Element button = el("button").attr("text", "Pay");
        RecordingStubs.Element host = el("card");
        RecordingStubs.Element number = el("number").attr("text", "4242");
        host.attachShadow().put(By.cssSelector("[id=\"number\"]"), number);   // CSS-only mapping in a shadow root
        driver.put("top", By.cssSelector("#pay"), iframe).put("pay", By.cssSelector("button.pay"), button)
                .put("top", By.cssSelector("#card"), host);
        AtomicInteger calls = new AtomicInteger();
        PayPage page = new PayPage();
        HealingPageFactory.initElements(page, healer(new MemoryLocatorStore(), calls));

        assertEquals("Pay", page.pay.getText());
        // Resolve (then restore the test's frame), and enter the frame again for the call (then restore).
        // The stub has no JS, so the captured frame state is the top.
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #pay)", "frame(pay)",
                "find(pay, By.cssSelector: button.pay)", "defaultContent",
                "defaultContent", "find(top, By.cssSelector: #pay)", "frame(pay)", "defaultContent"), log);
        assertEquals("top", driver.frame);

        log.clear();
        assertEquals("4242", page.number.getText());
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #card)", "getShadowRoot(card)",
                "find(card#shadow, By.cssSelector: [id=\"number\"])", "defaultContent"), log);
        assertEquals(0, calls.get());
    }

    @Test
    void learnedSuggestionReplaysThroughItsWithin() {
        RecordingStubs.Element host = el("host");
        RecordingStubs.Element button = el("save");
        host.attachShadow().put(By.cssSelector("[data-testid=\"save\"]"), button);
        driver.put("top", By.cssSelector("#host"), host);
        HealingBy save = HealingBy.of(By.id("old-save"), "the save button");
        MemoryLocatorStore store = new MemoryLocatorStore();
        store.learn(save.key(), LocatorSuggestion.of("testId", "save", List.of("shadow=#host")), "here", "SELENIUM");
        AtomicInteger calls = new AtomicInteger();
        healer(store, calls);

        assertSame(button, driver.findElement(save));
        assertEquals(0, calls.get());
    }

    @Test
    void healUnderAFrameHopStartsFromTheTestsFrame() {
        RecordingStubs.Element iframe = el("pay");
        RecordingStubs.Element found = el("found").attr("id", "pay-btn");
        driver.put("top", By.cssSelector("#pay"), iframe);
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : null;
        List<String> framesSeen = new ArrayList<>();
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(new MemoryLocatorStore())
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        open.add(SeleniumHealer.withFinder(driver, d -> {
            framesSeen.add(driver.frame);
            return found;
        }, config));

        WebElement el = driver.findElement(
                HealingBy.of(By.cssSelector("button.gone"), "the pay button").within("frame=#pay"));

        assertSame(found, el);
        assertEquals(List.of("top"), framesSeen, "Alumnium must not inherit the iframe the primary searched");
    }

    @Test
    void aLearnedMissInsideAFrameReturnsToTheTestsFrameBeforeAlumnium() {
        driver.put("top", By.cssSelector("#pay"), el("pay"));   // the frame is there, the element is not
        RecordingStubs.Element found = el("found");
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : null;
        HealingBy save = HealingBy.of(By.id("old-save"), "the save button").proxied();
        MemoryLocatorStore store = new MemoryLocatorStore();
        store.learn(save.key(), LocatorSuggestion.of("testId", "save", List.of("frame=#pay")), "here", "SELENIUM");
        List<String> framesSeen = new ArrayList<>();
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(store)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        open.add(SeleniumHealer.withFinder(driver, d -> {
            framesSeen.add(driver.frame);
            return found;
        }, config));

        driver.findElement(save).click();

        assertTrue(log.contains("frame(pay)"), "the learned locator was tried inside the frame: " + log);
        assertEquals(List.of("top"), framesSeen, "Alumnium must not inherit the learned locator's frame");
    }

    @Test
    void learnedTextInsideAShadowRootIsAMiss() {
        RecordingStubs.Element host = el("host");
        host.attachShadow();
        driver.put("top", By.cssSelector("#host"), host);
        HealingBy save = HealingBy.of(By.id("old-save"), "the save button");
        MemoryLocatorStore store = new MemoryLocatorStore();
        store.learn(save.key(), LocatorSuggestion.of("text", "Save", List.of("shadow=#host")), "here", "SELENIUM");
        AtomicInteger calls = new AtomicInteger();
        healer(store, calls);

        assertThrows(RuntimeException.class, () -> driver.findElement(save));
        assertEquals(1, calls.get(), "the learned text locator is unusable in a shadow root, so Alumnium runs");
        assertTrue(log.stream().noneMatch(l -> l.startsWith("find(host#shadow")), log.toString());
    }

    // ---- a cached element whose iframe is gone -----------------------------------------------

    static class MovedPage {
        @Element(value = "the pay button", css = "button.pay")
        WebElement pay;
    }

    /**
     * {@code button.pay} is not at the top, but a learned locator finds it inside {@code frame=#pay},
     * which caches it with that frame hop.
     */
    private MovedPage cacheInsideTheFrame(MemoryLocatorStore store, AtomicInteger calls) {
        RecordingStubs.Element iframe = el("pay");
        driver.put("top", By.cssSelector("#pay"), iframe)
                .put("pay", By.cssSelector("[data-testid=\"pay\"]"), el("framed").attr("text", "Pay (framed)"));
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : null;
        String key = tech.rookieintraining.graft.ElementSpec.of(MovedPage.class, fieldOf("pay")).key();
        store.learn(key, LocatorSuggestion.of("testId", "pay", List.of("frame=#pay")), "here", "SELENIUM");
        MovedPage page = new MovedPage();
        HealingPageFactory.initElements(page, healer(store, calls));
        assertEquals("Pay (framed)", page.pay.getText());
        return page;
    }

    private static java.lang.reflect.Field fieldOf(String name) {
        try {
            return MovedPage.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aProxyWhoseCachedIframeIsGoneReResolvesInsteadOfFailing() {
        AtomicInteger calls = new AtomicInteger();
        MovedPage page = cacheInsideTheFrame(new MemoryLocatorStore(), calls);

        // The iframe is removed and the button now sits at the top: the cached hop cannot be entered.
        driver.frames.get("top").remove(By.cssSelector("#pay").toString());
        driver.put("top", By.cssSelector("button.pay"), el("moved").attr("text", "Pay (moved)"));

        assertEquals("Pay (moved)", page.pay.getText());
        assertEquals("Pay (moved)", page.pay.getText(), "the fresh element is cached, not the lost one");
        assertEquals("top", driver.frame);
        assertEquals(0, calls.get());
    }

    @Test
    void aRawHealingByWhoseCachedIframeIsGoneReResolvesFromTheTestsFrame() {
        RecordingStubs.Element iframe = el("pay");
        driver.put("top", By.cssSelector("#pay"), iframe)
                .put("pay", By.cssSelector("[data-testid=\"pay\"]"), el("framed"));
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : null;
        HealingBy pay = HealingBy.of(By.cssSelector("button.pay"), "the pay button");
        MemoryLocatorStore store = new MemoryLocatorStore();
        store.learn(pay.key(), LocatorSuggestion.of("testId", "pay", List.of("frame=#pay")), "here", "SELENIUM");
        AtomicInteger calls = new AtomicInteger();
        healer(store, calls);
        assertEquals("framed", driver.findElement(pay).toString());
        assertEquals("pay", driver.frame);
        driver.switchTo().defaultContent();

        driver.frames.get("top").remove(By.cssSelector("#pay").toString());
        RecordingStubs.Element moved = el("moved");
        driver.put("top", By.cssSelector("button.pay"), moved);

        assertSame(moved, driver.findElement(pay));
        assertEquals("top", driver.frame);
        assertEquals(0, calls.get());
    }

    @Test
    void aFrameBoundChildWhoseIframeIsGoneIsStale() {
        AtomicInteger calls = new AtomicInteger();
        healer(new MemoryLocatorStore(), calls);
        SeleniumHealer h = open.get(open.size() - 1);
        driver.js = script -> script.equals(FrameState.IS_TOP_JS) ? Boolean.TRUE : null;
        WebElement child = (WebElement) FrameBoundElement.bind(h,
                new Located(el("parent"), Within.parseAll("frame=#pay"), driver), el("child"));

        org.openqa.selenium.StaleElementReferenceException stale = assertThrows(
                org.openqa.selenium.StaleElementReferenceException.class, child::getText);
        assertTrue(stale.getCause() instanceof NoSuchElementException, String.valueOf(stale.getCause()));
        assertTrue(org.openqa.selenium.support.ui.ExpectedConditions.stalenessOf(child).apply(driver));
        assertEquals("top", driver.frame);
    }
}
