package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.ElementSpec;
import tech.ishabbi.graft.ElementSpec.LocatorKind;
import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.FindAll;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.FindBys;
import org.openqa.selenium.support.pagefactory.Annotations;

import java.lang.reflect.Field;

/**
 * Turns an {@link ElementSpec} into a Selenium {@link By}.
 *
 * <p>Precedence: a locator attribute on {@code @Element} wins; otherwise Appium's
 * {@code @AndroidFindBy/@iOSXCUITFindBy} (when driving Appium) or Selenium's
 * {@code @FindBy/@FindBys/@FindAll}; otherwise {@code null}, meaning "always ask Alumnium".
 */
final class LocatorBuilder {

    private LocatorBuilder() {}

    static By build(ElementSpec spec, WebDriver driver, Framework framework) {
        if (spec.hasInlineLocator()) {
            return fromInline(spec, driver, framework);
        }
        Field field = spec.field();
        if (framework == Framework.APPIUM && AppiumSupport.available()) {
            By by = AppiumSupport.buildBy(field, driver);
            if (by != null) return by;
        }
        if (field.isAnnotationPresent(FindBy.class) || field.isAnnotationPresent(FindBys.class)
                || field.isAnnotationPresent(FindAll.class)) {
            return new Annotations(field).buildBy();
        }
        return null;
    }

    /** Turns a learned {@link LocatorSuggestion} back into a {@link By}; {@code null} if it does not apply here. */
    static By fromSuggestion(LocatorSuggestion s, WebDriver driver, Framework framework) {
        if (s == null) return null;
        try {
            switch (s.kind()) {
                case "id":              return fromKind(LocatorKind.ID, s.value(), driver, framework);
                case "css":             return fromKind(LocatorKind.CSS, s.value(), driver, framework);
                case "xpath":           return fromKind(LocatorKind.XPATH, s.value(), driver, framework);
                case "name":            return fromKind(LocatorKind.NAME, s.value(), driver, framework);
                case "text":            return fromKind(LocatorKind.TEXT, s.value(), driver, framework);
                case "testId":          return fromKind(LocatorKind.TEST_ID, s.value(), driver, framework);
                case "accessibilityId": return AppiumSupport.available() ? AppiumSupport.accessibilityId(s.value()) : null;
                default:                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static By fromInline(ElementSpec spec, WebDriver driver, Framework framework) {
        return fromKind(spec.locatorKind().orElseThrow(), spec.locatorValue(), driver, framework, spec);
    }

    private static By fromKind(LocatorKind kind, String v, WebDriver driver, Framework framework) {
        return fromKind(kind, v, driver, framework, null);
    }

    private static By fromKind(LocatorKind kind, String v, WebDriver driver, Framework framework, ElementSpec spec) {
        boolean mobile = framework == Framework.APPIUM;
        switch (kind) {
            case ID:
                return mobile && AppiumSupport.available() ? AppiumSupport.id(v) : By.id(v);
            case CSS:
            case SELECTOR:
                return By.cssSelector(v);
            case XPATH:
                return By.xpath(v);
            case NAME:
                return By.name(v);
            case TEXT:
                // Works on web (text()), Android (@text) and iOS (@label/@name).
                return By.xpath("//*[@text=" + xpathLiteral(v) + " or @label=" + xpathLiteral(v)
                        + " or @name=" + xpathLiteral(v) + " or normalize-space(text())=" + xpathLiteral(v) + "]");
            case TEST_ID:
                if (mobile && AppiumSupport.available()) {
                    return AppiumSupport.isAndroid(driver) ? AppiumSupport.id(v) : AppiumSupport.accessibilityId(v);
                }
                return By.cssSelector("[data-testid=" + cssLiteral(v) + "]");
            case ACCESSIBILITY_ID:
                return AppiumSupport.require(spec, kind).accessibilityId(v);
            case ANDROID_UIAUTOMATOR:
                return AppiumSupport.require(spec, kind).androidUIAutomator(v);
            case IOS_CLASS_CHAIN:
                return AppiumSupport.require(spec, kind).iosClassChain(v);
            case IOS_PREDICATE:
                return AppiumSupport.require(spec, kind).iosNsPredicate(v);
            default:
                throw new IllegalStateException("Unhandled locator kind " + kind);
        }
    }

    static String xpathLiteral(String s) {
        if (!s.contains("'")) return "'" + s + "'";
        if (!s.contains("\"")) return "\"" + s + "\"";
        // concat('a', "'", 'b') for strings containing both quote kinds
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append('\'').append(parts[i]).append('\'');
        }
        return sb.append(')').toString();
    }

    static String cssLiteral(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Everything that touches {@code io.appium.*} lives here so that Selenium-only projects never
     * trigger loading of Appium classes.
     */
    static final class AppiumSupport {
        private static final boolean AVAILABLE;

        static {
            boolean ok;
            try {
                Class.forName("io.appium.java_client.AppiumBy", false, AppiumSupport.class.getClassLoader());
                ok = true;
            } catch (ClassNotFoundException e) {
                ok = false;
            }
            AVAILABLE = ok;
        }

        private AppiumSupport() {}

        static boolean available() { return AVAILABLE; }

        static AppiumLocators require(ElementSpec spec, LocatorKind kind) {
            if (!AVAILABLE) {
                throw new IllegalStateException((spec == null ? "A locator" : "@Element on " + spec.displayName())
                        + " uses the Appium locator kind " + kind + " but io.appium:java-client is not on the classpath");
            }
            return new AppiumLocators();
        }

        static By id(String v) { return new AppiumLocators().id(v); }

        static By accessibilityId(String v) { return new AppiumLocators().accessibilityId(v); }

        static boolean isAndroid(WebDriver driver) {
            return platformOf(driver).toLowerCase().contains("android");
        }

        static String platformOf(WebDriver driver) {
            if (driver instanceof HasCapabilities hc) {
                Capabilities caps = hc.getCapabilities();
                Object p = caps.getPlatformName();
                if (p != null) return p.toString();
            }
            return "";
        }

        /**
         * Delegates to Appium's own annotation processor for {@code @AndroidFindBy} & co.
         * Returns {@code null} when the field has no locator annotations at all — Appium's
         * builder would otherwise fall back to a field-name locator, which is not what we want.
         */
        static By buildBy(Field field, WebDriver driver) {
            boolean annotated = field.isAnnotationPresent(FindBy.class)
                    || field.isAnnotationPresent(FindBys.class)
                    || field.isAnnotationPresent(FindAll.class);
            for (java.lang.annotation.Annotation a : field.getAnnotations()) {
                if (a.annotationType().getName().startsWith("io.appium.java_client.pagefactory.")) {
                    annotated = true;
                }
            }
            return annotated ? new AppiumLocators().buildBy(field, driver) : null;
        }
    }

    /** Separate class so the {@code io.appium} symbols are only resolved when it is instantiated. */
    static final class AppiumLocators {
        By id(String v) { return io.appium.java_client.AppiumBy.id(v); }

        By accessibilityId(String v) { return io.appium.java_client.AppiumBy.accessibilityId(v); }

        By androidUIAutomator(String v) { return io.appium.java_client.AppiumBy.androidUIAutomator(v); }

        By iosClassChain(String v) { return io.appium.java_client.AppiumBy.iOSClassChain(v); }

        By iosNsPredicate(String v) { return io.appium.java_client.AppiumBy.iOSNsPredicateString(v); }

        By buildBy(Field field, WebDriver driver) {
            String platform = AppiumSupport.platformOf(driver);
            String automation = "";
            if (driver instanceof HasCapabilities hc) {
                Object a = hc.getCapabilities().getCapability("appium:automationName");
                if (a == null) a = hc.getCapabilities().getCapability("automationName");
                if (a != null) automation = a.toString();
            }
            io.appium.java_client.pagefactory.DefaultElementByBuilder builder =
                    new io.appium.java_client.pagefactory.DefaultElementByBuilder(platform, automation);
            builder.setAnnotated(field);
            return builder.buildBy();
        }
    }
}
