package tech.ishabbi.graft.playwright;

import tech.ishabbi.graft.LocatorSpec;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Delegates every {@link Locator} call to the resolved locator.
 *
 * <p>Playwright actions auto-wait, so a {@link TimeoutError} from {@code click()} can mean many
 * things (covered, disabled, detached). The handler only heals when, at the moment of the
 * timeout, the primary locator matches <em>zero</em> nodes — the signature of a selector that no
 * longer exists. Other timeouts surface unchanged. Used by {@code @Element} fields and
 * {@code HealingSelector} constants alike.
 */
final class HealingLocatorHandler implements InvocationHandler {

    private final PlaywrightHealer healer;
    private final LocatorSpec spec;
    private final Supplier<Locator> primary;   // returns null for description-only locators

    HealingLocatorHandler(PlaywrightHealer healer, LocatorSpec spec, Supplier<Locator> primary) {
        this.healer = healer;
        this.spec = spec;
        this.primary = primary;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString": return "HealingLocator(" + spec.displayName() + ", \"" + spec.description() + "\")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: break;
            }
        }

        Locator locator = healer.resolve(spec, primary);
        try {
            return method.invoke(locator, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof TimeoutError timeout && !healer.wasHealed(spec) && matchesNothing(locator)) {
                Locator healed = healer.healFrom(spec, locator, timeout);
                try {
                    return method.invoke(healed, args);
                } catch (InvocationTargetException again) {
                    throw again.getCause();
                }
            }
            throw cause;
        }
    }

    private static boolean matchesNothing(Locator locator) {
        try {
            return locator.count() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
