package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.LocatorSpec;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Mirrors Selenium's {@code LocatingElementHandler}: every call resolves the element, then
 * delegates. Resolution may heal, and a {@link StaleElementReferenceException} triggers a
 * re-resolve instead of surfacing. Used by {@code @Element} fields and {@code HealingBy.proxied()}.
 */
final class HealingElementHandler implements InvocationHandler {

    private final SeleniumHealer healer;
    private final LocatorSpec spec;
    private final By primary;            // null → description-only
    private final SearchContext context; // driver, or the element the lookup is scoped to

    HealingElementHandler(SeleniumHealer healer, LocatorSpec spec, By primary, SearchContext context) {
        this.healer = healer;
        this.spec = spec;
        this.primary = primary;
        this.context = context;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString": return "HealingElement(" + spec.displayName() + ", \"" + spec.description() + "\")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: break;
            }
        }

        WebElement element = healer.resolve(spec, primary, context);
        if ("getWrappedElement".equals(method.getName()) && method.getParameterCount() == 0) {
            return element;
        }

        try {
            return method.invoke(element, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof StaleElementReferenceException stale) {
                WebElement fresh = healer.reResolveAfterStale(spec, primary, context, stale);
                try {
                    return method.invoke(fresh, args);
                } catch (InvocationTargetException again) {
                    throw again.getCause();
                }
            }
            throw cause;
        }
    }
}
