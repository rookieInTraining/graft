package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.LocatorSpec;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsElement;
import org.openqa.selenium.interactions.Locatable;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A {@link By} that heals. Wraps the locator you already have plus a description:
 *
 * <pre>{@code
 * static final By SIGN_IN = HealingBy.of(By.id("login-submit"), "the 'Sign in' button");
 * static final By EMAIL   = HealingBy.of(AppiumBy.accessibilityId("email"), "the email field").timeout(Duration.ofSeconds(2));
 *
 * driver.findElement(SIGN_IN).click();                 // heals on NoSuchElementException
 * wait.until(elementToBeClickable(SIGN_IN));           // polls, heals once, then returns the cached element
 * driver.findElement(By.id("form")).findElement(EMAIL); // scoped: healed element must be inside the form
 * }</pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #findElement}: primary locator polled for the timeout → learned locator → Alumnium.
 *       Healed elements are cached for the session, so waits don't re-pay the timeout per poll.</li>
 *   <li>{@link #findElements}: primary only, never heals — an empty list is a legitimate answer.</li>
 *   <li>Not {@code By.Remotable}, so RemoteWebDriver always routes through this class.</li>
 *   <li>Returns the raw {@link WebElement} unless {@link #proxied()} — then a self-healing proxy
 *       that also recovers from {@code StaleElementReferenceException}.</li>
 * </ul>
 */
public final class HealingBy extends By implements LocatorSpec {

    private final By primary;
    private final String description;
    private final boolean heal;
    private final long timeoutMs;
    private final boolean proxied;
    private final String origin;

    private HealingBy(By primary, String description, boolean heal, long timeoutMs, boolean proxied, String origin) {
        this.primary = Objects.requireNonNull(primary, "primary");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("HealingBy needs a non-blank description for " + primary);
        }
        this.description = description;
        this.heal = heal;
        this.timeoutMs = timeoutMs;
        this.proxied = proxied;
        this.origin = origin;
    }

    public static HealingBy of(By primary, String description) {
        return new HealingBy(primary, description, true, -1, false, tech.ishabbi.graft.CallSite.capture());
    }

    public HealingBy timeout(Duration d) { return new HealingBy(primary, description, heal, d.toMillis(), proxied, origin); }

    public HealingBy heal(boolean heal) { return new HealingBy(primary, description, heal, timeoutMs, proxied, origin); }

    /** Return a self-healing proxy instead of the raw element (stale-reference recovery, re-heal). */
    public HealingBy proxied() { return new HealingBy(primary, description, heal, timeoutMs, true, origin); }

    public By primary() { return primary; }

    // ---- By ------------------------------------------------------------------------------

    @Override
    public WebElement findElement(SearchContext context) {
        SeleniumHealer healer = DriverRegistry.require(context);
        if (!proxied) {
            return healer.resolve(this, primary, context);
        }
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[] {WebElement.class, WrapsElement.class, Locatable.class},
                new HealingElementHandler(healer, this, primary, context));
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        return primary.findElements(context);
    }

    // ---- LocatorSpec ---------------------------------------------------------------------

    @Override public String key() { return "by:" + primary + "|" + description; }

    @Override public String description() { return description; }

    @Override public boolean healEnabled() { return heal; }

    @Override
    public Duration locatorTimeout(HealingConfig config) {
        return timeoutMs < 0 ? config.locatorTimeout() : Duration.ofMillis(timeoutMs);
    }

    @Override public String displayName() { return primary.toString(); }

    @Override public String origin() { return origin; }

    @Override
    public String toString() {
        return "HealingBy(" + primary + " | \"" + description + "\")";
    }
    // equals/hashCode: By's own toString-based implementation covers primary + description.
}
