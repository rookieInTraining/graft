package tech.ishabbi.graft.selenium;

import io.appium.java_client.AppiumBy;
import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.ElementSpec;
import tech.ishabbi.graft.Framework;
import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorBuilderTest {

    private static By learned(String kind, String value, boolean cssOnly) {
        return LocatorBuilder.fromSuggestion(LocatorSuggestion.of(kind, value), null, Framework.SELENIUM, cssOnly);
    }

    @Test
    void cssOnlyMappingsForEachKind() {
        assertEquals(By.cssSelector("[id=\"a\\\"b\"]"), learned("id", "a\"b", true));
        assertEquals(By.cssSelector("[name=\"email\"]"), learned("name", "email", true));
        assertEquals(By.cssSelector("[data-testid=\"save\"]"), learned("testId", "save", true));
        assertEquals(By.cssSelector("div > button"), learned("css", "div > button", true));
        assertNull(learned("xpath", "//button", true));
        assertNull(learned("text", "Save", true));
    }

    @Test
    void webMappingsWithoutCssOnlyAreUnchanged() {
        assertEquals(By.id("a"), learned("id", "a", false));
        assertEquals(By.name("email"), learned("name", "email", false));
        assertEquals(By.cssSelector("[data-testid=\"save\"]"), learned("testId", "save", false));
        assertEquals(By.xpath("//button"), learned("xpath", "//button", false));
    }

    @Test
    void textXpathMatchesAnyNonBlankTextNode() {
        assertEquals("By.xpath: //*[@text='Save' or @label='Save' or @name='Save' or text()[normalize-space()='Save']]",
                learned("text", "Save", false).toString());
    }

    static class Fields {
        @Element(value = "x", within = {"shadow=#host"}, xpath = "//button")
        Object xpathInShadow;

        @Element(value = "x", within = {"shadow=#host"}, text = "Save")
        Object textInShadow;

        @Element(value = "x", within = {"frame=#pay"}, xpath = "//button")
        Object xpathInFrame;

        @Element(value = "x", within = {"shadow=#host"}, id = "go")
        Object idInShadow;

        @Element(value = "x", within = {"shadow=#host", "frame=#f"}, text = "Save")
        Object textInFrameInShadow;
    }

    private static ElementSpec spec(String field) throws NoSuchFieldException {
        return ElementSpec.of(Fields.class, Fields.class.getDeclaredField(field));
    }

    @Test
    void inlineXpathOrTextInAShadowRootIsRejected() throws Exception {
        for (String field : List.of("xpathInShadow", "textInShadow")) {
            ElementSpec spec = spec(field);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LocatorBuilder.build(spec, null, Framework.SELENIUM));
            assertTrue(e.getMessage().contains("Fields." + field), e.getMessage());
            assertTrue(e.getMessage().contains("xpath/text locators can't be used inside a shadow root in Selenium"),
                    e.getMessage());
        }
    }

    @Test
    void inlineLocatorsOutsideAShadowRootKeepTheirMapping() throws Exception {
        assertEquals(By.xpath("//button"), LocatorBuilder.build(spec("xpathInFrame"), null, Framework.SELENIUM));
        assertEquals(By.cssSelector("[id=\"go\"]"), LocatorBuilder.build(spec("idInShadow"), null, Framework.SELENIUM));
        // A frame hop after the shadow hop: the locator runs in a plain document again.
        assertTrue(LocatorBuilder.build(spec("textInFrameInShadow"), null, Framework.SELENIUM).toString()
                .startsWith("By.xpath: "));
    }

    @Test
    void healingByXpathInAShadowRootIsRejected() {
        HealingBy by = HealingBy.of(By.xpath("//button"), "the button");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> by.within("shadow=#host"));
        assertTrue(e.getMessage().contains("By.xpath: //button"), e.getMessage());
        assertTrue(e.getMessage().contains("xpath/text locators can't be used inside a shadow root in Selenium"));
        by.within("frame=#pay");                                     // fine outside a shadow root
        HealingBy.of(By.cssSelector("button"), "the button").within("shadow=#host");
    }

    @Test
    void appiumMappingsAreUnchanged() {
        By id = LocatorBuilder.fromSuggestion(LocatorSuggestion.of("id", "com.app:id/go"), null, Framework.APPIUM, false);
        assertEquals(AppiumBy.id("com.app:id/go"), id);
        By testId = LocatorBuilder.fromSuggestion(LocatorSuggestion.of("testId", "go"), null, Framework.APPIUM, false);
        assertEquals(AppiumBy.accessibilityId("go"), testId);
        By a11y = LocatorBuilder.fromSuggestion(LocatorSuggestion.of("accessibilityId", "go"), null, Framework.APPIUM, false);
        assertEquals(AppiumBy.accessibilityId("go"), a11y);
        By text = LocatorBuilder.fromSuggestion(LocatorSuggestion.of("text", "Go"), null, Framework.APPIUM, false);
        assertEquals("By.xpath: //*[@text='Go' or @label='Go' or @name='Go' or text()[normalize-space()='Go']]",
                text.toString());
    }
}
