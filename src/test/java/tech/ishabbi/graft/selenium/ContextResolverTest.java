package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingPageFactory;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.Within;
import tech.ishabbi.graft.cache.MemoryLocatorStore;
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
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #pay)", "frame(pay)",
                "find(pay, By.cssSelector: button.pay)"), log);

        log.clear();
        assertEquals("4242", page.number.getText());
        assertEquals(List.of("defaultContent", "find(top, By.cssSelector: #card)", "getShadowRoot(card)",
                "find(card#shadow, By.cssSelector: [id=\"number\"])"), log);
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
}
