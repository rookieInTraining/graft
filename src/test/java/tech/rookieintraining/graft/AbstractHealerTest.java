package tech.rookieintraining.graft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractHealerTest {

    static class Page {
        @Element(value = "Submit button", id = "submit") Object submit;
        @Element(value = "No heal", id = "x", heal = false) Object noHeal;
    }

    /** Minimal healer: the "lookup" is whatever the test supplies. */
    static class FakeHealer extends AbstractHealer {
        FakeHealer(HealingConfig c) { super(c); }
        @Override public Framework framework() { return Framework.SELENIUM; }
        @Override public Object createElement(ElementSpec spec, Class<?> t) { return null; }
        @Override public void invalidate(LocatorSpec spec) {}
        @Override public void invalidateAll() {}
        @Override public void close() {}

        String healIt(LocatorSpec spec, java.util.function.Supplier<String> lookup) {
            return heal(spec, "By.id: submit", new RuntimeException("no such element"), lookup,
                    r -> "found " + r, r -> LocatorSuggestion.of("id", r));
        }
    }

    static class Recording implements HealListener {
        final List<HealEvent> healed = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<Throwable> failed = new ArrayList<>();
        @Override public void onHeal(HealEvent e) { healed.add(e); }
        @Override public void onHealSkipped(LocatorSpec s, String why) { skipped.add(why); }
        @Override public void onHealFailed(LocatorSpec s, Throwable t) { failed.add(t); }
    }

    private static ElementSpec spec(String f) throws Exception {
        return ElementSpec.of(Page.class, Page.class.getDeclaredField(f));
    }

    private static HealingConfig.Builder config(Recording r) {
        return HealingConfig.builder().reportPath(null).learnedLocatorsPath(null).addListener(r);
    }

    @Test
    void successfulHealReturnsResultAndEmitsEvent() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).build());

        String out = h.healIt(spec("submit"), () -> "new-id");

        assertEquals("new-id", out);
        assertEquals(1, r.healed.size());
        HealEvent e = r.healed.get(0);
        assertEquals("By.id: submit", e.originalLocator());
        assertEquals("found new-id", e.healedTo());
        assertEquals("@Element(id = \"new-id\")", e.suggestedLocator());
        assertEquals(LocatorSuggestion.of("id", "new-id"), e.suggestion());
        assertEquals(Page.class.getName() + "#submit", e.origin());
        assertEquals(1, h.healCount(spec("submit")));
    }

    @Test
    void disabledHealingRethrowsOriginalFailure() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).enabled(false).build());

        RuntimeException e = assertThrows(RuntimeException.class, () -> h.healIt(spec("submit"), () -> "x"));
        assertEquals("no such element", e.getMessage());
        assertEquals(List.of("healing disabled"), r.skipped);
    }

    @Test
    void elementLevelOptOutRethrows() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).build());

        assertThrows(RuntimeException.class, () -> h.healIt(spec("noHeal"), () -> "x"));
        assertEquals(List.of("heal disabled for this locator"), r.skipped);
    }

    @Test
    void strictModeHealsThenFailsWithEvent() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).strict(true).build());

        HealingException e = assertThrows(HealingException.class, () -> h.healIt(spec("submit"), () -> "new-id"));
        assertNotNull(e.event(), "strict failure carries the heal event");
        assertEquals(1, r.healed.size(), "the heal still happened so the report has a suggestion");
        assertTrue(e.getMessage().contains("@Element(id = \"new-id\")"));
    }

    @Test
    void budgetCapsAlumniumCalls() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).maxHealsPerElement(2).build());
        AtomicInteger calls = new AtomicInteger();

        h.healIt(spec("submit"), () -> "a" + calls.incrementAndGet());
        h.healIt(spec("submit"), () -> "a" + calls.incrementAndGet());
        HealingException e = assertThrows(HealingException.class,
                () -> h.healIt(spec("submit"), () -> "a" + calls.incrementAndGet()));

        assertEquals(2, calls.get(), "third call never reached Alumnium");
        assertTrue(e.getMessage().contains("budget exhausted"));
        assertNull(e.event());
    }

    @Test
    void alumniumFailureBecomesHealingExceptionWithBothCauses() throws Exception {
        Recording r = new Recording();
        FakeHealer h = new FakeHealer(config(r).build());
        RuntimeException alumniumError = new RuntimeException("LLM could not find it");

        HealingException e = assertThrows(HealingException.class,
                () -> h.healIt(spec("submit"), () -> { throw alumniumError; }));

        assertEquals("no such element", e.getCause().getMessage());
        assertSame(alumniumError, e.getSuppressed()[0]);
        assertEquals(1, r.failed.size());
    }
}
