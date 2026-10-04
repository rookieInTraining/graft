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
 *
 * <p>Switch &amp; restore: when the element sits in an iframe, each call captures the test's frame
 * ({@link FrameState}), enters the element's frame, delegates, and restores the test's frame,
 * also when the call throws. {@code getWrappedElement()} returns the raw element without switching.
 * Elements that {@code findElement}/{@code findElements} return from such an element (including
 * raw {@link HealingBy} lookups made through it) come back frame-bound ({@link FrameBoundElement}),
 * so they stay usable after the driver is restored.
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

        Located located = healer.resolve(spec, primary, context, false);
        if ("getWrappedElement".equals(method.getName()) && method.getParameterCount() == 0) {
            return located.element();
        }

        FrameState state = located.frames().isEmpty() ? null : healer.frameState();
        try {
            if (state != null) healer.enter(located);
            try {
                return FrameBoundElement.bind(healer, located, method.invoke(located.element(), args));
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (!(cause instanceof StaleElementReferenceException stale)) throw cause;
                // Re-resolve from the test's frame; the fresh element's frame is entered by the re-resolve.
                if (state != null) {
                    state.restore();
                } else {
                    state = healer.frameState();
                }
                Located fresh = healer.reResolveAfterStale(spec, primary, context, stale, state);
                try {
                    return FrameBoundElement.bind(healer, fresh, method.invoke(fresh.element(), args));
                } catch (InvocationTargetException again) {
                    throw again.getCause();
                }
            }
        } finally {
            if (state != null) state.restore();
        }
    }
}
