package tech.ishabbi.graft.selenium;

import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsElement;
import org.openqa.selenium.interactions.Locatable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain element that lives in an iframe, reached through a proxy that switches & restores:
 * each call captures the test's frame, enters the same frame hops (from the same context) as the
 * proxy it came from, delegates, and restores the test's frame. No healing. Children it finds are
 * frame-bound the same way.
 *
 * <p>This is what {@code findElement}/{@code findElements} return on a proxy whose element sits in
 * an iframe: without it, the child would be a raw in-iframe element while the driver is back in
 * the test's frame, which Selenium reports as stale.
 */
final class FrameBoundElement implements InvocationHandler {

    private final SeleniumHealer healer;
    private final Located located;

    private FrameBoundElement(SeleniumHealer healer, Located located) {
        this.healer = healer;
        this.located = located;
    }

    /**
     * {@code result} of a call made inside {@code frames} of {@code parent}: a {@link WebElement},
     * or a list of them, comes back frame-bound; anything else unchanged.
     */
    static Object bind(SeleniumHealer healer, Located parent, Object result) {
        if (parent.frames().isEmpty()) return result;
        if (result instanceof WebElement el) return wrap(healer, parent, el);
        if (result instanceof List<?> list && list.stream().allMatch(o -> o instanceof WebElement)) {
            List<WebElement> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(wrap(healer, parent, (WebElement) o));
            return out;
        }
        return result;
    }

    private static WebElement wrap(SeleniumHealer healer, Located parent, WebElement el) {
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[] {WebElement.class, WrapsElement.class, Locatable.class},
                new FrameBoundElement(healer, new Located(el, parent.frames(), parent.from())));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString": return "FrameBoundElement(" + located.element() + " in "
                        + String.join(" > ", tech.ishabbi.graft.Within.formatAll(located.frames())) + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: break;
            }
        }
        if ("getWrappedElement".equals(method.getName()) && method.getParameterCount() == 0) {
            return located.element();
        }
        FrameState state = healer.frameState();
        try {
            healer.enter(located);
            return bind(healer, located, method.invoke(located.element(), args));
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        } finally {
            state.restore();
        }
    }
}
