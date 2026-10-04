package tech.rookieintraining.graft.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tech.rookieintraining.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.rookieintraining.graft.cache.LearnedLocatorStore.ForgetResult.DELETED;

class FileLocatorStoreTest {

    @Test
    void roundTripAndForgetClearsTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        FileLocatorStore store = new FileLocatorStore(file);
        store.learn("k1", LocatorSuggestion.of("css", "[data-test='x']"), "LoginPage.java:12", null);

        String json = Files.readString(file);
        assertTrue(json.contains("\"kind\": \"css\""));
        assertTrue(json.contains("\"annotation\": \"@Element(css = \\\"[data-test='x']\\\")\""));
        assertTrue(json.contains("LoginPage.java:12"));
        assertEquals("css", store.get("k1").orElseThrow().suggestion().kind());

        assertEquals(DELETED, store.forget("k1", "2099-01-01T00:00:00Z"));
        assertTrue(store.snapshot().isEmpty());
        assertEquals("{}", Files.readString(file).trim());
    }

    @Test
    void persistsWithinAndFramework(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        new FileLocatorStore(file).learn("k1", LocatorSuggestion.of("css", "button", List.of("frame=#pay", "shadow=card")),
                "Pay.java:3", "PLAYWRIGHT");

        String json = Files.readString(file);
        assertTrue(json.contains("\"within\""));
        assertTrue(json.contains("\"framework\": \"PLAYWRIGHT\""));

        StoredEntry back = new FileLocatorStore(file).get("k1").orElseThrow();
        assertEquals(List.of("frame=#pay", "shadow=card"), back.suggestion().within());
        assertEquals("PLAYWRIGHT", back.framework());
    }

    @Test
    void rowsWithoutWithinOrFrameworkKeepTheOldShape(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        new FileLocatorStore(file).learn("k1", LocatorSuggestion.of("css", "b"), "P.java:1", null);
        String json = Files.readString(file);
        assertFalse(json.contains("within"));
        assertFalse(json.contains("framework"));
    }

    @Test
    void oldShapeFileLoadsAsTopLevel(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        Files.writeString(file, "{\"k1\":{\"kind\":\"css\",\"value\":\"b\",\"annotation\":\"x\","
                + "\"origin\":\"P.java:1\",\"learnedAt\":\"2026-01-01T00:00:00Z\"}}");
        assertEquals(List.of(), new FileLocatorStore(file).get("k1").orElseThrow().suggestion().within());
    }

    private static final String BAD_ROW = "{\"kind\":\"css\",\"value\":\"b\",\"within\":[\"portal=#x\"],"
            + "\"origin\":\"Future.java:9\",\"learnedAt\":\"2026-01-01T00:00:00Z\",\"futureField\":[1,2]}";

    @Test
    void aBadHopRowIsSkippedWithAWarningAndKeptVerbatimOnSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        Files.writeString(file, "{\"good1\":{\"kind\":\"css\",\"value\":\"a\",\"origin\":\"P.java:1\","
                + "\"learnedAt\":\"2026-01-01T00:00:00Z\"},"
                + "\"bad\":" + BAD_ROW + ","
                + "\"good2\":{\"kind\":\"id\",\"value\":\"c\",\"within\":[\"frame=#pay\"],\"origin\":\"P.java:2\","
                + "\"learnedAt\":\"2026-01-01T00:00:00Z\"}}");

        List<LogRecord> warnings = new ArrayList<>();
        FileLocatorStore store = withWarnings(warnings, () -> new FileLocatorStore(file));
        assertEquals("a", store.get("good1").orElseThrow().suggestion().value());
        assertEquals(List.of("frame=#pay"), store.get("good2").orElseThrow().suggestion().within());
        assertTrue(store.get("bad").isEmpty(), "a bad row is not used for lookups");
        assertFalse(store.snapshot().containsKey("bad"));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).getMessage().contains("bad"), warnings.get(0).getMessage());
        assertNull(warnings.get(0).getThrown(), "no stack trace");

        store.learn("k3", LocatorSuggestion.of("css", "d"), "P.java:3", null);

        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(JsonParser.parseString(BAD_ROW), saved.get("bad"), "the bad row survives unchanged");
        assertTrue(saved.has("good1"));
        assertTrue(saved.has("good2"));
        assertTrue(saved.has("k3"));
        FileLocatorStore reloaded = withWarnings(new ArrayList<>(), () -> new FileLocatorStore(file));
        assertEquals("a", reloaded.get("good1").orElseThrow().suggestion().value());
        assertEquals("d", reloaded.get("k3").orElseThrow().suggestion().value());
    }

    @Test
    void learningTheKeyOfASkippedRowReplacesIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        Files.writeString(file, "{\"bad\":" + BAD_ROW + "}");
        FileLocatorStore store = withWarnings(new ArrayList<>(), () -> new FileLocatorStore(file));
        store.learn("bad", LocatorSuggestion.of("css", "fresh"), "P.java:4", null);

        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("fresh", saved.getAsJsonObject("bad").get("value").getAsString());
        assertFalse(saved.getAsJsonObject("bad").has("futureField"));
    }

    @Test
    void mixedRowsWithAndWithoutWithinRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learned.json");
        FileLocatorStore store = new FileLocatorStore(file);
        store.learn("top", LocatorSuggestion.of("testId", "go"), "P.java:1", "SELENIUM");
        store.learn("framed", LocatorSuggestion.of("css", "b", List.of("frame=#pay", "shadow=#card")), "P.java:2", "SELENIUM");
        store.learn("top2", LocatorSuggestion.of("name", "q"), "P.java:3", null);

        FileLocatorStore back = new FileLocatorStore(file);
        assertEquals(LocatorSuggestion.of("testId", "go"), back.get("top").orElseThrow().suggestion());
        assertEquals(LocatorSuggestion.of("css", "b", List.of("frame=#pay", "shadow=#card")),
                back.get("framed").orElseThrow().suggestion());
        assertEquals(LocatorSuggestion.of("name", "q"), back.get("top2").orElseThrow().suggestion());
        assertEquals(List.of("top", "framed", "top2"), List.copyOf(back.snapshot().keySet()));
    }

    /** Runs {@code s}, collecting the WARNING records {@link FileLocatorStore} logs. */
    private static <T> T withWarnings(List<LogRecord> records, java.util.function.Supplier<T> s) {
        Logger logger = Logger.getLogger(FileLocatorStore.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) records.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        boolean parent = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);   // expected warnings stay out of the build output
        try {
            return s.get();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parent);
        }
    }
}
