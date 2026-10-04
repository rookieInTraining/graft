package tech.rookieintraining.graft;

import java.time.Duration;
import java.util.List;

/**
 * What every healable locator looks like to the heal policy, regardless of how it was declared:
 * an {@code @Element} field ({@link ElementSpec}), a Selenium {@code HealingBy}, or a
 * framework-neutral {@link HealingSelector}.
 */
public interface LocatorSpec {

    /** Stable identity for budgets, caches, learned locators and the report. */
    String key();

    /** Natural-language description Alumnium heals from. */
    String description();

    boolean healEnabled();

    /** How long the primary locator is retried before Alumnium is consulted. */
    Duration locatorTimeout(HealingConfig config);

    /** Short label for logs, e.g. {@code LoginPage.signIn} or {@code By.id: login}. */
    String displayName();

    /** Where to edit: {@code com.acme.LoginPage#signIn} or {@code LoginPage.java:42}. */
    String origin();

    /** Context chain (iframes / shadow roots) the primary locator is resolved in, outside-in; empty for top level. */
    default List<Within.Hop> within() { return List.of(); }
}
