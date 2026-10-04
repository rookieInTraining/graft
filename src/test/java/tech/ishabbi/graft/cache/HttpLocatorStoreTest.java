package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLocatorStoreTest {

    private CacheServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void oneClientLearnsAndAnotherReads() {
        server = CacheServer.start(0, new MemoryLocatorStore(), "secret", () -> true);
        HttpLocatorStore writer = client();
        HttpLocatorStore reader = client();

        writer.learn("by:By.id: login-old|the 'Sign in' button",
                LocatorSuggestion.of("testId", "signin-btn"), "HealingTest.java:20", "SELENIUM");

        StoredEntry entry = reader.get("by:By.id: login-old|the 'Sign in' button").orElseThrow();
        assertEquals("testId", entry.suggestion().kind());
        assertEquals("signin-btn", entry.suggestion().value());
        assertEquals("SELENIUM", entry.framework());
        assertEquals(1, reader.snapshot().size());
    }

    @Test
    void withinSurvivesTheRoundTrip() {
        server = CacheServer.start(0, new MemoryLocatorStore(), "secret", () -> true);
        client().learn("k", LocatorSuggestion.of("css", "button", java.util.List.of("frame=#pay", "shadow=card")),
                "Pay.java:3", "PLAYWRIGHT");

        StoredEntry entry = client().get("k").orElseThrow();
        assertEquals(java.util.List.of("frame=#pay", "shadow=card"), entry.suggestion().within());
        assertEquals(java.util.List.of("frame=#pay", "shadow=card"),
                client().snapshot().get("k").suggestion().within());
    }

    @Test
    void refusedConnectionIsAnEmptyGet() {
        HttpLocatorStore store = new HttpLocatorStore(
                URI.create("http://127.0.0.1:1"), "default", null, Duration.ofMillis(200));
        assertTrue(store.get("missing").isEmpty());
    }

    private HttpLocatorStore client() {
        return new HttpLocatorStore(URI.create(server.baseUrl()), "default", "secret", Duration.ofSeconds(2));
    }
}
