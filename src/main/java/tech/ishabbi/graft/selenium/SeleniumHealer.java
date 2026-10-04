package tech.ishabbi.graft.selenium;

import ai.alumnium.Alumni;
import tech.ishabbi.graft.AbstractHealer;
import tech.ishabbi.graft.AiFinder;
import tech.ishabbi.graft.ElementSpec;
import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingException;
import tech.ishabbi.graft.HealingSelector;
import tech.ishabbi.graft.LocatorSpec;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.internal.AlumniHolder;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.InvalidSelectorException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Platform;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsElement;
import org.openqa.selenium.interactions.Locatable;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Healer for Selenium and Appium — local drivers and Selenium Grid alike.
 *
 * <p>Grid-readiness, concretely:
 * <ul>
 *   <li>Only {@link WebDriver}/{@link SearchContext} APIs are used — never {@code ChromeDriver},
 *       never a CDP session of our own. {@code RemoteWebDriver}, Appium drivers and decorated
 *       drivers all work.</li>
 *   <li>Appium vs Selenium is detected from <b>capabilities</b> (platformName / automationName),
 *       not the driver class, so a plain {@code RemoteWebDriver} pointed at an Appium node through
 *       a grid relay gets Appium locator mapping and suggestions.</li>
 *   <li>Registered in {@link DriverRegistry} by session id, so static {@code HealingBy} constants
 *       find the right healer even with many sessions in one JVM.</li>
 *   <li>Everything Alumnium returns is an element of the same session; nothing crosses sessions.</li>
 * </ul>
 *
 * <p>Resolution for every access: cached healed element → primary locator polled for the timeout →
 * learned locator → Alumnium. Heals are scoped: when the search context is an element, the healed
 * element must be inside it.
 */
public final class SeleniumHealer extends AbstractHealer {

    private final WebDriver driver;
    private final Framework framework;
    private final AiFinder finder;
    private final Runnable closer;
    private final Map<String, WebElement> healed = new ConcurrentHashMap<>();

    private SeleniumHealer(WebDriver driver, AiFinder finder, Runnable closer, HealingConfig config) {
        super(config);
        this.driver = Objects.requireNonNull(driver, "driver");
        this.finder = Objects.requireNonNull(finder, "finder");
        this.closer = closer;
        this.framework = detectFramework(driver);
        DriverRegistry.register(this);
    }

    // ---- factories -----------------------------------------------------------------------

    public static SeleniumHealer of(WebDriver driver) {
        return of(driver, HealingConfig.defaults());
    }

    /** Creates an {@link Alumni} on first heal and quits it on {@link #close()}. */
    public static SeleniumHealer of(WebDriver driver, HealingConfig config) {
        AlumniHolder holder = AlumniHolder.lazy(() -> new Alumni(driver));
        return new SeleniumHealer(driver, d -> holder.get().find(d), holder::close, config);
    }

    /** Reuse an {@link Alumni} you already use for {@code act/check/get}; it is not closed by this healer. */
    public static SeleniumHealer of(WebDriver driver, Alumni alumni, HealingConfig config) {
        return new SeleniumHealer(driver, alumni::find, () -> {}, config);
    }

    /** Any {@link AiFinder} — for tests, or a resolver other than Alumnium. */
    public static SeleniumHealer withFinder(WebDriver driver, AiFinder finder, HealingConfig config) {
        return new SeleniumHealer(driver, finder, () -> {}, config);
    }

    /** Untyped entry used by {@code Graft.with(Object)}. */
    public static SeleniumHealer of(Object driver, HealingConfig config) {
        return of((WebDriver) driver, config);
    }

    // ---- Healer --------------------------------------------------------------------------

    @Override
    public Framework framework() { return framework; }

    public WebDriver driver() { return driver; }

    @Override
    public Object createElement(ElementSpec spec, Class<?> fieldType) {
        if (fieldType.isAssignableFrom(WebElement.class)) {
            By primary = LocatorBuilder.build(spec, driver, framework);   // null → description-only
            return Proxy.newProxyInstance(
                    WebElement.class.getClassLoader(),
                    new Class<?>[] {WebElement.class, WrapsElement.class, Locatable.class},
                    new HealingElementHandler(this, spec, primary, driver));
        }
        if (List.class.isAssignableFrom(fieldType)) {
            throw new IllegalArgumentException("@Element on " + spec.displayName()
                    + ": List<WebElement> fields are not healed yet (Alumnium's find() returns a single element). "
                    + "Use @FindBy for lists for now.");
        }
        throw new IllegalArgumentException("@Element on " + spec.displayName()
                + ": unsupported field type " + fieldType.getName() + " for " + framework + " (expected WebElement)");
    }

    /** A self-healing {@link By} bound to nothing but the registry — see {@link HealingBy}. */
    public HealingBy by(By primary, String description) {
        return HealingBy.of(primary, description);
    }

    @Override
    public void invalidate(LocatorSpec spec) { healed.remove(spec.key()); }

    @Override
    public void invalidateAll() { healed.clear(); }

    @Override
    public void close() {
        DriverRegistry.unregister(this);
        healed.clear();
        closer.run();
    }

    // ---- resolution ----------------------------------------------------------------------

    /**
     * Resolves one interaction's element. {@code primary} may be {@code null} (description-only);
     * {@code context} is the driver or the element the lookup was scoped to.
     */
    WebElement resolve(LocatorSpec spec, By primary, SearchContext context) {
        WebElement cached = healed.get(spec.key());
        if (cached != null) return cached;

        if (primary == null) {
            return cacheHealed(spec, findByDescription(spec, context));
        }
        try {
            return waitFor(context, primary, spec.locatorTimeout(config()));
        } catch (NoSuchElementException | InvalidSelectorException primaryFailure) {
            return cacheHealed(spec, healWithAlumnium(spec, primary, context, primaryFailure));
        }
    }

    /** A healed (cached) element went stale: quick retry of the primary, then Alumnium again. */
    WebElement reResolveAfterStale(LocatorSpec spec, By primary, SearchContext context, Throwable stale) {
        healed.remove(spec.key());
        if (primary == null) {
            return cacheHealed(spec, findByDescription(spec, context));
        }
        try {
            return waitFor(context, primary, config().pollInterval());
        } catch (NoSuchElementException | InvalidSelectorException ignored) {
            return cacheHealed(spec, healWithAlumnium(spec, primary, context, stale));
        }
    }

    /** Used by {@link AlumniumBy}: Alumnium is the primary locator; failures become {@link NoSuchElementException}. */
    WebElement findByDescription(HealingSelector spec, SearchContext context) {
        return findByDescription((LocatorSpec) spec, context);
    }

    private WebElement findByDescription(LocatorSpec spec, SearchContext context) {
        WebElement el;
        try {
            el = findWithAlumnium(spec);
        } catch (RuntimeException e) {
            NoSuchElementException nse = new NoSuchElementException(
                    "Alumnium could not find \"" + spec.description() + "\": " + e.getMessage());
            nse.initCause(e);
            throw nse;
        }
        requireInScope(spec, el, context, null);
        return el;
    }

    private WebElement healWithAlumnium(LocatorSpec spec, By primary, SearchContext context, Throwable cause) {
        try {
            return heal(spec, primary.toString(), cause,
                    suggestion -> tryLearned(suggestion, context),
                    () -> {
                        WebElement el = findWithAlumnium(spec);
                        requireInScope(spec, el, context, cause);
                        return el;
                    },
                    SuggestedLocator::describe,
                    found -> SuggestedLocator.suggest(found, framework));
        } catch (HealingException e) {
            for (Throwable t : e.getSuppressed()) {
                if (t instanceof OutOfScopeException scope) throw scope;   // keep Selenium's exception type
            }
            throw e;
        }
    }

    private WebElement cacheHealed(LocatorSpec spec, WebElement el) {
        healed.put(spec.key(), el);
        return el;
    }

    private WebElement findWithAlumnium(LocatorSpec spec) {
        Object found = finder.find(spec.description());
        if (found instanceof WebElement we) return we;
        throw new IllegalStateException("AI finder returned " + (found == null ? "null" : found.getClass().getName())
                + " for a " + framework + " driver; expected WebElement");
    }

    /** One quick attempt with a locator remembered from an earlier heal; {@code null} on miss. */
    private WebElement tryLearned(LocatorSuggestion suggestion, SearchContext context) {
        By by = LocatorBuilder.fromSuggestion(suggestion, driver, framework);
        if (by == null) return null;
        try {
            return waitFor(context, by, config().pollInterval());
        } catch (NoSuchElementException | InvalidSelectorException miss) {
            return null;
        }
    }

    /**
     * A heal must respect the search context: if the lookup was {@code parent.findElement(by)}, the
     * element Alumnium found has to be inside {@code parent}. Web: DOM containment via JS; native
     * mobile: the child's centre must lie within the parent's rect. If the check itself cannot run,
     * the heal is allowed and the uncertainty is logged.
     */
    private void requireInScope(LocatorSpec spec, WebElement el, SearchContext context, Throwable cause) {
        if (!(context instanceof WebElement parent) || parent == el) return;
        Boolean inside = contains(parent, el);
        if (inside == null) {
            log.log(System.Logger.Level.DEBUG, "Could not verify that the healed {0} lies within {1}; allowing",
                    spec.displayName(), parent);
            return;
        }
        if (!inside) {
            OutOfScopeException nse = new OutOfScopeException("Alumnium found \"" + spec.description()
                    + "\" but it is outside the search context " + parent + "; refusing to heal out of scope");
            if (cause != null) nse.initCause(cause);
            throw nse;
        }
    }

    /** A heal that landed outside {@code parent.findElement(...)}'s scope. */
    public static final class OutOfScopeException extends NoSuchElementException {
        OutOfScopeException(String message) { super(message); }
    }

    private Boolean contains(WebElement parent, WebElement child) {
        try {
            if (framework == Framework.APPIUM) {
                Rectangle p = parent.getRect();
                Rectangle c = child.getRect();
                int cx = c.getX() + c.getWidth() / 2;
                int cy = c.getY() + c.getHeight() / 2;
                return cx >= p.getX() && cx <= p.getX() + p.getWidth()
                        && cy >= p.getY() && cy <= p.getY() + p.getHeight();
            }
            WebDriver d = DriverRegistry.unwrapDriver(driver);
            if (d instanceof JavascriptExecutor js) {
                Object r = js.executeScript("return arguments[0].contains(arguments[1]);", unwrap(parent), unwrap(child));
                return r instanceof Boolean b ? b : null;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return null;
    }

    private static WebElement unwrap(WebElement el) {
        WebElement current = el;
        for (int i = 0; i < 8 && current instanceof WrapsElement we; i++) {
            WebElement inner = we.getWrappedElement();
            if (inner == null || inner == current) break;
            current = inner;
        }
        return current;
    }

    /**
     * Polls the locator in the given context until found or the timeout elapses. Deliberately not
     * {@code WebDriverWait}: the raw {@link NoSuchElementException} (with its selector text) is the
     * heal cause. An implicit wait on the driver adds to each poll.
     */
    private WebElement waitFor(SearchContext context, By by, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        NoSuchElementException last;
        while (true) {
            try {
                return context.findElement(by);
            } catch (NoSuchElementException e) {
                last = e;
            }
            if (!Instant.now().isBefore(deadline)) throw last;
            try {
                Thread.sleep(config().pollInterval().toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw last;
            }
        }
    }

    // ---- detection -----------------------------------------------------------------------

    /** Appium if the driver class says so, or if the session's capabilities do (grid relays). */
    static Framework detectFramework(WebDriver driver) {
        WebDriver d = DriverRegistry.unwrapDriver(driver);
        if (d == null) d = driver;
        if (d.getClass().getName().startsWith("io.appium.")) return Framework.APPIUM;
        if (d instanceof HasCapabilities hc) {
            try {
                Capabilities caps = hc.getCapabilities();
                Platform p = caps.getPlatformName();
                if (p == Platform.ANDROID || p == Platform.IOS) return Framework.APPIUM;
                if (caps.getCapability("appium:automationName") != null
                        || caps.getCapability("automationName") != null) return Framework.APPIUM;
            } catch (RuntimeException ignored) {
                // capabilities unavailable before/after session; default to Selenium
            }
        }
        return Framework.SELENIUM;
    }
}
