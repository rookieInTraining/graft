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
import tech.ishabbi.graft.Within;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * learned locator → Alumnium. Heals are scoped: when the search context is an element or a shadow
 * root, the healed element must be inside it.
 *
 * <p>Frames ("switch &amp; restore"): Selenium's frame state is global to the driver, and Alumnium
 * switches into a healed element's frame chain without switching back. A resolution remembers the
 * element's frame hops ({@link Located}); a proxy ({@code @Element}, {@link HealingBy#proxied()})
 * enters them for each call and restores the test's frame afterwards ({@link FrameState}). A raw
 * {@link HealingBy#findElement} returns a plain element, so it leaves the driver in that frame.
 */
public final class SeleniumHealer extends AbstractHealer {

    private final WebDriver driver;
    private final Framework framework;
    private final AiFinder finder;
    private final Runnable closer;
    private final Map<String, Located> healed = new ConcurrentHashMap<>();

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
     *
     * <p>Frames: with {@code stay} (a raw {@link HealingBy#findElement}) the driver is left in the
     * element's frame. Without it (proxies) the driver is back in the test's frame on return, and
     * the caller enters {@link Located#frames()} itself for the actual call ({@link #enter}).
     */
    Located resolve(LocatorSpec spec, By primary, SearchContext context, boolean stay) {
        Located cached = healed.get(spec.key());
        if (cached != null) {
            if (stay) enter(cached);
            return cached;
        }
        return locate(spec, primary, context, spec.locatorTimeout(config()), null, stay, null);
    }

    /**
     * Raw {@link HealingBy#findElement}: a plain element, so the driver has to stay switched into
     * the element's frame for it to be usable.
     */
    WebElement find(LocatorSpec spec, By primary, SearchContext context) {
        Located located = resolve(spec, primary, context, true);
        if (!located.frames().isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "{0}: the element is inside {1}; the driver stays switched into "
                    + "that frame (use HealingBy.proxied() to switch and restore)",
                    spec.displayName(), Within.formatAll(located.frames()));
        }
        return located.element();
    }

    /** Switches the driver into the located element's frame (no-op when it has no frame hops). */
    void enter(Located located) {
        if (!located.frames().isEmpty()) ContextResolver.enter(driver, located.from(), located.frames());
    }

    /** The test's current frame, to restore after switching; a no-op state for native Appium. */
    FrameState frameState() {
        return framework == Framework.APPIUM ? FrameState.none() : FrameState.capture(driver);
    }

    /**
     * One resolution after the cache: the primary (through its {@code within}), then the heal.
     * {@code outer} is the test's frame state when the caller (a proxy) already captured it and
     * will restore it; {@code healCause} replaces the primary's failure as the heal's cause.
     */
    private Located locate(LocatorSpec spec, By primary, SearchContext context, Duration timeout,
                           Throwable healCause, boolean stay, FrameState outer) {
        boolean switches = switchesFrames(spec, context);
        FrameState caller = outer != null ? outer : switches ? frameState() : null;
        Throwable cause = healCause;
        if (primary != null) {
            try {
                WebElement el = waitFor(scopeOf(spec, context), primary, timeout);
                if (!stay && outer == null && caller != null) caller.restore();
                return new Located(el, Located.framePrefix(spec.within()), context);
            } catch (NoSuchElementException | InvalidSelectorException primaryFailure) {
                if (cause == null) cause = primaryFailure;
            } catch (RuntimeException e) {
                if (outer == null && caller != null) caller.restore();
                throw e;
            }
        }
        // The heal must not inherit the iframe the primary searched: back to the test's frame first.
        if (switches) caller.restore();
        return cacheHealed(spec, healInto(spec, primary, context, cause, caller, stay));
    }

    /** True when entering the spec's {@code within} from {@code context} switches the driver's frame. */
    private static boolean switchesFrames(LocatorSpec spec, SearchContext context) {
        List<Within.Hop> within = spec.within();
        return !within.isEmpty() && (context instanceof WebDriver || !Located.framePrefix(within).isEmpty());
    }

    /**
     * Learned tier, then Alumnium (or Alumnium alone for a description-only spec). Both may switch
     * frames: Alumnium switches into the element's frame chain and never switches back. Without
     * {@code stay} the test's frame is restored afterwards, also on failure. With {@code stay} the
     * driver is left in the element's frame, where the heal left it (restored only on failure).
     */
    private Located healInto(LocatorSpec spec, By primary, SearchContext context, Throwable cause,
                             FrameState caller, boolean stay) {
        FrameState before = caller != null ? caller : frameState();
        boolean[] lost = {false};   // found in a frame whose hops could not be derived
        Located found;
        try {
            found = primary == null ? describeOnly(spec, context, lost)
                    : healWithAlumnium(spec, primary, context, cause, lost);
        } catch (RuntimeException e) {
            before.restore();
            throw e;
        }
        if (stay) return found;
        if (lost[0]) {
            // Rare: the frame path has an iframe without a unique CSS (or the frame search hit its caps).
            // Without hops the frame cannot be re-entered, so the driver is left where Alumnium put it.
            log.log(System.Logger.Level.DEBUG, "{0}: Alumnium found the element in a frame Graft cannot re-enter; "
                    + "leaving the driver switched into it", spec.displayName());
            return found;
        }
        before.restore();
        return found;
    }

    /**
     * Where the primary is searched: {@code context} itself for an empty {@code within}, else the
     * context entered through the chain, re-entered on every poll so a late host or iframe is found.
     */
    private Supplier<SearchContext> scopeOf(LocatorSpec spec, SearchContext context) {
        List<Within.Hop> within = spec.within();
        if (within.isEmpty()) return () -> context;
        return () -> ContextResolver.enter(driver, context, within).context();
    }

    /**
     * A healed (cached) element went stale during a proxy call: quick retry of the primary, then
     * Alumnium again. {@code outer} is the test's frame state, captured (and later restored) by the
     * proxy; the driver is left in the fresh element's frame for the retried call.
     */
    Located reResolveAfterStale(LocatorSpec spec, By primary, SearchContext context, Throwable stale,
                                FrameState outer) {
        healed.remove(spec.key());
        return locate(spec, primary, context, config().pollInterval(), stale, true, outer);
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

    /** A description-only spec: Alumnium is the locator, with the frame hops of what it found. */
    private Located describeOnly(LocatorSpec spec, SearchContext context, boolean[] lost) {
        WebElement el = findByDescription(spec, context);
        return locatedByAlumnium(el, frameHops(el), lost);
    }

    private Located healWithAlumnium(LocatorSpec spec, By primary, SearchContext context, Throwable cause,
                                     boolean[] lost) {
        List<List<String>> hops = new ArrayList<>(1);   // the frame hops of what Alumnium found
        try {
            return heal(spec, primary.toString(), cause,
                    suggestion -> tryLearned(spec, suggestion, context),
                    () -> {
                        WebElement el = findWithAlumnium(spec);
                        requireInScope(spec, el, context, cause);
                        hops.add(frameHops(el));
                        return locatedByAlumnium(el, hops.get(0), lost);
                    },
                    found -> SuggestedLocator.describe(found.element()),
                    found -> SuggestedLocator.suggest(driver, found.element(), framework, hops.get(0)));
        } catch (HealingException e) {
            for (Throwable t : e.getSuppressed()) {
                if (t instanceof OutOfScopeException scope) throw scope;   // keep Selenium's exception type
            }
            throw e;
        }
    }

    /**
     * The {@code within} hops of the frames an element Alumnium found sits in ({@link FramePath#hopsFor});
     * the driver is in that element's frame. Empty for native Appium; {@code null} when unusable.
     */
    private List<String> frameHops(WebElement el) {
        return framework == Framework.APPIUM ? List.of() : FramePath.hopsFor(driver, el);
    }

    /** Alumnium's element, re-entered from the top through its frame hops ({@code null} hops: none, and lost). */
    private Located locatedByAlumnium(WebElement el, List<String> hops, boolean[] lost) {
        if (hops == null) {
            lost[0] = true;
            return new Located(el, List.of(), driver);
        }
        return new Located(el, Within.parseAll(hops), driver);
    }

    private Located cacheHealed(LocatorSpec spec, Located located) {
        healed.put(spec.key(), located);
        return located;
    }

    private WebElement findWithAlumnium(LocatorSpec spec) {
        Object found = finder.find(spec.description());
        if (found instanceof WebElement we) return we;
        throw new IllegalStateException("AI finder returned " + (found == null ? "null" : found.getClass().getName())
                + " for a " + framework + " driver; expected WebElement");
    }

    /**
     * One quick attempt with a locator remembered from an earlier heal; {@code null} on miss. A
     * learned {@code within} is absolute (computed from the top-level document), so it is entered
     * from the driver even under a scoped search context; failing to enter is a miss, and so is a
     * match outside the scope. Inside a shadow root the locator must map to CSS.
     */
    private Located tryLearned(LocatorSpec spec, LocatorSuggestion suggestion, SearchContext context) {
        List<Within.Hop> within = Within.parseAll(suggestion.within());
        ContextResolver.Scope scope;
        try {
            scope = ContextResolver.enter(driver, driver, within);
        } catch (RuntimeException miss) {
            log.log(System.Logger.Level.DEBUG, "{0}: cannot enter the learned locator''s context {1}: {2}",
                    spec.displayName(), suggestion.within(), miss.getMessage());
            return null;
        }
        By by = LocatorBuilder.fromSuggestion(suggestion, driver, framework, scope.inShadow());
        if (by == null) {
            if (scope.inShadow()) {
                log.log(System.Logger.Level.DEBUG, "{0}: learned locator {1} is unsupported in a shadow root "
                        + "(Selenium supports only CSS there); treating it as a miss", spec.displayName(), suggestion);
            }
            return null;
        }
        WebElement el;
        try {
            el = waitFor(scope::context, by, config().pollInterval());
        } catch (NoSuchElementException | InvalidSelectorException miss) {
            return null;
        }
        try {
            requireInScope(spec, el, context, null);
        } catch (OutOfScopeException outside) {
            log.log(System.Logger.Level.DEBUG, "{0}: learned locator {1} matched outside the search context; "
                    + "treating it as a miss", spec.displayName(), suggestion);
            return null;
        }
        return new Located(el, Located.framePrefix(within), driver);
    }

    /**
     * A heal must respect the search context: if the lookup was {@code parent.findElement(by)} (an
     * element or a shadow root), the element Alumnium found has to be inside {@code parent}. Web: DOM
     * containment via JS, composed through shadow hosts; native mobile: the child's centre must lie
     * within the parent's rect. If the check itself cannot run, the heal is allowed and the
     * uncertainty is logged.
     */
    private void requireInScope(LocatorSpec spec, WebElement el, SearchContext context, Throwable cause) {
        if (context instanceof WebDriver || context == null || context == el) return;
        Boolean inside = contains(context, el);
        if (inside == null) {
            log.log(System.Logger.Level.DEBUG, "Could not verify that the healed {0} lies within {1}; allowing",
                    spec.displayName(), context);
            return;
        }
        if (!inside) {
            OutOfScopeException nse = new OutOfScopeException("Alumnium found \"" + spec.description()
                    + "\" but it is outside the search context " + context + "; refusing to heal out of scope");
            if (cause != null) nse.initCause(cause);
            throw nse;
        }
    }

    /** A heal that landed outside {@code parent.findElement(...)}'s scope. */
    public static final class OutOfScopeException extends NoSuchElementException {
        OutOfScopeException(String message) { super(message); }
    }

    /**
     * {@code arguments[0]} (an element or a shadow root) contains {@code arguments[1]}, also when the
     * child sits in shadow roots nested below it: climb from the child through shadow hosts.
     */
    private static final String CONTAINS_JS = "const parent = arguments[0]; let n = arguments[1];"
            + " while (n) { if (parent.contains(n)) return true;"
            + " const r = n.getRootNode(); n = r && r.host ? r.host : null; }"
            + " return false;";

    private Boolean contains(SearchContext context, WebElement child) {
        try {
            if (framework == Framework.APPIUM && context instanceof WebElement parent) {
                Rectangle p = parent.getRect();
                Rectangle c = child.getRect();
                int cx = c.getX() + c.getWidth() / 2;
                int cy = c.getY() + c.getHeight() / 2;
                return cx >= p.getX() && cx <= p.getX() + p.getWidth()
                        && cy >= p.getY() && cy <= p.getY() + p.getHeight();
            }
            WebDriver d = DriverRegistry.unwrapDriver(driver);
            if (d instanceof JavascriptExecutor js) {
                Object parent = context instanceof WebElement el ? unwrap(el) : context;   // ShadowRoot as-is
                Object r = js.executeScript(CONTAINS_JS, parent, unwrap(child));
                return r instanceof Boolean b ? b : null;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return null;
    }

    static WebElement unwrap(WebElement el) {
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
    private WebElement waitFor(Supplier<SearchContext> scope, By by, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        NoSuchElementException last;
        while (true) {
            try {
                return scope.get().findElement(by);
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
