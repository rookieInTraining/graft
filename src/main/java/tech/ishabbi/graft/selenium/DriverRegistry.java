package tech.ishabbi.graft.selenium;

import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsDriver;
import org.openqa.selenium.WrapsElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the {@link SeleniumHealer} for a {@link SearchContext}, which is all a {@code By} gets
 * handed. This is what lets {@code static final By LOGIN = Graft.by(...)} work without a driver
 * in scope.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>by WebDriver <b>session id</b> — the key that survives Selenium Grid, {@code Augmenter},
 *       {@code EventFiringDecorator} and any other wrapper that hands you a different object for
 *       the same session;</li>
 *   <li>by driver identity (weak), for non-remote drivers;</li>
 *   <li>the healer most recently created on this thread (ThreadLocal-driver frameworks).</li>
 * </ol>
 * Elements and decorated drivers are unwrapped via {@link WrapsDriver}/{@link WrapsElement} first.
 */
public final class DriverRegistry {

    private static final Map<String, SeleniumHealer> BY_SESSION = new ConcurrentHashMap<>();
    private static final Map<WebDriver, SeleniumHealer> BY_IDENTITY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<SeleniumHealer> CURRENT = new ThreadLocal<>();

    private DriverRegistry() {}

    static void register(SeleniumHealer healer) {
        WebDriver driver = healer.driver();
        sessionId(driver).ifPresent(id -> BY_SESSION.put(id, healer));
        BY_IDENTITY.put(driver, healer);
        WebDriver inner = unwrapDriver(driver);
        if (inner != null && inner != driver) BY_IDENTITY.put(inner, healer);
        CURRENT.set(healer);
    }

    static void unregister(SeleniumHealer healer) {
        WebDriver driver = healer.driver();
        sessionId(driver).ifPresent(id -> BY_SESSION.remove(id, healer));
        BY_IDENTITY.entrySet().removeIf(e -> e.getValue() == healer);
        if (CURRENT.get() == healer) CURRENT.remove();
    }

    public static Optional<SeleniumHealer> lookup(SearchContext context) {
        WebDriver driver = unwrapDriver(context);
        if (driver != null) {
            Optional<String> id = sessionId(driver);
            if (id.isPresent()) {
                SeleniumHealer h = BY_SESSION.get(id.get());
                if (h != null) return Optional.of(h);
            }
            SeleniumHealer h = BY_IDENTITY.get(driver);
            if (h != null) return Optional.of(h);
        }
        return Optional.ofNullable(CURRENT.get());
    }

    public static SeleniumHealer require(SearchContext context) {
        return lookup(context).orElseThrow(() -> new IllegalStateException(
                "No SeleniumHealer registered for " + context + ". Call Graft.with(driver) "
                        + "(or SeleniumHealer.of(driver)) before using HealingBy/AlumniumBy, "
                        + "and keep it open for the session's lifetime."));
    }

    /** The healer most recently created on this thread, if any. */
    public static Optional<SeleniumHealer> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Walks {@code WrapsElement} → {@code WrapsDriver} → driver until it reaches the innermost driver. */
    static WebDriver unwrapDriver(SearchContext context) {
        Object current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof WrapsElement we && !(current instanceof WebDriver)) {
                WebElement inner = we.getWrappedElement();
                if (inner == current) break;
                current = inner;
                continue;
            }
            if (current instanceof WrapsDriver wd) {
                WebDriver inner = wd.getWrappedDriver();
                if (inner == null || inner == current) break;
                current = inner;
                continue;
            }
            break;
        }
        return current instanceof WebDriver d ? d : null;
    }

    static Optional<String> sessionId(WebDriver driver) {
        WebDriver inner = unwrapDriver(driver);
        WebDriver d = inner != null ? inner : driver;
        if (d instanceof RemoteWebDriver remote) {
            SessionId id = remote.getSessionId();
            if (id != null) return Optional.of(id.toString());
        }
        return Optional.empty();
    }
}
