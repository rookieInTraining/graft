package tech.rookieintraining.graft.selenium;

import tech.rookieintraining.graft.HealingSelector;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;

import java.util.List;
import java.util.Objects;

/**
 * A description-only {@link By}: {@code findElement} asks Alumnium directly.
 *
 * <pre>{@code
 * driver.findElement(AlumniumBy.describe("the cookie consent 'Accept all' button")).click();
 * new ByAll(By.id("login"), AlumniumBy.describe("the 'Sign in' button"));   // stock fallback chain
 * }</pre>
 *
 * <p>Deliberately not {@code By.Remotable}: a remotable {@code By} is sent to the wire and this
 * override would never run. Also deliberately not policy-aware — no timeout, budget or report —
 * that is {@link HealingBy}'s job. Inside a {@code WebDriverWait} this calls Alumnium on every
 * poll; Alumnium's element cache makes repeats cheap, but prefer {@link HealingBy} for waits.
 */
public final class AlumniumBy extends By {

    private final HealingSelector spec;

    private AlumniumBy(HealingSelector spec) {
        this.spec = spec;
    }

    public static AlumniumBy describe(String description) {
        return new AlumniumBy(HealingSelector.describe(description));
    }

    public String description() { return spec.description(); }

    @Override
    public WebElement findElement(SearchContext context) {
        SeleniumHealer healer = DriverRegistry.require(context);
        return healer.findByDescription(spec, context);
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        try {
            return List.of(findElement(context));
        } catch (NoSuchElementException e) {
            return List.of();
        }
    }

    @Override
    public String toString() {
        return "AlumniumBy: \"" + spec.description() + "\"";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AlumniumBy other && Objects.equals(spec.description(), other.spec.description());
    }

    @Override
    public int hashCode() {
        return spec.description().hashCode();
    }
}
