package tech.ishabbi.graft;

import java.time.Duration;

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
}
