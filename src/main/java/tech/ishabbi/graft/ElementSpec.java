package tech.ishabbi.graft;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, framework-neutral view of one {@link Element}-annotated field.
 *
 * <p>Validates the annotation once (exactly one locator attribute, non-blank description) so
 * the framework adapters never have to re-check it.
 */
public final class ElementSpec implements LocatorSpec {

    /** Which {@link Element} locator attribute was set, if any. */
    public enum LocatorKind {
        ID, CSS, XPATH, NAME, TEXT, TEST_ID, SELECTOR,
        ACCESSIBILITY_ID, ANDROID_UIAUTOMATOR, IOS_CLASS_CHAIN, IOS_PREDICATE
    }

    private final Class<?> pageClass;
    private final Field field;
    private final Element annotation;
    private final LocatorKind locatorKind;   // null when @Element carries no locator
    private final String locatorValue;

    private ElementSpec(Class<?> pageClass, Field field, Element annotation,
                        LocatorKind locatorKind, String locatorValue) {
        this.pageClass = pageClass;
        this.field = field;
        this.annotation = annotation;
        this.locatorKind = locatorKind;
        this.locatorValue = locatorValue;
    }

    public static ElementSpec of(Class<?> pageClass, Field field) {
        Element annotation = Objects.requireNonNull(field.getAnnotation(Element.class),
                () -> field + " is not annotated with @Element");
        if (annotation.value().isBlank()) {
            throw new IllegalStateException("@Element on " + describe(pageClass, field)
                    + " needs a non-blank description; it is what Alumnium heals from.");
        }

        Map<LocatorKind, String> set = new LinkedHashMap<>();
        put(set, LocatorKind.ID, annotation.id());
        put(set, LocatorKind.CSS, annotation.css());
        put(set, LocatorKind.XPATH, annotation.xpath());
        put(set, LocatorKind.NAME, annotation.name());
        put(set, LocatorKind.TEXT, annotation.text());
        put(set, LocatorKind.TEST_ID, annotation.testId());
        put(set, LocatorKind.SELECTOR, annotation.selector());
        put(set, LocatorKind.ACCESSIBILITY_ID, annotation.accessibilityId());
        put(set, LocatorKind.ANDROID_UIAUTOMATOR, annotation.androidUIAutomator());
        put(set, LocatorKind.IOS_CLASS_CHAIN, annotation.iosClassChain());
        put(set, LocatorKind.IOS_PREDICATE, annotation.iosPredicate());

        if (set.size() > 1) {
            throw new IllegalStateException("@Element on " + describe(pageClass, field)
                    + " sets more than one locator attribute: " + set.keySet()
                    + ". Use exactly one, or none and rely on @FindBy / Alumnium.");
        }
        Map.Entry<LocatorKind, String> only = set.isEmpty() ? null : set.entrySet().iterator().next();
        return new ElementSpec(pageClass, field, annotation,
                only == null ? null : only.getKey(),
                only == null ? null : only.getValue());
    }

    private static void put(Map<LocatorKind, String> map, LocatorKind kind, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(kind, value);
        }
    }

    private static String describe(Class<?> pageClass, Field field) {
        return pageClass.getSimpleName() + "." + field.getName();
    }

    // ---- accessors ---------------------------------------------------------------------

    public Class<?> pageClass() { return pageClass; }

    public Field field() { return field; }

    public Element annotation() { return annotation; }

    @Override public String description() { return annotation.value(); }

    @Override public boolean healEnabled() { return annotation.heal(); }

    /** Locator attribute set on {@code @Element}, empty if none. */
    public Optional<LocatorKind> locatorKind() { return Optional.ofNullable(locatorKind); }

    public String locatorValue() { return locatorValue; }

    public boolean hasInlineLocator() { return locatorKind != null; }

    /** Stable identity used for heal budgets, caching and reporting. */
    @Override public String key() { return pageClass.getName() + "#" + field.getName(); }

    @Override public String displayName() { return describe(pageClass, field); }

    @Override public String origin() { return key(); }

    @Override
    public Duration locatorTimeout(HealingConfig config) {
        long ms = annotation.timeoutMs();
        return ms < 0 ? config.locatorTimeout() : Duration.ofMillis(ms);
    }

    @Override
    public String toString() {
        return "ElementSpec{" + displayName() + ", \"" + description() + "\""
                + (locatorKind == null ? "" : ", " + locatorKind + "=" + locatorValue) + "}";
    }
}
