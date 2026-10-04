package tech.ishabbi.graft.cache;

import com.google.gson.GsonBuilder;
import tech.ishabbi.graft.LocatorSuggestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client of {@link CacheServer}. A refused connection or a 5xx is a miss: healing continues
 * with Alumnium, and the test is not failed because the cache is down.
 */
public final class HttpLocatorStore implements LearnedLocatorStore {

    private static final System.Logger LOG = System.getLogger(HttpLocatorStore.class.getName());

    private final HttpClient client;
    private final String base;
    private final String namespace;
    private final String token;
    private final Duration timeout;

    public HttpLocatorStore(URI base, String namespace, String token, Duration timeout) {
        if (!CachePaths.namespaceOk(namespace)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        String text = base.toString();
        if (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        this.base = text;
        this.namespace = namespace;
        this.token = token == null || token.isBlank() ? null : token;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<StoredEntry> get(String key) {
        try {
            HttpResult result = send("GET", entry(key), null);
            if (result.status == 404) return Optional.empty();
            if (result.status != 200) {
                warn("get", key, result.status, null);
                return Optional.empty();
            }
            StoredEntry entry = CacheJson.parseEntry(result.body);
            return entry.suggestion() == null ? Optional.empty() : Optional.of(entry);
        } catch (Exception e) {
            warn("get", key, 0, e);
            return Optional.empty();
        }
    }

    @Override
    public void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {
        if (suggestion == null) return;
        Map<String, String> body = new LinkedHashMap<>();
        body.put("kind", suggestion.kind());
        body.put("value", suggestion.value());
        body.put("origin", origin == null ? "" : origin);
        if (framework != null) body.put("framework", framework);
        try {
            HttpResult result = send("PUT", entry(key), new GsonBuilder().disableHtmlEscaping().create().toJson(body));
            if (result.status != 200) warn("learn", key, result.status, null);
        } catch (Exception e) {
            warn("learn", key, 0, e);
        }
    }

    @Override
    public ForgetResult forget(String key, String ifLearnedAt) {
        try {
            String cutoff = ifLearnedAt == null ? "" : ifLearnedAt;
            HttpResult result = send("DELETE", URI.create(entry(key) + "?ifLearnedAt=" + CachePaths.encode(cutoff)), null);
            if (result.status == 409) return ForgetResult.KEPT;
            if (result.status == 204) return ForgetResult.DELETED;
            warn("forget", key, result.status, null);
            return ForgetResult.ABSENT;
        } catch (Exception e) {
            warn("forget", key, 0, e);
            return ForgetResult.ABSENT;
        }
    }

    @Override
    public Map<String, StoredEntry> snapshot() {
        try {
            HttpResult result = send("GET", collection(), null);
            if (result.status != 200) {
                warn("snapshot", namespace, result.status, null);
                return Map.of();
            }
            return CacheJson.parseSnapshot(result.body);
        } catch (Exception e) {
            warn("snapshot", namespace, 0, e);
            return Map.of();
        }
    }

    private URI entry(String key) {
        return URI.create(collection() + "/" + CachePaths.encode(key));
    }

    private URI collection() {
        return URI.create(base + "/v1/namespaces/" + namespace + "/entries");
    }

    private HttpResult send(String method, URI uri, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else if ("DELETE".equals(method)) {
            request.DELETE();
        } else {
            request.GET();
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static void warn(String op, String key, int status, Exception e) {
        String detail = e == null ? "HTTP " + status : e.getClass().getSimpleName() + ": " + e.getMessage();
        LOG.log(System.Logger.Level.WARNING, "Learned-locator cache " + op + " failed for " + key + " (" + detail + ")");
    }

    private record HttpResult(int status, String body) {}
}
