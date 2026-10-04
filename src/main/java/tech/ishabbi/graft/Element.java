package tech.ishabbi.graft;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a page-object field as a self-healing element.
 *
 * <p>The {@link #value() description} is the only required attribute. It is what Alumnium
 * receives when the primary locator fails, so write it the way you would describe the
 * element to a colleague: {@code "blue 'Sign in' button below the password field"}.
 *
 * <p>Three ways to use it:
 *
 * <pre>{@code
 * // 1. Keep your existing locator annotations, just add a description.
 * @Element("Sign in button")
 * @FindBy(id = "login-submit")
 * WebElement signIn;
 *
 * // 2. Put the locator on @Element itself (one locator attribute only).
 * @Element(value = "Email field on the login form", css = "input[name=email]")
 * WebElement email;
 *
 * // 3. No locator at all: the element is always resolved by Alumnium.
 * @Element("Cookie consent 'Accept all' button")
 * WebElement acceptCookies;
 * }</pre>
 *
 * <p>Locator attributes are mapped per framework (see {@code README.md}): web frameworks use
 * {@code id/css/xpath/name/text/testId/selector}; Appium adds {@code accessibilityId},
 * {@code androidUIAutomator}, {@code iosClassChain}, {@code iosPredicate}; Maestro uses
 * {@code id} and {@code text}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Element {

    /** Natural-language description of the element, used for healing. Required. */
    String value();

    // ---- locators (set at most one) -------------------------------------------------

    /** DOM id (web), resource-id (Android), accessibility identifier or Maestro id (mobile). */
    String id() default "";

    /** CSS selector (Selenium, Playwright). */
    String css() default "";

    /** XPath (Selenium, Appium, Playwright). */
    String xpath() default "";

    /** {@code name} attribute (web). */
    String name() default "";

    /** Exact visible text. Web: text match; Appium: {@code @text/@label/@name}; Maestro: {@code text:}. */
    String text() default "";

    /** Test id: {@code data-testid} on the web, resource-id / accessibility id on mobile. */
    String testId() default "";

    /** Raw framework selector: CSS for Selenium, any Playwright selector engine for Playwright. */
    String selector() default "";

    /** Appium accessibility id (content-desc on Android, accessibility identifier on iOS). */
    String accessibilityId() default "";

    /** Appium Android UiAutomator expression. */
    String androidUIAutomator() default "";

    /** Appium iOS class chain. */
    String iosClassChain() default "";

    /** Appium iOS NSPredicate. */
    String iosPredicate() default "";

    // ---- context -----------------------------------------------------------------------

    /**
     * Context chain the locator lives in, resolved outside-in before the element's own locator:
     * {@code "frame=<css>"} enters an iframe, {@code "shadow=<css>"} enters an open shadow root.
     * Each hop's CSS is resolved in the scope left by the previous one. Requires a locator
     * attribute. Empty means the current search context, no switching.
     */
    String[] within() default {};

    // ---- behaviour --------------------------------------------------------------------

    /** Set to {@code false} to make this element fail fast without consulting Alumnium. */
    boolean heal() default true;

    /**
     * How long to keep trying the primary locator before healing, in milliseconds.
     * {@code -1} uses {@link HealingConfig#locatorTimeout()}.
     */
    long timeoutMs() default -1;
}
