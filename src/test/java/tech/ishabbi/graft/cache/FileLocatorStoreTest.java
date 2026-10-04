package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.ishabbi.graft.cache.LearnedLocatorStore.ForgetResult.DELETED;

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
}
