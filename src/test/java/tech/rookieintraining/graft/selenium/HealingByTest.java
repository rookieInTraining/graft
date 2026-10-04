package tech.rookieintraining.graft.selenium;

import tech.rookieintraining.graft.HealReport;
import tech.rookieintraining.graft.HealingConfig;
import tech.rookieintraining.graft.HealingException;
import tech.rookieintraining.graft.internal.AnchoredLocatorScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises HealingBy/AlumniumBy against a scripted WebDriver stub and a scripted AiFinder —
 * no browser, no Alumnium. Covers: heal on miss, session cache, findElements never healing,
 * learned-tier replay in a fresh healer, out-of-scope refusal, and strict mode.
 */
class HealingByTest {

    /** A WebElement stub that only knows a few attributes. */
    static final class StubElement implements WebElement {
        final String name;
        final Map<String, String> attrs = new HashMap<>();
        StubElement(String name) { this.name = name; }
        StubElement attr(String k, String v) { attrs.put(k, v); return this; }

        @Override public String getAttribute(String n) { return attrs.get(n); }
        @Override public String getDomAttribute(String n) { return attrs.get(n); }
        @Override public String getText() { return attrs.getOrDefault("text", ""); }
        @Override public String getTagName() { return "button"; }
        @Override public void click() {}
        @Override public void submit() {}
        @Override public void sendKeys(CharSequence... k) {}
        @Override public void clear() {}
        @Override public boolean isSelected() { return false; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isDisplayed() { return true; }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElement(this);   // as RemoteWebElement does
            throw new NoSuchElementException("child " + by + " not in " + name);
        }
        @Override public Point getLocation() { return new Point(0, 0); }
        @Override public Dimension getSize() { return new Dimension(1, 1); }
        @Override public Rectangle getRect() { return new Rectangle(0, 0, 1, 1); }
        @Override public String getCssValue(String p) { return ""; }
        @Override public <X> X getScreenshotAs(OutputType<X> t) { throw new UnsupportedOperationException(); }
        @Override public String toString() { return "StubElement(" + name + ")"; }
    }

    /** A WebDriver stub whose findElement is a lookup table keyed by By.toString(). */
    static final class StubDriver implements WebDriver, JavascriptExecutor {
        final Map<String, WebElement> elements = new HashMap<>();
        boolean jsContains = true;

        // RemoteWebDriver routes any non-Remotable By through by.findElement(this); mirror that.
        @Override public WebElement findElement(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElement(this);
            WebElement e = elements.get(by.toString());
            if (e == null) throw new NoSuchElementException("no such element: " + by);
            return e;
        }
        @Override public List<WebElement> findElements(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElements(this);
            WebElement e = elements.get(by.toString());
            return e == null ? List.of() : List.of(e);
        }
        /**
         * The anchored-locator script answers like the real one for a top-level stub element (its
         * data-testid, else its id, else nothing unique); every other script answers {@link #jsContains}.
         */
        @Override public Object executeScript(String script, Object... args) {
            if (!script.contains(AnchoredLocatorScript.source())) return jsContains;
            StubElement el = (StubElement) args[0];
            if (el.attrs.containsKey("data-testid")) {
                return Map.of("within", List.of(), "kind", "testId", "value", el.attrs.get("data-testid"));
            }
            if (el.attrs.containsKey("id")) return Map.of("within", List.of(), "kind", "id", "value", el.attrs.get("id"));
            return null;
        }
        @Override public Object executeAsyncScript(String script, Object... args) { return null; }
        @Override public void get(String url) {}
        @Override public String getCurrentUrl() { return ""; }
        @Override public String getTitle() { return ""; }
        @Override public String getPageSource() { return ""; }
        @Override public void close() {}
        @Override public void quit() {}
        @Override public Set<String> getWindowHandles() { return Set.of(); }
        @Override public String getWindowHandle() { return ""; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    private final List<SeleniumHealer> open = new ArrayList<>();

    private SeleniumHealer healer(StubDriver driver, Path learned, boolean strict, WebElement aiAnswer, AtomicInteger aiCalls) {
        HealingConfig config = HealingConfig.builder()
                .reportPath(null).learnedLocatorsPath(learned).strict(strict)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        SeleniumHealer h = SeleniumHealer.withFinder(driver, d -> {
            aiCalls.incrementAndGet();
            if (aiAnswer == null) throw new RuntimeException("Alumnium: nothing matches \"" + d + "\"");
            return aiAnswer;
        }, config);
        open.add(h);
        return h;
    }

    @AfterEach
    void closeAll() { open.forEach(SeleniumHealer::close); }

    @Test
    void healsOnMissAndCachesForTheSession(@TempDir Path dir) {
        StubDriver driver = new StubDriver();
        StubElement real = new StubElement("signin").attr("data-testid", "signin-btn").attr("text", "Sign in");
        AtomicInteger aiCalls = new AtomicInteger();
        SeleniumHealer healer = healer(driver, dir.resolve("learned.json"), false, real, aiCalls);
        int eventsBefore = HealReport.global().events().size();

        HealingBy signIn = HealingBy.of(By.id("login-old"), "the 'Sign in' button");

        assertSame(real, driver.findElement(signIn));
        assertSame(real, driver.findElement(signIn));            // cached: no second heal
        assertEquals(1, aiCalls.get());
        assertEquals(1, healer.healCount(signIn));

        var event = HealReport.global().events().get(eventsBefore);
        assertEquals("By.id: login-old", event.originalLocator());
        assertEquals("@Element(testId = \"signin-btn\")", event.suggestedLocator());
        assertTrue(event.origin().contains("HealingByTest"), "call site captured: " + event.origin());

        assertEquals(List.of(), driver.findElements(signIn), "findElements never heals");
    }

    @Test
    void freshHealerReplaysLearnedLocatorWithoutAlumnium(@TempDir Path dir) {
        Path learned = dir.resolve("learned.json");
        StubDriver driver = new StubDriver();
        StubElement real = new StubElement("signin").attr("data-testid", "signin-btn");
        HealingBy signIn = HealingBy.of(By.id("login-old"), "the 'Sign in' button");

        AtomicInteger firstCalls = new AtomicInteger();
        SeleniumHealer first = healer(driver, learned, false, real, firstCalls);
        driver.findElement(signIn);
        first.close();
        assertEquals(1, firstCalls.get());

        // Next run: the learned css now matches on the page; Alumnium is unreachable and never needed.
        driver.elements.put(By.cssSelector("[data-testid=\"signin-btn\"]").toString(), real);
        AtomicInteger secondCalls = new AtomicInteger();
        healer(driver, learned, false, null, secondCalls);
        assertSame(real, driver.findElement(signIn));
        assertEquals(0, secondCalls.get());
    }

    @Test
    void refusesToHealOutsideTheSearchContext(@TempDir Path dir) {
        StubDriver driver = new StubDriver();
        driver.jsContains = false;                                  // "parent.contains(el)" → false
        StubElement real = new StubElement("elsewhere");
        healer(driver, dir.resolve("learned.json"), false, real, new AtomicInteger());
        StubElement parent = new StubElement("form");

        HealingBy email = HealingBy.of(By.name("email"), "the email field");
        NoSuchElementException e = assertThrows(NoSuchElementException.class, () -> parent.findElement(email));
        assertTrue(e.getMessage().contains("outside the search context"));
    }

    @Test
    void strictModeHealsThenFails(@TempDir Path dir) {
        StubDriver driver = new StubDriver();
        StubElement real = new StubElement("signin").attr("id", "signin");
        healer(driver, dir.resolve("learned.json"), true, real, new AtomicInteger());

        HealingBy signIn = HealingBy.of(By.id("login-old"), "the 'Sign in' button");
        HealingException e = assertThrows(HealingException.class, () -> driver.findElement(signIn));
        assertEquals("@Element(id = \"signin\")", e.event().suggestedLocator());
    }

    @Test
    void alumniumByIsDescriptionOnly(@TempDir Path dir) {
        StubDriver driver = new StubDriver();
        StubElement real = new StubElement("banner");
        AtomicInteger aiCalls = new AtomicInteger();
        healer(driver, dir.resolve("learned.json"), false, real, aiCalls);

        AlumniumBy banner = AlumniumBy.describe("the cookie banner");
        assertSame(real, driver.findElement(banner));
        assertEquals(List.of(real), driver.findElements(banner));
        assertEquals(2, aiCalls.get(), "AlumniumBy has no session cache by design");
        assertEquals("AlumniumBy: \"the cookie banner\"", banner.toString());
    }

    @Test
    void failsLoudlyWithoutARegisteredHealer() {
        StubDriver driver = new StubDriver();
        HealingBy by = HealingBy.of(By.id("x"), "x");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> driver.findElement(by));
        assertTrue(e.getMessage().contains("Graft.with(driver)"));
    }
}
