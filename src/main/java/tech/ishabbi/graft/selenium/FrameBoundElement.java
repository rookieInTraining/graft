package tech.ishabbi.graft.selenium;

import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.StaleElementReferenceException;
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
 *
 * <p>When the frame hops cannot be entered any more (the iframe was removed), or the element is not
 * in the document they enter (the iframe was replaced, {@link #asStale}), a call throws
 * {@link StaleElementReferenceException} with that failure as its cause, so
 * {@code ExpectedConditions.stalenessOf(child)} keeps working.
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
            try {
                healer.enter(located);
            } catch (NotFoundException gone) {
                throw new StaleElementReferenceException(
                        "The frame of this element cannot be entered: " + gone.getMessage(), gone);
            }
            return bind(healer, located, method.invoke(located.element(), args));
        } catch (InvocationTargetException ite) {
            StaleElementReferenceException stale = asStale(ite.getCause(), located.element());
            throw stale != null ? stale : ite.getCause();
        } finally {
            state.restore();
        }
    }

    /**
     * {@code failure}, thrown by a call on {@code el} made inside el's frame, as a stale-element
     * failure, or {@code null} when it is something else. Besides {@link StaleElementReferenceException}
     * itself: when the iframe was replaced, the hops enter the new document, where Chrome reports
     * the old element as "no such element", not stale. A {@code NoSuchElementException} counts as
     * stale only when {@code el} itself is unknown there (a cheap probe), so a missing child of
     * {@code findElement} is not mistaken for it.
     */
    static StaleElementReferenceException asStale(Throwable failure, WebElement el) {
        if (failure instanceof StaleElementReferenceException stale) return stale;
        if (!(failure instanceof NoSuchElementException)) return null;
        try {
            el.getTagName();
            return null;
        } catch (NotFoundException | StaleElementReferenceException gone) {
            return new StaleElementReferenceException(
                    "The element is no longer in its frame's document: " + failure.getMessage(), failure);
        } catch (RuntimeException probeFailed) {
            return null;
        }
    }
}
