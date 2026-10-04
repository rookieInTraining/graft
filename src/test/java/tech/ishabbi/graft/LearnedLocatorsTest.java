package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnedLocatorsTest {

    static class Page {
        @Element(value = "Submit button", id = "submit") Object submit;
    }

    /** Healer whose Alumnium lookup counts calls and whose learned replay is scripted. */
    static class FakeHealer extends AbstractHealer {
        final AtomicInteger alumniumCalls = new AtomicInteger();
        boolean learnedMatches = true;

        FakeHealer(HealingConfig c) { super(c); }
        @Override public Framework framework() { return Framework.SELENIUM; }
        @Override public Object createElement(ElementSpec spec, Class<?> t) { return null; }
        @Override public void invalidate(LocatorSpec spec) {}
        @Override public void invalidateAll() {}
        @Override public void close() {}

        String resolve(LocatorSpec spec) {
            return heal(spec, "By.id: submit", new RuntimeException("no such element"),
                    learned -> learnedMatches ? "via-learned:" + learned.value() : null,
                    () -> { alumniumCalls.incrementAndGet(); return "via-alumnium"; },
                    r -> r,
                    r -> LocatorSuggestion.of("testId", "submit-btn"));
        }
    }

    @Test
    void secondHealerReplaysWhatTheFirstLearned(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("learned.json");
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedLocatorsPath(store).build();
        ElementSpec spec = ElementSpec.of(Page.class, Page.class.getDeclaredField("submit"));

        FakeHealer first = new FakeHealer(config);
        assertEquals("via-alumnium", first.resolve(spec));
        assertEquals(1, first.alumniumCalls.get());
        assertTrue(Files.exists(store), "write-through");
        assertTrue(Files.readString(store).contains("submit-btn"));

        FakeHealer second = new FakeHealer(config);          // same JVM: shared store instance
        assertEquals("via-learned:submit-btn", second.resolve(spec));
        assertEquals(0, second.alumniumCalls.get(), "learned tier short-circuited the LLM");
    }

    @Test
    void failedLearnedLocatorIsEvictedAndAlumniumAskedAgain(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("learned.json");
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedLocatorsPath(store).build();
        ElementSpec spec = ElementSpec.of(Page.class, Page.class.getDeclaredField("submit"));

        FakeHealer h = new FakeHealer(config);
        h.resolve(spec);                                      // learns testId=submit-btn
        h.learnedMatches = false;                             // UI changed again
        assertEquals("via-alumnium", h.resolve(spec));
        assertEquals(2, h.alumniumCalls.get());
        assertTrue(h.learnedLocators().get(spec.key()).isPresent(), "re-learned from the second heal");
    }

    @Test
    void disabledStoreRemembersNothing() throws Exception {
        HealingConfig config = HealingConfig.builder().reportPath(null).learnedLocatorsPath(null).build();
        ElementSpec spec = ElementSpec.of(Page.class, Page.class.getDeclaredField("submit"));
        FakeHealer h = new FakeHealer(config);
        h.resolve(spec);
        h.resolve(spec);
        assertEquals(2, h.alumniumCalls.get());
        assertTrue(h.learnedLocators().snapshot().isEmpty());
    }

    @Test
    void storeWritesAReadableFile(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("learned.json");
        LearnedLocators a = LearnedLocators.at(store);
        a.learn("k1", LocatorSuggestion.of("css", "[data-test='x']"), "LoginPage.java:12");

        String json = Files.readString(store);
        assertTrue(json.contains("\"kind\": \"css\""));
        assertTrue(json.contains("\"annotation\": \"@Element(css = \\\"[data-test='x']\\\")\""));
        assertTrue(json.contains("LoginPage.java:12"));
        List<String> keys = new ArrayList<>(a.snapshot().keySet());
        assertEquals(List.of("k1"), keys);

        a.forget("k1");
        assertTrue(a.snapshot().isEmpty());
        assertTrue(Files.readString(store).trim().equals("{}"));
    }
}
