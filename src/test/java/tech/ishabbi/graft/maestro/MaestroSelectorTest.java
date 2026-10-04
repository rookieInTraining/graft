package tech.ishabbi.graft.maestro;

import tech.ishabbi.graft.Element;
import tech.ishabbi.graft.ElementSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaestroSelectorTest {

    static class Screen {
        @Element(value = "Email", id = "email_input") MaestroElement byId;
        @Element(value = "Sign in", text = "Sign \"in\"") MaestroElement byText;
        @Element(value = "Avatar", accessibilityId = "avatar") MaestroElement byA11y;
        @Element(value = "Nope", xpath = "//x") MaestroElement unsupported;
        @Element("Just a description") MaestroElement descriptionOnly;
    }

    private static ElementSpec spec(String f) throws Exception {
        return ElementSpec.of(Screen.class, Screen.class.getDeclaredField(f));
    }

    @Test
    void rendersCommands() throws Exception {
        assertEquals("- tapOn:\n    id: \"email_input\"", MaestroSelector.from(spec("byId")).command("tapOn"));
        assertEquals("- assertVisible:\n    text: \"Sign \\\"in\\\"\"",
                MaestroSelector.from(spec("byText")).command("assertVisible"));
        assertEquals("- tapOn:\n    point: \"50%,50%\"", MaestroSelector.point("50%,50%").command("tapOn"));
    }

    @Test
    void accessibilityIdMapsToId() throws Exception {
        MaestroSelector s = MaestroSelector.from(spec("byA11y"));
        assertEquals(MaestroSelector.Kind.ID, s.kind());
        assertEquals("avatar", s.value());
    }

    @Test
    void descriptionOnlyHasNoSelector() throws Exception {
        assertNull(MaestroSelector.from(spec("descriptionOnly")));
    }

    @Test
    void unsupportedKindsAreRejectedUpFront() {
        assertThrows(IllegalStateException.class, () -> MaestroSelector.from(spec("unsupported")));
    }

    @Test
    void resultClassifiesMaestroOutput() {
        var notFound = new MaestroDevice.MaestroResult(1,
                "❌ Tap on id: login_btn\nElement not found: Id matching regex: login_btn", Path.of("f.yaml"), "");
        assertTrue(notFound.elementNotFound());
        assertFalse(notFound.assertionFailed());
        assertEquals("Element not found: Id matching regex: login_btn", notFound.failureSummary());

        var assertion = new MaestroDevice.MaestroResult(1, "Assertion is false: Text matching regex: Welcome", Path.of("f"), "");
        assertTrue(assertion.assertionFailed());

        var ok = new MaestroDevice.MaestroResult(0, "✅ Tap on id: login_btn", Path.of("f"), "");
        assertTrue(ok.success());
        assertFalse(ok.elementNotFound());
    }
}
