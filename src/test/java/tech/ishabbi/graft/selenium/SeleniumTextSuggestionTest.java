package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.AiFinder;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.Within;
import tech.ishabbi.graft.cache.MemoryLocatorStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Selenium's text suggestions replay as an XPath that matches more than the anchored script
 * counts ({@code @name}/{@code @label} attributes, any own text node). A text suggestion is kept
 * only when that XPath finds the target alone, in the target's own document; otherwise the next
 * tier is learned. Each case heals, then replays the learned locator and checks it is the target.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_BROWSER", matches = "true")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SeleniumTextSuggestionTest {

    private static WebDriver driver;
    private MemoryLocatorStore store;
    private final List<SeleniumHealer> open = new ArrayList<>();

    @BeforeAll
    static void launch() {
        driver = BrowserDrivers.headlessChrome();
    }

    @AfterAll
    static void quit() {
        if (driver != null) driver.quit();
    }

    @BeforeEach
    void fresh() {
        store = new MemoryLocatorStore();
        if (driver != null) driver.switchTo().defaultContent();
    }

    @AfterEach
    void closeHealers() {
        open.forEach(SeleniumHealer::close);
        open.clear();
    }

    @Test
    void anInputNamedLikeTheLabelTextIsNotLearnedAsText() {
        load("<input name=\"Email\"><label>Email</label>");
        LocatorSuggestion s = healAndReplay(d -> d.findElement(By.tagName("label")));
        assertNotEquals("text", s.kind(), String.valueOf(s));
        assertEquals(LocatorSuggestion.of("css", ":root > body > label"), s);
    }

    @Test
    void aLabelWhoseTextNodeEqualsTheButtonTextIsNotLearnedAsText() {
        load("<label>Email <span>*</span></label><button>Email</button>");
        LocatorSuggestion s = healAndReplay(d -> d.findElement(By.tagName("button")));
        assertNotEquals("text", s.kind(), String.valueOf(s));
        assertEquals(LocatorSuggestion.of("css", ":root > body > button"), s);
    }

    @Test
    void aTextThatReplaysToTheTargetAloneIsStillLearnedAsText() {
        load("<p>Intro</p><button>Save draft</button>");
        assertEquals(LocatorSuggestion.of("text", "Save draft"),
                healAndReplay(d -> d.findElement(By.tagName("button"))));
    }

    @Test
    void insideAnIframeTheCheckRunsInTheElementsOwnDocument() {
        // At the top a unique "Email" label; inside the iframe the replay XPath also hits the input.
        load("<label>Email</label><iframe id=\"f\" srcdoc=\"&lt;input name=&quot;Email&quot;&gt;"
                + "&lt;label&gt;Email&lt;/label&gt;\"></iframe>");
        LocatorSuggestion s = healAndReplay(d -> {
            d.switchTo().frame(d.findElement(By.id("f")));
            return d.findElement(By.tagName("label"));
        });
        assertEquals(LocatorSuggestion.of("css", ":root > body > label", List.of("frame=#f")), s);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void load(String body) {
        driver.switchTo().defaultContent();
        driver.get("data:text/html;charset=utf-8," + URLEncoder.encode(
                "<!doctype html><html><body>" + body + "</body></html>", StandardCharsets.UTF_8).replace("+", "%20"));
    }

    /**
     * Heals {@code #gone} with a fake Alumnium returning {@code target} (from the top), then
     * replays the learned suggestion, as the learned tier does, and checks it finds that element.
     */
    private LocatorSuggestion healAndReplay(Function<WebDriver, WebElement> target) {
        AtomicReference<WebElement> found = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AiFinder alumnium = description -> {
            calls.incrementAndGet();
            driver.switchTo().defaultContent();
            found.set(target.apply(driver));
            return found.get();
        };
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(store)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        SeleniumHealer healer = SeleniumHealer.withFinder(driver, alumnium, config);
        open.add(healer);
        HealingBy by = HealingBy.of(By.cssSelector("#gone"), "the target");
        driver.findElement(by);
        assertEquals(1, calls.get());

        LocatorSuggestion s = store.get(by.key()).orElseThrow().suggestion();
        ContextResolver.Scope scope = ContextResolver.enter(driver, driver, Within.parseAll(s.within()));
        By replay = LocatorBuilder.fromSuggestion(s, driver, healer.framework(), scope.inShadow());
        assertNotNull(replay, String.valueOf(s));
        assertEquals(found.get(), scope.context().findElement(replay), "the learned " + s + " replays to the target");
        driver.switchTo().defaultContent();
        return s;
    }
}
