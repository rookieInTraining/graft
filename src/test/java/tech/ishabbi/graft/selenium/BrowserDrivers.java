package tech.ishabbi.graft.selenium;

import org.junit.jupiter.api.Assumptions;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Headless Chrome for the gated browser tests: Chrome through Selenium Manager, else Playwright's
 * bundled Chromium. Skips the calling test class (an assumption failure) when neither starts.
 */
final class BrowserDrivers {

    private BrowserDrivers() {}

    static WebDriver headlessChrome() {
        WebDriver driver = null;
        String failure;
        try {
            driver = new ChromeDriver(options());
            failure = null;
        } catch (RuntimeException e) {
            failure = e.toString();
            Path chromium = playwrightChromium();
            if (chromium != null) {
                try {
                    driver = new ChromeDriver(options().setBinary(chromium.toFile()));
                } catch (RuntimeException again) {
                    failure += " / with " + chromium + ": " + again;
                }
            }
        }
        Assumptions.assumeTrue(driver != null, "No Chrome/chromedriver available: " + failure);
        return driver;
    }

    private static ChromeOptions options() {
        return new ChromeOptions().addArguments("--headless=new");
    }

    private static Path playwrightChromium() {
        Path root = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "ms-playwright");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("chromium-"))
                    .sorted((a, b) -> b.compareTo(a))
                    .flatMap(d -> Stream.of(d.resolve("chrome-win64/chrome.exe"), d.resolve("chrome-win/chrome.exe")))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
