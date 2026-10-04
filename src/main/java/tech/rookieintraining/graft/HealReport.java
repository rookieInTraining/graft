package tech.rookieintraining.graft;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link HealEvent}s for the JVM and writes them as JSON. One instance is shared by
 * all healers (healing is a cross-cutting concern; a run-level report is what you want to
 * read in CI), and it is flushed on JVM shutdown and on every {@link Healer#close()}.
 *
 * <p>The file is the hand-off to engineers: each entry has {@code originalLocator} (what broke),
 * {@code suggestedLocator} (what to replace it with) and the page class/field to edit.
 */
public final class HealReport implements HealListener {

    private static final HealReport GLOBAL = new HealReport();
    private static volatile boolean hookInstalled;

    private final List<HealEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> failures = Collections.synchronizedList(new ArrayList<>());

    public static HealReport global() { return GLOBAL; }

    /** Installs a shutdown hook writing the global report to {@code path} (idempotent). */
    public static void writeOnExit(Path path) {
        if (path == null || hookInstalled) return;
        synchronized (HealReport.class) {
            if (hookInstalled) return;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> GLOBAL.writeIfNotEmpty(path), "graft-report"));
            hookInstalled = true;
        }
    }

    @Override
    public void onHeal(HealEvent event) {
        events.add(event);
    }

    @Override
    public void onHealFailed(LocatorSpec spec, Throwable cause) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("element", spec.key());
        m.put("description", spec.description());
        m.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        failures.add(m);
    }

    public List<HealEvent> events() { return new ArrayList<>(events); }

    public boolean isEmpty() { return events.isEmpty() && failures.isEmpty(); }

    public void writeIfNotEmpty(Path path) {
        if (!isEmpty()) write(path);
    }

    public void write(Path path) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> healed = new ArrayList<>();
        synchronized (events) {
            for (HealEvent e : events) healed.add(e.toMap());
        }
        root.put("healed", healed);
        root.put("unhealed", new ArrayList<>(failures));
        root.put("totals", Map.of("healed", healed.size(), "unhealed", failures.size()));
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write heal report to " + path, e);
        }
    }
}
