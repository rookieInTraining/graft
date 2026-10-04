package tech.rookieintraining.graft.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheServerTest {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private CacheServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void putGetSnapshotAndConditionalDelete() throws Exception {
        server = CacheServer.start(0, new MemoryLocatorStore(), null, () -> true);
        String key = "by:By.id: login-old|the 'Sign in' button";
        URI entry = entry(server, "default", key);

        HttpResponse<String> created = send("PUT", entry, putBody(), null);
        assertEquals(200, created.statusCode());
        assertTrue(created.body().contains("\"kind\":\"testId\"") || created.body().contains("\"kind\": \"testId\""));
        assertTrue(created.body().contains("learnedAt"));

        HttpResponse<String> fetched = send("GET", entry, null, null);
        assertEquals(200, fetched.statusCode());
        String learnedAt = learnedAt(fetched.body());

        HttpResponse<String> kept = send("DELETE", withCutoff(entry, "2020-01-01T00:00:00Z"), null, null);
        assertEquals(409, kept.statusCode());
        assertEquals(200, send("GET", entry, null, null).statusCode());

        HttpResponse<String> removed = send("DELETE", withCutoff(entry, learnedAt), null, null);
        assertEquals(204, removed.statusCode());
        assertEquals(404, send("GET", entry, null, null).statusCode());

        send("PUT", entry, putBody(), null);
        HttpResponse<String> newerCutoff = send("DELETE", withCutoff(entry, "2099-01-01T00:00:00Z"), null, null);
        assertEquals(204, newerCutoff.statusCode());

        send("PUT", entry, putBody(), null);
        HttpResponse<String> all = send("GET", URI.create(server.baseUrl() + "/v1/namespaces/default/entries"), null, null);
        assertEquals(200, all.statusCode());
        assertTrue(all.body().contains("signin-btn"));
    }

    @Test
    void keepsWithinThroughPutThenGet() throws Exception {
        server = CacheServer.start(0, new MemoryLocatorStore(), null, () -> true);
        URI entry = entry(server, "default", "k");
        String body = "{\"kind\":\"css\",\"value\":\"button\",\"within\":[\"frame=#pay\",\"shadow=card\"]}";

        HttpResponse<String> put = send("PUT", entry, body, null);
        assertEquals(200, put.statusCode());
        assertTrue(put.body().contains("\"within\":[\"frame=#pay\",\"shadow=card\"]"), put.body());
        assertTrue(send("GET", entry, null, null).body().contains("\"within\":[\"frame=#pay\",\"shadow=card\"]"));

        HttpResponse<String> bad = send("PUT", entry, "{\"kind\":\"css\",\"value\":\"b\",\"within\":[\"nope\"]}", null);
        assertEquals(400, bad.statusCode());
    }

    @Test
    void rejectsBadNamespaceMissingCutoffAndMissingToken() throws Exception {
        server = CacheServer.start(0, new MemoryLocatorStore(), "secret", () -> true);
        URI badNs = URI.create(server.baseUrl() + "/v1/namespaces/has%20space/entries");
        assertEquals(400, send("GET", badNs, null, "secret").statusCode());

        URI entry = entry(server, "default", "k");
        assertEquals(401, send("PUT", entry, putBody(), null).statusCode());
        assertEquals(200, send("PUT", entry, putBody(), "secret").statusCode());
        assertEquals(400, send("DELETE", entry, null, "secret").statusCode());
        assertEquals(400, send("DELETE", withCutoff(entry, "not-a-timestamp"), null, "secret").statusCode());
    }

    @Test
    void healthReflectsTheProbeAndDoesNotRequireAToken() throws Exception {
        server = CacheServer.start(0, new MemoryLocatorStore(), "secret", () -> false);
        HttpResponse<String> health = send("GET", URI.create(server.baseUrl() + "/health"), null, null);
        assertEquals(503, health.statusCode());
    }

    private static URI entry(CacheServer server, String namespace, String key) {
        return URI.create(server.baseUrl() + "/v1/namespaces/" + namespace + "/entries/"
                + CachePaths.encode(key));
    }

    private static URI withCutoff(URI entry, String learnedAt) {
        return URI.create(entry + "?ifLearnedAt=" + CachePaths.encode(learnedAt));
    }

    private static String putBody() {
        return "{\"kind\":\"testId\",\"value\":\"signin-btn\",\"origin\":\"LoginPage.java:12\",\"framework\":\"SELENIUM\"}";
    }

    private static String learnedAt(String json) {
        int i = json.indexOf("\"learnedAt\"");
        int colon = json.indexOf(':', i);
        int start = json.indexOf('"', colon) + 1;
        return json.substring(start, json.indexOf('"', start));
    }

    private HttpResponse<String> send(String method, URI uri, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else if ("DELETE".equals(method)) {
            b.DELETE();
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
