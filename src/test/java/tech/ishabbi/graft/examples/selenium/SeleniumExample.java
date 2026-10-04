package tech.ishabbi.graft.examples.selenium;

import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.Graft;
import tech.ishabbi.graft.HealReport;
import tech.ishabbi.graft.Healer;
import tech.ishabbi.graft.HealingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URL;
import java.time.Duration;

import static org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run with {@code GRAFT_E2E=true OPENAI_API_KEY=...} (or any provider Alumnium supports).
 * Set {@code SELENIUM_GRID_URL=http://hub:4444} to run the same test through a Selenium Grid —
 * nothing else changes: the healer is keyed by session id, not by driver class.
 * The locators below are deliberately wrong so every interaction heals.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_E2E", matches = "true")
class SeleniumExample {

    /** Page object: three styles of @Element side by side. */
    static class CalculatorPage {
        // 1. Existing Selenium locator kept, description added. (Wrong id → heals.)
        @Element("the '7' digit button on the calculator keypad")
        @FindBy(id = "seven-does-not-exist")
        WebElement seven;

        // 2. Locator on @Element itself. (Wrong css → heals.)
        @Element(value = "the '+' plus operator button", css = "button.plus-nope")
        WebElement plus;

        @Element(value = "the '2' digit button", xpath = "//button[@id='2']")
        WebElement two;

        @Element(value = "the '=' equals button", xpath = "//button[@id='equal']")
        WebElement equals;

        // 3. Description only: always resolved by Alumnium.
        @Element("the calculator display showing the current value")
        WebElement display;
    }

    /** Locator constants: no page factory, no driver in scope, found through the session registry. */
    static final class Calculator {
        static final By SEVEN = Graft.by(By.id("seven-does-not-exist"), "the '7' digit button");
        static final By PLUS  = Graft.by(By.cssSelector("button.plus-nope"), "the '+' plus operator button");
        static final By TWO   = Graft.by(By.xpath("//button[@id='2']"), "the '2' digit button");
        static final By EQ    = Graft.by(By.xpath("//button[@id='equal']"), "the '=' equals button");
        static final By DISPLAY = Graft.describe("the calculator display showing the current value");
    }

    private WebDriver driver;
    private Healer healer;

    @BeforeEach
    void setUp() throws Exception {
        String grid = System.getenv("SELENIUM_GRID_URL");
        driver = grid == null ? new ChromeDriver() : new RemoteWebDriver(new URL(grid), new ChromeOptions());
        healer = Graft.with(driver, HealingConfig.builder()
                .locatorTimeout(Duration.ofSeconds(2))      // short: we know the locators are broken
                .build());
    }

    @AfterEach
    void tearDown() {
        healer.close();   // quits the Alumni it created; your driver is yours to quit
        driver.quit();
    }

    @Test
    void addsNumbersThroughHealedLocators() {
        driver.get("https://seleniumbase.io/apps/calculator");
        CalculatorPage page = Graft.page(CalculatorPage.class, healer, driver);

        page.seven.click();
        page.plus.click();
        page.two.click();
        page.equals.click();

        assertTrue(page.display.getText().contains("9"));
        assertFalse(HealReport.global().events().isEmpty(), "at least seven/plus were healed");
        HealReport.global().events().forEach(e -> System.out.println(e.summary()));
    }

    @Test
    void addsNumbersThroughHealingByConstants() {
        driver.get("https://seleniumbase.io/apps/calculator");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        wait.until(elementToBeClickable(Calculator.SEVEN)).click();   // polls, heals once, cached after
        driver.findElement(Calculator.PLUS).click();
        driver.findElement(Calculator.TWO).click();
        driver.findElement(Calculator.EQ).click();

        assertTrue(driver.findElement(Calculator.DISPLAY).getText().contains("9"));
    }
}
