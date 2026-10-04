package tech.ishabbi.graft.examples.playwright;

import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.Graft;
import tech.ishabbi.graft.Healer;
import tech.ishabbi.graft.HealingSelector;
import tech.ishabbi.graft.playwright.PlaywrightHealer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Run with {@code GRAFT_E2E=true} and an AI provider configured. */
@EnabledIfEnvironmentVariable(named = "GRAFT_E2E", matches = "true")
class PlaywrightExample {

    static class CalculatorPage {
        @Element(value = "the '7' digit button", testId = "key-seven-renamed")   // wrong → heals
        Locator seven;

        @Element(value = "the '+' plus operator button", selector = "button.operator-plus")  // wrong → heals
        Locator plus;

        @Element(value = "the '2' digit button", css = "#\\32")   // right: CSS for id="2"
        Locator two;

        @Element(value = "the '=' equals button", id = "equal")
        Locator equals;

        @Element("the calculator display")
        Locator display;
    }

    /** Constants: a selector string or any Locator-producing function, resolved against a Page. */
    static final class Calculator {
        static final HealingSelector SEVEN = Graft.selector("[data-testid=key-seven-renamed]", "the '7' digit button");
        static final HealingSelector PLUS  = PlaywrightHealer.selector(
                p -> p.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("nope")), "the '+' plus operator button");
        static final HealingSelector DISPLAY = Graft.selector(null, "the calculator display");
    }

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private Healer healer;

    @BeforeEach
    void setUp() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        healer = Graft.with(page);
    }

    @AfterEach
    void tearDown() {
        healer.close();
        browser.close();
        playwright.close();
    }

    @Test
    void addsNumbersThroughHealedLocators() {
        page.navigate("https://seleniumbase.io/apps/calculator");
        CalculatorPage calc = Graft.page(CalculatorPage.class, healer, page);

        calc.seven.click();
        calc.plus.click();
        calc.two.click();
        calc.equals.click();

        assertThat(calc.display).containsText("9");   // the proxy is a real Locator: assertions work
    }

    @Test
    void worksWithSelectorConstants() {
        page.navigate("https://seleniumbase.io/apps/calculator");
        PlaywrightHealer.locator(page, Calculator.SEVEN).click();   // registry lookup by Page
        PlaywrightHealer.locator(page, Calculator.PLUS).click();
        assertThat(PlaywrightHealer.locator(page, Calculator.DISPLAY)).containsText("7");
    }
}
