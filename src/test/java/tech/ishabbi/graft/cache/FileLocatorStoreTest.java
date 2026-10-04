package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
