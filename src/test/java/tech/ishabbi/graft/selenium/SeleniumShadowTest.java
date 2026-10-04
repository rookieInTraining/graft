package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.AiFinder;
import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingPageFactory;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.cache.MemoryLocatorStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenium against headless Chrome: heals into nested open shadow roots, replays the learned
 * {@code shadow=} chain without the finder, resolves inline {@code within} locators, checks heal
 * scope through shadow roots, and the whitespace-first TEXT XPath.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_BROWSER", matches = "true")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SeleniumShadowTest {

    private static final String PAGE = "<!doctype html><html><body>"
            + "<div id='outer-host'></div>"
            + "<button>Top</button>"
            + "<button>\n  <i></i> Save</button>"
            + "<section id='panel'><div><span>hello</span><span>bye</span></div></section>"
            + "<script>"
            + "const outer = document.getElementById('outer-host').attachShadow({mode: 'open'});"
            + "outer.innerHTML = '<x-inner id=\"inner-host\"></x-inner><button class=\"x\">Direct</button>"
            + "<input name=\"q\" value=\"query\">';"
            + "outer.getElementById('inner-host').attachShadow({mode: 'open'}).innerHTML ="
            + " '<div><button>Deep</button></div>';"
            + "</script></body></html>";

    private static final String DEEP_JS = "return document.getElementById('outer-host').shadowRoot"
            + ".getElementById('inner-host').shadowRoot.querySelector('button');";
    private static final String DIRECT_JS = "return document.getElementById('outer-host').shadowRoot"
            + ".querySelector('button.x');";

    private static WebDriver driver;
    private MemoryLocatorStore store;
    private final List<SeleniumHealer> open = new ArrayList<>();

    @BeforeAll
    static void launch() {
        String failure;
        try {
            driver = new ChromeDriver(options());
            failure = null;
        } catch (RuntimeException e) {
            failure = e.toString();
            Path chromium = playwrightChromium();
            if (chromium != null) {
                try {
                    driver = new ChromeDriver(options().setBinary(chromium.toFile()));
                } catch (RuntimeException again) {
                    failure += " / with " + chromium + ": " + again;
                }
            }
        }
        Assumptions.assumeTrue(driver != null, "No Chrome/chromedriver available: " + failure);
    }

    private static ChromeOptions options() {
        return new ChromeOptions().addArguments("--headless=new");
    }

    private static Path playwrightChromium() {
        Path root = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "ms-playwright");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("chromium-"))
                    .sorted((a, b) -> b.compareTo(a))
                    .flatMap(d -> Stream.of(d.resolve("chrome-win64/chrome.exe"), d.resolve("chrome-win/chrome.exe")))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @AfterAll
    static void quit() {
        if (driver != null) driver.quit();
    }

    @BeforeEach
    void load() {
        store = new MemoryLocatorStore();
        driver.get("data:text/html;charset=utf-8,"
                + URLEncoder.encode(PAGE, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    @AfterEach
    void closeHealers() {
        open.forEach(SeleniumHealer::close);
        open.clear();
    }

    private SeleniumHealer healer(AiFinder finder) {
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedStore(store)
                .locatorTimeout(Duration.ZERO).pollInterval(Duration.ofMillis(1)).build();
        SeleniumHealer h = SeleniumHealer.withFinder(driver, finder, config);
        open.add(h);
        return h;
    }

    private static AiFinder js(String script, AtomicInteger calls) {
        return description -> {
            calls.incrementAndGet();
            return ((JavascriptExecutor) driver).executeScript(script);
        };
    }

    private static AiFinder throwing(AtomicInteger calls) {
        return description -> {
            calls.incrementAndGet();
            throw new IllegalStateException("finder must not be called");
        };
    }

    private LocatorSuggestion learned(HealingBy by) {
        var entry = store.snapshot().get(by.key());
        return entry == null ? null : entry.suggestion();
    }

    @Test
    void healsIntoNestedShadowRootsAndReplaysWithoutTheFinder() {
        HealingBy deep = HealingBy.of(By.cssSelector("#gone"), "the deep button");
        AtomicInteger first = new AtomicInteger();
        SeleniumHealer healer = healer(js(DEEP_JS, first));

        assertEquals("Deep", driver.findElement(deep).getText());
        assertEquals(1, first.get());
        assertEquals(new LocatorSuggestion("css", "div > button", List.of("shadow=#outer-host", "shadow=#inner-host")),
                learned(deep));

        healer.close();
        open.remove(healer);
        AtomicInteger second = new AtomicInteger();
        healer(throwing(second));
        WebElement replayed = driver.findElement(deep);
        assertEquals("Deep", replayed.getText());
        replayed.click();
        assertEquals(0, second.get());
    }

    static class ShadowPage {
        @Element(value = "the deep button", within = {"shadow=#outer-host", "shadow=#inner-host"}, css = "div > button")
        WebElement deep;

        @Element(value = "the query field", within = {"shadow=#outer-host"}, name = "q")
        WebElement query;
    }

    @Test
    void inlineWithinResolvesWithoutAHeal() {
        AtomicInteger calls = new AtomicInteger();
        SeleniumHealer healer = healer(throwing(calls));

        WebElement direct = driver.findElement(
                HealingBy.of(By.cssSelector("button.x"), "the direct button").within("shadow=#outer-host"));
        assertEquals("Direct", direct.getText());

        ShadowPage page = new ShadowPage();
        HealingPageFactory.initElements(page, healer);
        assertEquals("Deep", page.deep.getText());
        assertEquals("query", page.query.getDomProperty("value"));
        assertEquals(0, calls.get());
    }

    @Test
    void healsAreScopedToAShadowRootSearchContext() {
        AtomicInteger calls = new AtomicInteger();
        healer(js(DIRECT_JS, calls));
        SearchContext root = driver.findElement(By.id("outer-host")).getShadowRoot();

        WebElement inside = root.findElement(HealingBy.of(By.cssSelector("#gone"), "the direct button"));
        assertEquals("Direct", inside.getText());
        assertEquals(1, calls.get());

        open.forEach(SeleniumHealer::close);
        open.clear();
        healer(js("return document.querySelector('body > button');", calls));
        assertThrows(SeleniumHealer.OutOfScopeException.class,
                () -> root.findElement(HealingBy.of(By.cssSelector("#gone"), "the top button")));
    }

    @Test
    void elementScopeComposesThroughNestedShadowRoots() {
        AtomicInteger calls = new AtomicInteger();
        healer(js(DEEP_JS, calls));
        WebElement host = driver.findElement(By.id("outer-host"));

        WebElement deep = host.findElement(HealingBy.of(By.cssSelector("#gone"), "the deep button, scoped"));
        assertEquals("Deep", deep.getText());

        WebElement panel = driver.findElement(By.id("panel"));
        assertThrows(SeleniumHealer.OutOfScopeException.class,
                () -> panel.findElement(HealingBy.of(By.cssSelector("#gone"), "the deep button, wrong scope")));
    }

    static class SavePage {
        @Element(value = "the save button", text = "Save")
        WebElement save;
    }

    @Test
    void textLocatorMatchesWhenTheFirstTextNodeIsWhitespace() {
        assertTrue(driver.findElements(By.xpath("//*[normalize-space(text())='Save']")).isEmpty(),
                "the old XPath reads only the whitespace-only first text node");
        AtomicInteger calls = new AtomicInteger();
        SavePage page = new SavePage();
        HealingPageFactory.initElements(page, healer(throwing(calls)));

        assertEquals("Save", page.save.getText());
        assertEquals(0, calls.get());
    }

    @Test
    void topLevelSuggestionIsAnchoredCss() {
        HealingBy hello = HealingBy.of(By.cssSelector("#gone"), "the hello label");
        AtomicInteger calls = new AtomicInteger();
        healer(js("return document.querySelector('#panel span');", calls));

        assertEquals("hello", driver.findElement(hello).getText());
        assertEquals(new LocatorSuggestion("css", "#panel > div > span:nth-of-type(1)", List.of()), learned(hello));
    }
}
