package tech.ishabbi.graft.examples.appium;

import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.Graft;
import tech.ishabbi.graft.Healer;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.pagefactory.AndroidFindBy;
import io.appium.java_client.pagefactory.iOSXCUITFindBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.WebElement;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appium drivers are WebDrivers, so this is the Selenium path with Appium locators.
 * Run with {@code GRAFT_E2E=true}, an Appium server on :4723 and an emulator.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_E2E", matches = "true")
class AppiumExample {

    static class LoginScreen {
        // Existing cross-platform annotations stay; only the description is new.
        @Element("the username text field on the login form")
        @AndroidFindBy(id = "com.example:id/username_old")        // renamed in the app → heals
        @iOSXCUITFindBy(accessibility = "username")
        WebElement username;

        @Element(value = "the password text field", accessibilityId = "password")
        WebElement password;

        @Element(value = "the 'Login' button", androidUIAutomator = "new UiSelector().text(\"Login\")")
        WebElement login;

        @Element("the welcome message shown after a successful login")
        WebElement welcome;
    }

    private AndroidDriver driver;
    private Healer healer;

    @BeforeEach
    void setUp() throws Exception {
        UiAutomator2Options options = new UiAutomator2Options()
                .setApp(System.getProperty("app", "/path/to/app.apk"))
                .setAutoGrantPermissions(true);
        driver = new AndroidDriver(new URL("http://127.0.0.1:4723"), options);
        healer = Graft.with(driver);
    }

    @AfterEach
    void tearDown() {
        healer.close();
        driver.quit();
    }

    @Test
    void logsIn() {
        LoginScreen screen = Graft.page(LoginScreen.class, healer, driver);
        screen.username.sendKeys("ish");
        screen.password.sendKeys("secret");
        screen.login.click();
        assertTrue(screen.welcome.isDisplayed());
    }
}
