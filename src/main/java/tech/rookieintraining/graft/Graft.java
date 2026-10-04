package tech.rookieintraining.graft;

import tech.rookieintraining.graft.maestro.MaestroDevice;
import tech.rookieintraining.graft.maestro.MaestroHealer;
import tech.rookieintraining.graft.playwright.PlaywrightHealer;
import tech.rookieintraining.graft.selenium.AlumniumBy;
import tech.rookieintraining.graft.selenium.HealingBy;
import tech.rookieintraining.graft.selenium.SeleniumHealer;

/**
 * Graft's entry point. Hand it whatever drives your app and get back a {@link Healer}; declare locators
 * either on page-object fields ({@link Element}) or as constants ({@link #by}, {@link #describe},
 * {@link #selector}):
 *
 * <pre>{@code
 * Healer healer = Graft.with(driver);          // Selenium WebDriver / Appium driver (local or Grid)
 * Healer healer = Graft.with(page);            // Playwright Page
 * Healer healer = Graft.with(maestroDevice);   // MaestroDevice (CLI wrapper)
 *
 * // page objects
 * LoginPage login = Graft.page(LoginPage.class, healer, driver);
 *
 * // constants — the healer is found through the driver's session, nothing to pass around
 * static final By SIGN_IN = Graft.by(By.id("login-submit"), "the 'Sign in' button");
 * static final By BANNER  = Graft.describe("the cookie consent banner");
 * static final HealingSelector EMAIL = Graft.selector("input[name=email]", "the email field");   // Playwright
 * static final HealingSelector TAB   = Graft.selector(MaestroSelector.id("tab_home"), "the Home tab"); // Maestro
 * }</pre>
 *
 * <p>Framework detection is by type, resolved reflectively so that only the framework you
 * actually use needs to be on the classpath.
 */
public final class Graft {

    private Graft() {}

    public static Healer with(Object driverOrPage) {
        return with(driverOrPage, HealingConfig.defaults());
    }

    public static Healer with(Object driverOrPage, HealingConfig config) {
        if (driverOrPage instanceof MaestroDevice device) {
            return MaestroHealer.of(device, config);
        }
        if (isInstance(driverOrPage, "org.openqa.selenium.WebDriver")) {
            return SeleniumHealer.of(driverOrPage, config);
        }
        if (isInstance(driverOrPage, "com.microsoft.playwright.Page")) {
            return PlaywrightHealer.of(driverOrPage, config);
        }
        throw new IllegalArgumentException("Don't know how to heal on top of "
                + (driverOrPage == null ? "null" : driverOrPage.getClass().getName())
                + "; expected a Selenium/Appium WebDriver, a Playwright Page or a MaestroDevice");
    }

    // ---- page objects ----------------------------------------------------------------------

    /** Creates and wires a page object (no-arg ctor or ctor taking the native driver/page). */
    public static <T> T page(Class<T> pageClass, Healer healer, Object nativeDriver) {
        return HealingPageFactory.create(pageClass, healer, nativeDriver);
    }

    public static <T> T page(Class<T> pageClass, Healer healer) {
        return HealingPageFactory.create(pageClass, healer, null);
    }

    /** Wires an already-constructed page object. */
    public static void initElements(Object page, Healer healer) {
        HealingPageFactory.initElements(page, healer);
    }

    // ---- locator constants -----------------------------------------------------------------

    /** Selenium/Appium: a {@code By} that heals from {@code description} when {@code primary} fails. */
    public static HealingBy by(org.openqa.selenium.By primary, String description) {
        return HealingBy.of(primary, description);
    }

    /** Selenium/Appium: a description-only {@code By}, resolved by Alumnium every time. */
    public static AlumniumBy describe(String description) {
        return AlumniumBy.describe(description);
    }

    /**
     * Playwright / Maestro: a framework-neutral healable locator. {@code primary} is a selector
     * string or {@code Function<Page, Locator>} for Playwright, a {@code MaestroSelector} for Maestro,
     * or {@code null} for description-only.
     */
    public static HealingSelector selector(Object primary, String description) {
        return HealingSelector.of(primary, description);
    }

    private static boolean isInstance(Object o, String className) {
        if (o == null) return false;
        try {
            return Class.forName(className, false, o.getClass().getClassLoader()).isInstance(o);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
