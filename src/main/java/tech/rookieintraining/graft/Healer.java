package tech.rookieintraining.graft;

/**
 * A framework adapter that knows how to (1) resolve an {@link ElementSpec} with the framework's
 * own locator machinery and (2) fall back to Alumnium when that fails.
 *
 * <p>Obtain one through {@link Graft#with(Object)} or the framework-specific factories
 * ({@code SeleniumHealer.of}, {@code PlaywrightHealer.of}, {@code MaestroHealer.of}).
 */
public interface Healer extends AutoCloseable {

    Framework framework();

    HealingConfig config();

    /**
     * Creates the object assigned to a page-object field: a lazy, self-healing proxy
     * (Selenium/Appium {@code WebElement}, Playwright {@code Locator}) or a {@code MaestroElement}.
     *
     * @throws IllegalArgumentException if the field type is not supported by this framework
     */
    Object createElement(ElementSpec spec, Class<?> fieldType);

    /** Forgets any healed resolution cached for the element (e.g. after navigation). */
    void invalidate(LocatorSpec spec);

    /** Forgets all cached resolutions. */
    void invalidateAll();

    /** Releases Alumnium resources this healer created (never the driver you passed in). */
    @Override
    void close();
}
