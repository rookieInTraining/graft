package tech.rookieintraining.graft;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A framework-neutral healable locator you can keep as a static constant — the counterpart of
 * {@code HealingBy} for frameworks without a {@code By}:
 *
 * <pre>{@code
 * // Playwright: selector string, or any Locator-producing function
 * static final HealingSelector SIGN_IN = Graft.selector("#login-submit", "the 'Sign in' button");
 * static final HealingSelector EMAIL   = Graft.selector(p -> p.getByLabel("Email"), "the email field");
 * Locator signIn = Graft.locator(page, SIGN_IN);
 *
 * // Maestro
 * static final HealingSelector SIGN_IN = Graft.selector(MaestroSelector.id("login"), "the 'Sign in' button");
 * healer.element(SIGN_IN).tap();
 * }</pre>
 *
 * <p>{@link #primary()} is {@code null} for description-only selectors, a {@code String} selector
 * or a {@code Function<Page, Locator>} for Playwright, and a {@code MaestroSelector} for Maestro.
 * Each healer validates the type it receives.
 */
public final class HealingSelector implements LocatorSpec {

    private final Object primary;
    private final String description;
    private final boolean heal;
    private final long timeoutMs;
    private final String origin;
    private final List<Within.Hop> within;

    private HealingSelector(Object primary, String description, boolean heal, long timeoutMs, String origin,
                            List<Within.Hop> within) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("HealingSelector needs a non-blank description");
        }
        this.primary = primary;
        this.description = description;
        this.heal = heal;
        this.timeoutMs = timeoutMs;
        this.origin = origin;
        this.within = within;
    }

    public static HealingSelector of(Object primary, String description) {
        return new HealingSelector(primary, description, true, -1, CallSite.capture(), List.of());
    }

    public static HealingSelector describe(String description) {
        return new HealingSelector(null, description, true, -1, CallSite.capture(), List.of());
    }

    public HealingSelector heal(boolean heal) { return new HealingSelector(primary, description, heal, timeoutMs, origin, within); }

    public HealingSelector timeout(Duration d) { return new HealingSelector(primary, description, heal, d.toMillis(), origin, within); }

    /**
     * Resolve inside iframes / shadow roots, outside-in: {@code "frame=<css>"}, {@code "shadow=<css>"}.
     * Needs a primary locator: a description-only selector is found by Alumnium wherever it is.
     */
    public HealingSelector within(String... hops) {
        List<Within.Hop> parsed = Within.parseAll(hops);
        if (!parsed.isEmpty() && primary == null) {
            throw new IllegalArgumentException(displayName() + ": within requires a primary locator; "
                    + "a description-only selector has nothing to resolve in the context");
        }
        return new HealingSelector(primary, description, heal, timeoutMs, origin, parsed);
    }

    @Override public List<Within.Hop> within() { return within; }

    public Object primary() { return primary; }

    public boolean hasPrimary() { return primary != null; }

    /** The primary cast to {@code type}, or an explanatory failure. */
    public <T> T primary(Class<T> type, String framework) {
        if (primary == null) return null;
        if (!type.isInstance(primary)) {
            throw new IllegalStateException(displayName() + ": " + framework + " expects a "
                    + type.getSimpleName() + " primary, got " + primary.getClass().getName());
        }
        return type.cast(primary);
    }

    @Override public String key() {
        return "selector:" + primaryText() + "|" + description
                + (within.isEmpty() ? "" : "|within=" + String.join(" > ", Within.formatAll(within)));
    }

    @Override public String description() { return description; }

    @Override public boolean healEnabled() { return heal; }

    @Override
    public Duration locatorTimeout(HealingConfig config) {
        return timeoutMs < 0 ? config.locatorTimeout() : Duration.ofMillis(timeoutMs);
    }

    @Override public String displayName() { return primary == null ? "\"" + description + "\"" : primaryText(); }

    @Override public String origin() { return origin; }

    private String primaryText() {
        if (primary == null) return "<none>";
        if (primary instanceof Function) return "fn@" + Integer.toHexString(System.identityHashCode(primary));
        return String.valueOf(primary);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HealingSelector other && Objects.equals(primary, other.primary)
                && description.equals(other.description);
    }

    @Override public int hashCode() { return Objects.hash(primary, description); }

    @Override public String toString() { return "HealingSelector(" + primaryText() + " | \"" + description + "\")"; }
}
