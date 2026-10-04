package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealingPageFactoryTest {

    static class Base {
        @Element(value = "Header logo", css = ".logo") String logo;
    }

    static class LoginPage extends Base {
        @Element(value = "Email field", id = "email") String email;
        @Element(value = "Password field", id = "password") String password;
        String notAnElement = "untouched";
        private final Object driver;

        LoginPage() { this.driver = null; }
        LoginPage(Object driver) { this.driver = driver; }
    }

    static class BadPage {
        @Element(value = "static", id = "x") static String nope;
    }

    static class CapturingHealer implements Healer {
        final List<ElementSpec> specs = new ArrayList<>();
        @Override public Framework framework() { return Framework.SELENIUM; }
        @Override public HealingConfig config() { return HealingConfig.builder().reportPath(null).learnedLocatorsPath(null).build(); }
        @Override public Object createElement(ElementSpec spec, Class<?> fieldType) {
            specs.add(spec);
            return "proxy:" + spec.field().getName();
        }
        @Override public void invalidate(LocatorSpec spec) {}
        @Override public void invalidateAll() {}
        @Override public void close() {}
    }

    @Test
    void injectsProxiesIntoAnnotatedFieldsAcrossHierarchy() {
        CapturingHealer healer = new CapturingHealer();
        LoginPage page = HealingPageFactory.create(LoginPage.class, healer, null);

        assertEquals("proxy:email", page.email);
        assertEquals("proxy:password", page.password);
        assertEquals("proxy:logo", page.logo);
        assertEquals("untouched", page.notAnElement);
        assertEquals(3, healer.specs.size());
        assertTrue(healer.specs.stream().allMatch(s -> s.pageClass() == LoginPage.class));
    }

    @Test
    void prefersConstructorTakingTheNativeDriver() {
        Object driver = new Object();
        LoginPage page = HealingPageFactory.create(LoginPage.class, new CapturingHealer(), driver);
        assertEquals(driver, page.driver);

        LoginPage noArg = HealingPageFactory.create(LoginPage.class, new CapturingHealer(), null);
        assertNull(noArg.driver);
    }

    @Test
    void rejectsStaticFields() {
        assertThrows(IllegalStateException.class,
                () -> HealingPageFactory.initElements(new BadPage(), new CapturingHealer()));
    }
}
