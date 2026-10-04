package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Within;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

/**
 * Applies a {@code within} chain ({@link Within}) to a Selenium search context, outside-in:
 * {@code frame=<css>} finds the iframe in the current scope and switches the driver into it;
 * {@code shadow=<css>} finds the host in the current scope and continues in its open shadow root.
 *
 * <p>From the driver, the chain is absolute: the driver goes to {@code defaultContent()} first.
 * From an element or a shadow root, the chain starts in that context. An empty chain returns the
 * context unchanged and makes no calls.
 *
 * <p>A frame hop leaves the driver switched into that frame; restoring the test's frame is the
 * caller's job. A missing iframe, host, or shadow root surfaces as {@link NoSuchElementException}.
 */
final class ContextResolver {

    /** Where the search runs after the chain, and whether that is inside a shadow root (CSS only). */
    record Scope(SearchContext context, boolean inShadow) {}

    private ContextResolver() {}

    static Scope enter(WebDriver driver, SearchContext context, List<Within.Hop> within) {
        if (within.isEmpty()) return new Scope(context, false);
        SearchContext current = context;
        boolean inShadow = false;
        if (context instanceof WebDriver) driver.switchTo().defaultContent();
        for (Within.Hop hop : within) {
            try {
                WebElement found = current.findElement(By.cssSelector(hop.css()));
                if (hop.type() == Within.Type.FRAME) {
                    driver.switchTo().frame(found);
                    current = driver;
                    inShadow = false;
                } else {
                    current = found.getShadowRoot();
                    inShadow = true;
                }
            } catch (NoSuchElementException e) {
                throw e;
            } catch (NotFoundException e) {   // NoSuchShadowRootException, NoSuchFrameException
                NoSuchElementException miss = new NoSuchElementException(
                        "Cannot enter " + hop.format() + ": " + e.getMessage());
                miss.initCause(e);
                throw miss;
            }
        }
        return new Scope(current, inShadow);
    }

    /** True when the chain's last hop is a shadow root, so the locator after it must be CSS. */
    static boolean endsInShadow(List<Within.Hop> within) {
        return !within.isEmpty() && within.get(within.size() - 1).type() == Within.Type.SHADOW;
    }
}
