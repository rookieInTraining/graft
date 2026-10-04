package tech.ishabbi.graft.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.ElementSpec;
import tech.ishabbi.graft.HealingConfig;
import tech.ishabbi.graft.HealingSelector;
import tech.ishabbi.graft.Within;
import tech.ishabbi.graft.cache.MemoryLocatorStore;

/**
 * Hermetic: a recording fake {@link Page} shows exactly which Playwright calls the inline,
 * learned and selector mappings make, with and without a {@code within} chain.
 */
class PlaywrightScopeTest {

    private final List<String> calls = new ArrayList<>();
    private int locators;
    private int frames;
    private final Page page = fake(Page.class, "page");
    private final PlaywrightHealer healer = PlaywrightHealer.withFinder(page, d -> {
        throw new AssertionError("finder must not be called");
    }, HealingConfig.builder().reportPath(null).learnedStore(new MemoryLocatorStore()).build());

    @AfterEach
    void close() {
        healer.close();
    }

    @SuppressWarnings("unused")
    static class Fields {
        @Element(value = "d", id = "a\"b") Object id;
        @Element(value = "d", css = "#c") Object css;
        @Element(value = "d", selector = "role=button") Object selector;
        @Element(value = "d", xpath = "//x") Object xpath;
        @Element(value = "d", name = "n") Object name;
        @Element(value = "d", text = "Hi") Object text;
        @Element(value = "d", testId = "t") Object testId;
        @Element(value = "d", within = {"frame=#pay"}, xpath = "//x") Object xpathInFrame;
        @Element(value = "d", within = {"shadow=#card", "frame=#pay"}, xpath = "//x") Object xpathUnderShadow;
        @Element(value = "d", within = {"shadow=#a", "frame=#f", "shadow=#b"}, testId = "t") Object testIdWithin;
    }

    @Test
    void emptyWithinInlineMappingMakesTheSamePageCallsAsBefore() {
        assertInline("id", "page.locator([id=\"a\\\"b\"])");
        assertInline("css", "page.locator(#c)");
        assertInline("selector", "page.locator(role=button)");
        assertInline("xpath", "page.locator(xpath=//x)");
        assertInline("name", "page.locator([name=\"n\"])");
        assertInline("text", "page.getByText(Hi, exact=true)");
        assertInline("testId", "page.getByTestId(t)");
    }

    @Test
    void emptyWithinLearnedMappingMakesTheSamePageCallsAsBefore() {
        assertFind(List.of(), "id", "a\"b", "page.locator([id=\"a\\\"b\"])");
        assertFind(List.of(), "css", "#c", "page.locator(#c)");
        assertFind(List.of(), "xpath", "//x", "page.locator(xpath=//x)");
        assertFind(List.of(), "name", "n", "page.locator([name=\"n\"])");
        assertFind(List.of(), "text", "Hi", "page.getByText(Hi, exact=true)");
        assertFind(List.of(), "testId", "t", "page.getByTestId(t)");
    }

    @Test
    void unknownLearnedKindIsAMiss() {
        assertNull(PlaywrightScope.enter(page, List.of()).find("accessibilityId", "x"));
        assertEquals(List.of(), calls);
    }

    @Test
    void hopsAreAppliedOutsideIn() {
        assertFind(Within.parseAll("shadow=#a", "frame=#f", "shadow=#b"), "testId", "t",
                "page.locator(#a)", "L1.frameLocator(#f)", "F1.locator(#b)", "L2.getByTestId(t)");
    }

    @Test
    void eachScopeTypeMapsEveryKind() {
        assertFind(Within.parseAll("frame=#f"), "text", "Hi", "page.frameLocator(#f)", "F1.getByText(Hi, exact=true)");
        assertFind(Within.parseAll("frame=#f"), "xpath", "//x", "page.frameLocator(#f)", "F1.locator(xpath=//x)");
        assertFind(Within.parseAll("frame=#f"), "id", "i", "page.frameLocator(#f)", "F1.locator([id=\"i\"])");
        assertFind(Within.parseAll("shadow=#h"), "text", "Hi", "page.locator(#h)", "L1.getByText(Hi, exact=true)");
        assertFind(Within.parseAll("shadow=#h"), "name", "n", "page.locator(#h)", "L1.locator([name=\"n\"])");
        assertFind(Within.parseAll("shadow=#h", "frame=#f"), "css", "b", "page.locator(#h)", "L1.frameLocator(#f)",
                "F1.locator(b)");
    }

    @Test
    void inlineWithinUsesTheScope() {
        assertEquals(List.of("page.locator(#a)", "L1.frameLocator(#f)", "F1.locator(#b)", "L2.getByTestId(t)"),
                inline("testIdWithin"));
    }

    @Test
    void xpathUnderAShadowHopIsRejected() {
        IllegalArgumentException learned = assertThrows(IllegalArgumentException.class,
                () -> PlaywrightScope.enter(page, Within.parseAll("shadow=#card", "frame=#pay")).find("xpath", "//x"));
        assertTrue(learned.getMessage().contains("shadow"), learned.getMessage());

        IllegalArgumentException inline = assertThrows(IllegalArgumentException.class,
                () -> healer.primaryLocator(spec("xpathUnderShadow")));
        assertTrue(inline.getMessage().contains("Fields.xpathUnderShadow"), inline.getMessage());

        reset();
        assertEquals(List.of("page.frameLocator(#pay)", "F1.locator(xpath=//x)"), inline("xpathInFrame"));
    }

    @Test
    void selectorStringWithAndWithoutWithin() {
        healer.locator(HealingSelector.of("#go", "go")).count();
        assertEquals("page.locator(#go)", calls.get(0));

        reset();
        healer.locator(HealingSelector.of("#go", "go").within("frame=#f", "shadow=#h")).count();
        assertEquals(List.of("page.frameLocator(#f)", "F1.locator(#h)", "L1.locator(#go)"), calls.subList(0, 3));
    }

    @Test
    void selectorFunctionWithWithinIsRejected() {
        Function<Page, Locator> fn = p -> p.locator("#go");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> healer.locator(HealingSelector.of(fn, "go").within("frame=#f")));
        assertTrue(e.getMessage().contains("within"), e.getMessage());

        healer.locator(HealingSelector.of(fn, "go")).count();
        assertEquals("page.locator(#go)", calls.get(0));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void reset() {
        calls.clear();
        locators = 0;
        frames = 0;
    }

    private void assertInline(String field, String expected) {
        reset();
        assertEquals(List.of(expected), inline(field), field);
    }

    private List<String> inline(String field) {
        healer.primaryLocator(spec(field));
        return List.copyOf(calls);
    }

    private void assertFind(List<Within.Hop> within, String kind, String value, String... expected) {
        reset();
        PlaywrightScope.enter(page, within).find(kind, value);
        assertEquals(List.of(expected), calls, kind + " in " + within);
    }

    private static ElementSpec spec(String field) {
        try {
            return ElementSpec.of(Fields.class, Fields.class.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** A proxy that records {@code name.method(args)} and returns further fakes for Locator / FrameLocator. */
    private <T> T fake(Class<T> type, String name) {
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, (self, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> name;
                };
            }
            List<String> rendered = new ArrayList<>();
            if (args != null) for (Object a : args) rendered.add(render(a));
            calls.add(name + "." + m.getName() + "(" + String.join(", ", rendered) + ")");
            Class<?> r = m.getReturnType();
            if (r == Locator.class) return fake(Locator.class, "L" + (++locators));
            if (r == FrameLocator.class) return fake(FrameLocator.class, "F" + (++frames));
            if (r == int.class) return 0;
            if (r == boolean.class) return false;
            return null;
        });
        return type.cast(proxy);
    }

    /** Strings as-is; option objects as their non-null public fields. */
    private static String render(Object a) {
        if (a == null || a instanceof String) return String.valueOf(a);
        List<String> parts = new ArrayList<>();
        for (Field f : a.getClass().getFields()) {
            try {
                Object v = f.get(a);
                if (v != null) parts.add(f.getName() + "=" + v);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return String.join(", ", parts);
    }
}
