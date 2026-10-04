package tech.rookieintraining.graft.maestro;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to {@code alumnium mcp} over stdio. Alumnium's Maestro driver is only exposed through
 * the MCP server (the Java client library wraps Appium/Selenium/Playwright drivers, not
 * Maestro), so this is the bridge the Maestro healer uses.
 *
 * <p>Only what healing needs is implemented: {@code initialize}, {@code tools/list},
 * {@code tools/call}. Tool argument names are discovered from each tool's input schema at
 * startup rather than hard-coded, so renames on the server side don't break the client.
 */
public final class AlumniumMcpClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AlumniumMcpClient.class.getName());
    private static final Gson GSON = new Gson();
    private static final Pattern SESSION_PARAM = Pattern.compile("(?i)^(session_?id|sessionid|session|id)$");

    /** Launch options. */
    public static final class Options {
        private String binary = envOr("ALUMNIUM_BINARY", "alumnium");
        private String driver = "maestro";
        private String mode = "agentic";
        private Duration callTimeout = Duration.ofSeconds(180);
        private final Map<String, String> env = new HashMap<>();

        public Options binary(String path) { this.binary = Objects.requireNonNull(path); return this; }
        public Options driver(String driver) { this.driver = Objects.requireNonNull(driver); return this; }
        public Options mode(String mode) { this.mode = Objects.requireNonNull(mode); return this; }
        public Options callTimeout(Duration d) { this.callTimeout = Objects.requireNonNull(d); return this; }
        public Options env(String key, String value) { this.env.put(key, value); return this; }
    }

    /** Argument names a tool takes, discovered from its JSON schema. */
    record ToolSignature(String name, String sessionParam, String primaryParam) {}

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread stdoutReader;
    private final Thread stderrReader;
    private final Duration callTimeout;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, ToolSignature> tools = new LinkedHashMap<>();

    private AlumniumMcpClient(Process process, Duration callTimeout) {
        this.process = process;
        this.callTimeout = callTimeout;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdoutReader = new Thread(this::readStdout, "alumnium-mcp-stdout");
        this.stdoutReader.setDaemon(true);
        this.stderrReader = new Thread(this::readStderr, "alumnium-mcp-stderr");
        this.stderrReader.setDaemon(true);
    }

    public static AlumniumMcpClient start(Options options) {
        ProcessBuilder pb = new ProcessBuilder(options.binary, "mcp", "--mode", options.mode);
        pb.environment().put("ALUMNIUM_DRIVER", options.driver);
        pb.environment().putAll(options.env);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start '" + options.binary + " mcp'. Install the Alumnium binary "
                    + "(https://alumnium.ai/docs/mcp/overview/) or set ALUMNIUM_BINARY.", e);
        }
        AlumniumMcpClient client = new AlumniumMcpClient(p, options.callTimeout);
        client.stdoutReader.start();
        client.stderrReader.start();
        client.initialize();
        return client;
    }

    // ---- high-level tool calls -----------------------------------------------------------

    /** Calls {@code start} with the given capabilities and returns the session id. */
    public String startSession(JsonObject capabilities) {
        ToolSignature sig = signature("start");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(sig.primaryParam(), capabilities.toString());
        String text = callTool("start", args);
        return extractSessionId(text);
    }

    public void stopSession(String sessionId) {
        ToolSignature sig = signature("stop");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(sig.sessionParam(), sessionId);
        try {
            callTool("stop", args);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "stop failed: " + e.getMessage());
        }
    }

    /** {@code do}: perform an action described in natural language. Returns Alumnium's summary. */
    public String doAction(String sessionId, String instruction) {
        return callSessionTool("do", sessionId, instruction);
    }

    /** {@code get}: retrieve data described in natural language. */
    public String get(String sessionId, String question) {
        return callSessionTool("get", sessionId, question);
    }

    /** {@code check}: verify a statement; throws {@link McpToolException} when it does not hold. */
    public String check(String sessionId, String statement) {
        return callSessionTool("check", sessionId, statement);
    }

    private String callSessionTool(String tool, String sessionId, String primary) {
        ToolSignature sig = signature(tool);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(sig.sessionParam(), sessionId);
        args.put(sig.primaryParam(), primary);
        return callTool(tool, args);
    }

    /** Names and discovered argument names of the server's tools (useful when debugging a new server version). */
    public Map<String, String> describeTools() {
        Map<String, String> out = new LinkedHashMap<>();
        tools.forEach((n, s) -> out.put(n, "session=" + s.sessionParam() + ", primary=" + s.primaryParam()));
        return out;
    }

    // ---- JSON-RPC plumbing ---------------------------------------------------------------

    private void initialize() {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2025-06-18");
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "graft");
        client.addProperty("version", "0.1.0");
        params.add("clientInfo", client);
        request("initialize", params);
        notify("notifications/initialized", null);

        JsonObject listed = request("tools/list", new JsonObject());
        JsonArray arr = listed.getAsJsonArray("tools");
        for (JsonElement el : arr) {
            JsonObject tool = el.getAsJsonObject();
            String name = tool.get("name").getAsString();
            tools.put(name, signatureOf(name, tool.getAsJsonObject("inputSchema")));
        }
        LOG.log(System.Logger.Level.DEBUG, "Alumnium MCP tools: {0}", describeTools());
    }

    private static ToolSignature signatureOf(String name, JsonObject schema) {
        String session = null;
        String primary = null;
        if (schema != null && schema.has("properties")) {
            JsonObject props = schema.getAsJsonObject("properties");
            List<String> required = new java.util.ArrayList<>();
            if (schema.has("required")) {
                for (JsonElement r : schema.getAsJsonArray("required")) required.add(r.getAsString());
            }
            for (String key : props.keySet()) {
                if (session == null && SESSION_PARAM.matcher(key).matches()) session = key;
            }
            for (String key : required) {
                if (!key.equals(session)) { primary = key; break; }
            }
            if (primary == null) {
                for (String key : props.keySet()) {
                    if (!key.equals(session)) { primary = key; break; }
                }
            }
        }
        return new ToolSignature(name, session, primary);
    }

    private ToolSignature signature(String tool) {
        ToolSignature sig = tools.get(tool);
        if (sig == null) {
            throw new IllegalStateException("Alumnium MCP server exposes no '" + tool + "' tool; available: " + tools.keySet());
        }
        return sig;
    }

    /** Calls a tool and returns its concatenated text content; throws on {@code isError}. */
    public String callTool(String name, Map<String, Object> arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", GSON.toJsonTree(arguments));
        JsonObject result = request("tools/call", params);
        StringBuilder text = new StringBuilder();
        if (result.has("content")) {
            for (JsonElement c : result.getAsJsonArray("content")) {
                JsonObject block = c.getAsJsonObject();
                if ("text".equals(optString(block, "type"))) {
                    if (text.length() > 0) text.append('\n');
                    text.append(optString(block, "text"));
                }
            }
        }
        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            throw new McpToolException(name, text.toString());
        }
        return text.toString();
    }

    private JsonObject request(String method, JsonObject params) {
        long id = ids.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        if (params != null) msg.add("params", params);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        send(msg);
        try {
            JsonObject response = future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                JsonObject err = response.getAsJsonObject("error");
                throw new McpToolException(method, optString(err, "message"));
            }
            return response.has("result") ? response.getAsJsonObject("result") : new JsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for MCP " + method, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("MCP " + method + " failed", e.getCause());
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IllegalStateException("MCP " + method + " timed out after " + callTimeout, e);
        }
    }

    private void notify(String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        if (params != null) msg.add("params", params);
        send(msg);
    }

    private synchronized void send(JsonObject msg) {
        try {
            stdin.write(GSON.toJson(msg));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Alumnium MCP process is not accepting input", e);
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException notJson) {
                    LOG.log(System.Logger.Level.DEBUG, "mcp stdout: {0}", line);
                    continue;
                }
                if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
                    CompletableFuture<JsonObject> f = pending.remove(msg.get("id").getAsLong());
                    if (f != null) f.complete(msg);
                } else if ("ping".equals(optString(msg, "method")) && msg.has("id")) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("jsonrpc", "2.0");
                    pong.add("id", msg.get("id"));
                    pong.add("result", new JsonObject());
                    send(pong);
                } else {
                    LOG.log(System.Logger.Level.DEBUG, "mcp notification: {0}", line);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "mcp stdout closed: " + e.getMessage());
        } finally {
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Alumnium MCP process exited")));
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.log(System.Logger.Level.DEBUG, "mcp stderr: {0}", line);
            }
        } catch (IOException ignored) {
            // process gone
        }
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String extractSessionId(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                JsonObject o = parsed.getAsJsonObject();
                for (String key : new String[] {"id", "session_id", "sessionId", "session"}) {
                    if (o.has(key)) return o.get(key).getAsString();
                }
            } else if (parsed.isJsonPrimitive()) {
                return parsed.getAsString();
            }
        } catch (RuntimeException notJson) {
            // fall through to the regexes below
        }
        Matcher m = Pattern.compile("(?i)(?:session\\s*id|id)\\s*[:=]\\s*\"?([A-Za-z0-9._-]+)").matcher(text);
        if (m.find()) return m.group(1);
        Matcher uuid = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(text);
        if (uuid.find()) return uuid.group();
        throw new IllegalStateException("Could not find a session id in Alumnium's start response: " + text);
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** A tool call the server reported as failed (e.g. a {@code check} that is false). */
    public static final class McpToolException extends RuntimeException {
        public McpToolException(String tool, String message) {
            super("Alumnium MCP '" + tool + "': " + message);
        }
    }
}
