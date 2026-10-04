package tech.ishabbi.graft.playwright;

import ai.alumnium.Alumni;
import tech.ishabbi.graft.AbstractHealer;
import tech.ishabbi.graft.AiFinder;
import tech.ishabbi.graft.ElementSpec;
import tech.ishabbi.graft.ElementSpec.LocatorKind;
import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingSelector;
import tech.ishabbi.graft.LocatorSpec;
import tech.ishabbi.graft.LocatorSuggestion;
import tech.ishabbi.graft.internal.AlumniHolder;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Healer for Playwright. Alumnium's {@code find()} returns a native {@link Locator}, so a healed
 * field or constant keeps every Playwright affordance (auto-wait, assertions, chaining).
 *
 * <p>Playwright has no {@code By}; the constant-friendly form is {@link HealingSelector}:
 * <pre>{@code
 * static final HealingSelector SIGN_IN = Graft.selector("#login-submit", "the 'Sign in' button");
 * static final HealingSelector EMAIL   = Graft.selector(p -> p.getByLabel("Email"), "the email field");
 *
 * PlaywrightHealer.locator(page, SIGN_IN).click();      // registry lookup by Page
 * healer.locator(EMAIL).fill("ish@example.com");
 * }</pre>
 *
 * <p>Remote browsers (Playwright {@code connect()}, {@code connectOverCDP()}, or Chromium launched
 * through a Selenium Grid via {@code SELENIUM_REMOTE_URL}) need nothing special: the {@link Page}
 * is the same object either way, and healers are registered per Page.
 */
public final class PlaywrightHealer extends AbstractHealer {

    private static final Map<Page, PlaywrightHealer> REGISTRY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<PlaywrightHealer> CURRENT = new ThreadLocal<>();

    private final Page page;
    private final AiFinder finder;
    private final Runnable closer;
    private final Map<String, Locator> resolved = new ConcurrentHashMap<>();
    private final Set<String> healedKeys = ConcurrentHashMap.newKeySet();

    private PlaywrightHealer(Page page, AiFinder finder, Runnable closer, HealingConfig config) {
        super(config);
        this.page = Objects.requireNonNull(page, "page");
        this.finder = finder;
        this.closer = closer;
        REGISTRY.put(page, this);
        CURRENT.set(this);
    }

    // ---- factories & registry ------------------------------------------------------------

    public static PlaywrightHealer of(Page page) {
        return of(page, HealingConfig.defaults());
    }

    public static PlaywrightHealer of(Page page, HealingConfig config) {
        AlumniHolder holder = AlumniHolder.lazy(() -> new Alumni(page));
        return new PlaywrightHealer(page, d -> holder.get().find(d), holder::close, config);
    }

    public static PlaywrightHealer of(Page page, Alumni alumni, HealingConfig config) {
        return new PlaywrightHealer(page, alumni::find, () -> {}, config);
    }

    public static PlaywrightHealer withFinder(Page page, AiFinder finder, HealingConfig config) {
        return new PlaywrightHealer(page, finder, () -> {}, config);
    }

    /** Untyped entry used by {@code Graft.with(Object)}. */
    public static PlaywrightHealer of(Object page, HealingConfig config) {
        return of((Page) page, config);
    }

    public static Optional<PlaywrightHealer> lookup(Page page) {
        PlaywrightHealer h = REGISTRY.get(page);
        return h != null ? Optional.of(h) : Optional.ofNullable(CURRENT.get());
    }

    /** Typed sugar for a Locator-producing constant: {@code PlaywrightHealer.selector(p -> p.getByLabel("Email"), "...")}. */
    public static HealingSelector selector(Function<Page, Locator> primary, String description) {
        return HealingSelector.of(primary, description);
    }

    /** Resolve a {@link HealingSelector} constant against a page whose healer was created with {@code Graft.with(page)}. */
    public static Locator locator(Page page, HealingSelector selector) {
        return lookup(page).orElseThrow(() -> new IllegalStateException(
                "No PlaywrightHealer registered for this Page; call Graft.with(page) first")).locator(selector);
    }

    // ---- Healer --------------------------------------------------------------------------

    @Override
    public Framework framework() { return Framework.PLAYWRIGHT; }

    public Page page() { return page; }

    @Override
    public Object createElement(ElementSpec spec, Class<?> fieldType) {
        if (fieldType.isAssignableFrom(Locator.class)) {
            return proxy(spec, () -> primaryLocator(spec));
        }
        throw new IllegalArgumentException("@Element on " + spec.displayName()
                + ": unsupported field type " + fieldType.getName() + " for Playwright (expected Locator)");
    }

    /** A self-healing {@link Locator} for a selector constant. */
    public Locator locator(HealingSelector selector) {
        Object primary = selector.primary();
        Supplier<Locator> supplier;
        if (primary == null) {
            supplier = () -> null;
        } else if (primary instanceof String s) {
            supplier = () -> page.locator(s);
        } else if (primary instanceof Function<?, ?> fn) {
            @SuppressWarnings("unchecked")
            Function<Page, Locator> typed = (Function<Page, Locator>) fn;
            supplier = () -> typed.apply(page);
        } else {
            throw new IllegalStateException(selector.displayName() + ": Playwright expects a String selector or a "
                    + "Function<Page, Locator> primary, got " + primary.getClass().getName());
        }
        return proxy(selector, supplier);
    }

    /** Sugar for an inline healing locator. */
    public Locator locator(String selector, String description) {
        return locator(HealingSelector.of(selector, description));
    }

    @Override
    public void invalidate(LocatorSpec spec) {
        resolved.remove(spec.key());
        healedKeys.remove(spec.key());
    }

    @Override
    public void invalidateAll() {
        resolved.clear();
        healedKeys.clear();
    }

    @Override
    public void close() {
        REGISTRY.remove(page, this);
        if (CURRENT.get() == this) CURRENT.remove();
        invalidateAll();
        closer.run();
    }

    // ---- resolution ----------------------------------------------------------------------

    private Locator proxy(LocatorSpec spec, Supplier<Locator> primary) {
        return (Locator) Proxy.newProxyInstance(
                Locator.class.getClassLoader(),
                new Class<?>[] {Locator.class},
                new HealingLocatorHandler(this, spec, primary));
    }

    Locator resolve(LocatorSpec spec, Supplier<Locator> primarySupplier) {
        Locator cached = resolved.get(spec.key());
        if (cached != null) return cached;

        Locator primary = primarySupplier.get();
        if (primary == null) {
            Locator found = findWithAlumnium(spec);
            resolved.put(spec.key(), found);
            healedKeys.add(spec.key());
            return found;
        }

        try {
            primary.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(spec.locatorTimeout(config()).toMillis()));
            resolved.put(spec.key(), primary);
            return primary;
        } catch (TimeoutError notFound) {
            return healFrom(spec, primary, notFound);
        }
    }

    /** Heal path used both on first resolution and when a cached locator stops matching. */
    Locator healFrom(LocatorSpec spec, Locator primary, Throwable cause) {
        resolved.remove(spec.key());
        Locator found = heal(spec, String.valueOf(primary), cause,
                this::tryLearned,
                () -> findWithAlumnium(spec),
                PlaywrightSuggestedLocator::describe,
                PlaywrightSuggestedLocator::suggest);
        resolved.put(spec.key(), found);
        healedKeys.add(spec.key());
        return found;
    }

    boolean wasHealed(LocatorSpec spec) {
        return healedKeys.contains(spec.key());
    }

    Locator primaryLocator(ElementSpec spec) {
        if (!spec.hasInlineLocator()) return null;
        LocatorKind kind = spec.locatorKind().orElseThrow();
        String v = spec.locatorValue();
        switch (kind) {
            case ID:       return page.locator("[id=" + quote(v) + "]");
            case CSS:
            case SELECTOR: return page.locator(v);
            case XPATH:    return page.locator("xpath=" + v);
            case NAME:     return page.locator("[name=" + quote(v) + "]");
            case TEXT:     return page.getByText(v, new Page.GetByTextOptions().setExact(true));
            case TEST_ID:  return page.getByTestId(v);
            default:
                throw new IllegalStateException("@Element on " + spec.displayName() + " uses " + kind
                        + ", which is an Appium locator; Playwright supports id/css/xpath/name/text/testId/selector");
        }
    }

    private Locator tryLearned(LocatorSuggestion s) {
        Locator loc;
        switch (s.kind()) {
            case "id":     loc = page.locator("[id=" + quote(s.value()) + "]"); break;
            case "css":    loc = page.locator(s.value()); break;
            case "xpath":  loc = page.locator("xpath=" + s.value()); break;
            case "name":   loc = page.locator("[name=" + quote(s.value()) + "]"); break;
            case "text":   loc = page.getByText(s.value(), new Page.GetByTextOptions().setExact(true)); break;
            case "testId": loc = page.getByTestId(s.value()); break;
            default:       return null;
        }
        return loc.count() > 0 ? loc : null;
    }

    private Locator findWithAlumnium(LocatorSpec spec) {
        Object found = finder.find(spec.description());
        if (found instanceof Locator loc) return loc;
        throw new IllegalStateException("AI finder returned " + (found == null ? "null" : found.getClass().getName())
                + " for a Playwright page; expected Locator");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
