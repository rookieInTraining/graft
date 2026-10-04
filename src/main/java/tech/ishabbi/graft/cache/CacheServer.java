package tech.ishabbi.graft.cache;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tech.ishabbi.graft.LocatorSuggestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * HTTP front for a {@link LearnedLocatorStore}. The test JVM never talks to Redis;
 * it talks to this process. Run {@link #main} with {@code REDIS_URL} set.
 */
public final class CacheServer implements AutoCloseable {

    private final HttpServer http;
    private final ExecutorService executor;
    private final int port;

    private CacheServer(HttpServer http, ExecutorService executor, int port) {
        this.http = http;
        this.executor = executor;
        this.port = port;
    }

    public static CacheServer start(int port, LearnedLocatorStore store, String token, BooleanSupplier healthy) {
        return start(port, namespace -> store, token, healthy);
    }

    public static CacheServer start(int port, Function<String, LearnedLocatorStore> stores, String token, BooleanSupplier healthy) {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "graft-cache");
                t.setDaemon(true);
                return t;
            });
            http.setExecutor(executor);
            int bound = http.getAddress().getPort();
            CacheServer server = new CacheServer(http, executor, bound);
            String expected = token == null || token.isBlank() ? null : token;
            http.createContext("/", exchange -> server.handle(exchange, stores, expected, healthy));
            http.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public int port() { return port; }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
    }

    /**
     * Binds {@code GRAFT_CACHE_PORT} (default 8741) and blocks until the process is stopped.
     * Jedis is loaded reflectively so this class can be mentioned without {@code jedis.jar} present;
     * {@code main} exits with a message naming {@code redis.clients:jedis:5.2.0} when it is missing.
     */
    public static void main(String[] args) throws InterruptedException {
        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isBlank()) {
            System.err.println("REDIS_URL is required, for example redis://127.0.0.1:6379");
            System.exit(1);
        }
        int port = (int) envLong("GRAFT_CACHE_PORT", 8741);
        RemoteCache cache = openRedis(redisUrl.trim());
        CacheServer server = start(port, cache::store, System.getenv("GRAFT_CACHE_TOKEN"), cache::ping);
        System.err.println("graft cache listening on " + server.baseUrl());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                cache.close();
            } catch (Exception ignored) {
                // the process is exiting
            }
            stopped.countDown();
        }, "graft-cache-shutdown"));
        stopped.await();
    }

    private static RemoteCache openRedis(String redisUrl) {
        try {
            Class<?> type = Class.forName("tech.ishabbi.graft.cache.RedisLocatorStore");
            return (RemoteCache) type.getMethod("open", String.class).invoke(null, redisUrl);
        } catch (Throwable e) {
            if (missingJedis(e)) {
                System.err.println("Jedis is required to run the cache server. Add redis.clients:jedis:5.2.0 to the classpath.");
                System.exit(1);
            }
            e.printStackTrace(System.err);
            System.exit(1);
            throw new IllegalStateException(e);
        }
    }

    private static boolean missingJedis(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError) {
                String msg = c.getMessage();
                if (msg != null && msg.contains("jedis")) return true;
                if (c instanceof NoClassDefFoundError && msg != null && msg.contains("redis/clients")) return true;
            }
        }
        return false;
    }

    private static long envLong(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void handle(HttpExchange exchange, Function<String, LearnedLocatorStore> stores, String token, BooleanSupplier healthy) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            List<String> segments = segments(exchange.getRequestURI().getRawPath());
            if (segments.size() == 1 && "health".equals(segments.get(0))) {
                boolean ok = false;
                try {
                    ok = healthy.getAsBoolean();
                } catch (RuntimeException ignored) {
                    ok = false;
                }
                respond(exchange, ok ? 200 : 503, null);
                return;
            }
            if (segments.size() < 4 || !"v1".equals(segments.get(0)) || !"namespaces".equals(segments.get(1))
                    || !"entries".equals(segments.get(3))) {
                respond(exchange, 404, null);
                return;
            }
            if (!authorized(exchange, token)) {
                respond(exchange, 401, null);
                return;
            }
            String namespace = CachePaths.decode(segments.get(2));
            if (!CachePaths.namespaceOk(namespace)) {
                respond(exchange, 400, error("invalid namespace"));
                return;
            }
            LearnedLocatorStore store = stores.apply(namespace);
            if (segments.size() == 4) {
                if (!"GET".equals(method)) {
                    respond(exchange, 405, null);
                    return;
                }
                respond(exchange, 200, CacheJson.snapshot(store.snapshot()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (segments.size() != 5) {
                respond(exchange, 404, null);
                return;
            }
            String key = CachePaths.decode(segments.get(4));
            switch (method) {
                case "GET" -> {
                    var found = store.get(key);
                    if (found.isEmpty() || found.get().suggestion() == null) respond(exchange, 404, null);
                    else respond(exchange, 200, CacheJson.entry(found.get()).getBytes(StandardCharsets.UTF_8));
                }
                case "PUT" -> put(exchange, store, key);
                case "DELETE" -> delete(exchange, store, key);
                default -> respond(exchange, 405, null);
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, error(e.getMessage() == null ? "error" : e.getMessage()));
        }
    }

    private void put(HttpExchange exchange, LearnedLocatorStore store, String key) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LocatorSuggestion suggestion;
        String origin;
        String framework;
        try {
            suggestion = CacheJson.parseSuggestion(body);
            origin = CacheJson.originOf(body);
            framework = CacheJson.frameworkOf(body);
        } catch (RuntimeException e) {
            respond(exchange, 400, error("invalid json"));
            return;
        }
        if (suggestion == null) {
            respond(exchange, 400, error("kind and value are required"));
            return;
        }
        store.learn(key, suggestion, origin == null ? "" : origin, framework);
        var saved = store.get(key);
        if (saved.isEmpty()) {
            respond(exchange, 500, error("store did not retain the entry"));
            return;
        }
        respond(exchange, 200, CacheJson.entry(saved.get()).getBytes(StandardCharsets.UTF_8));
    }

    private void delete(HttpExchange exchange, LearnedLocatorStore store, String key) throws IOException {
        String cutoff = queryParam(exchange.getRequestURI().getRawQuery(), "ifLearnedAt");
        if (cutoff == null || cutoff.isBlank()) {
            respond(exchange, 400, error("ifLearnedAt is required"));
            return;
        }
        try {
            Instant.parse(cutoff);
        } catch (DateTimeParseException e) {
            respond(exchange, 400, error("ifLearnedAt is not an instant"));
            return;
        }
        LearnedLocatorStore.ForgetResult result = store.forget(key, cutoff);
        respond(exchange, result == LearnedLocatorStore.ForgetResult.KEPT ? 409 : 204, null);
    }

    private static boolean authorized(HttpExchange exchange, String token) {
        if (token == null) return true;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented);
    }

    private static List<String> segments(String rawPath) {
        List<String> out = new ArrayList<>();
        if (rawPath == null) return out;
        for (String part : rawPath.split("/")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String k = CachePaths.decode(eq < 0 ? part : part.substring(0, eq));
            if (name.equals(k)) return eq < 0 ? "" : CachePaths.decode(part.substring(eq + 1));
        }
        return null;
    }

    private static byte[] error(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
