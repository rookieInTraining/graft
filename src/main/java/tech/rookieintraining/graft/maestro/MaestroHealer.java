package tech.rookieintraining.graft.maestro;

import tech.rookieintraining.graft.AbstractHealer;
import tech.rookieintraining.graft.ElementSpec;
import tech.rookieintraining.graft.Framework;
import tech.rookieintraining.graft.HealingConfig;
import tech.rookieintraining.graft.HealingException;
import tech.rookieintraining.graft.HealingSelector;
import tech.rookieintraining.graft.LocatorSpec;
import tech.rookieintraining.graft.LocatorSuggestion;
import tech.rookieintraining.graft.maestro.MaestroDevice.MaestroResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Healer for Maestro.
 *
 * <p>Alumnium's Maestro driver is available only through the MCP server, so healing goes:
 * Maestro CLI (declared selector) → learned selector → Alumnium MCP {@code do} with the description,
 * in a session Alumnium holds on the same device/app. Both sides drive the device through the
 * Maestro CLI, so they see the same screen — local or remote.
 *
 * <p><b>Session timing:</b> Alumnium's {@code start} launches the app. The default factory starts
 * the session eagerly, before any test flow runs, so a mid-test heal never relaunches the app.
 * Use {@link #lazy} only if you accept that risk.
 */
public final class MaestroHealer extends AbstractHealer {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[^{}]*}");

    private final MaestroDevice device;
    private final Supplier<AlumniumMcpClient> mcpFactory;
    private final boolean ownsMcp;
    private volatile AlumniumMcpClient mcp;
    private volatile String sessionId;

    private MaestroHealer(MaestroDevice device, Supplier<AlumniumMcpClient> mcpFactory, boolean ownsMcp,
                          HealingConfig config) {
        super(config);
        this.device = Objects.requireNonNull(device, "device");
        this.mcpFactory = mcpFactory;
        this.ownsMcp = ownsMcp;
    }

    // ---- factories -----------------------------------------------------------------------

    /** Starts the Alumnium Maestro session immediately (recommended). */
    public static MaestroHealer of(MaestroDevice device, HealingConfig config) {
        return lazy(device, config).startSession();
    }

    public static MaestroHealer of(MaestroDevice device) {
        return of(device, HealingConfig.defaults());
    }

    /** Uses an MCP client you manage (not closed by this healer); session still starts eagerly. */
    public static MaestroHealer of(MaestroDevice device, AlumniumMcpClient mcp, HealingConfig config) {
        return new MaestroHealer(device, () -> mcp, false, config).startSession();
    }

    /** Defers MCP startup and the Alumnium session to the first heal. */
    public static MaestroHealer lazy(MaestroDevice device, HealingConfig config) {
        return new MaestroHealer(device,
                () -> AlumniumMcpClient.start(new AlumniumMcpClient.Options().driver("maestro")),
                true, config);
    }

    /** Starts (idempotently) the Alumnium session for {@link MaestroDevice#appId()} on the device. */
    public MaestroHealer startSession() {
        if (sessionId == null) {
            synchronized (this) {
                if (sessionId == null) {
                    JsonObject caps = new JsonObject();
                    caps.addProperty("platformName", device.platform());
                    JsonObject opts = new JsonObject();
                    opts.addProperty("app", device.appId());
                    if (device.deviceId() != null) opts.addProperty("device", device.deviceId());
                    opts.addProperty("changeAnalysis", false);   // we only need the action, not a diff summary
                    caps.add("alumnium:options", opts);
                    sessionId = mcp().startSession(caps);
                    log.log(System.Logger.Level.INFO, "Alumnium Maestro session {0} started for {1}", sessionId, device.appId());
                }
            }
        }
        return this;
    }

    private AlumniumMcpClient mcp() {
        AlumniumMcpClient c = mcp;
        if (c == null) {
            synchronized (this) {
                c = mcp;
                if (c == null) mcp = c = mcpFactory.get();
            }
        }
        return c;
    }

    // ---- Healer --------------------------------------------------------------------------

    @Override
    public Framework framework() { return Framework.MAESTRO; }

    public MaestroDevice device() { return device; }

    @Override
    public Object createElement(ElementSpec spec, Class<?> fieldType) {
        if (fieldType == MaestroElement.class) {
            return new MaestroElement(this, spec, MaestroSelector.from(spec));   // validates the kind early
        }
        throw new IllegalArgumentException("@Element on " + spec.displayName()
                + ": unsupported field type " + fieldType.getName() + " for Maestro (expected MaestroElement)");
    }

    /** A self-healing element for a {@link HealingSelector} constant (primary: {@link MaestroSelector} or none). */
    public MaestroElement element(HealingSelector selector) {
        return new MaestroElement(this, selector, MaestroSelector.from(selector));
    }

    @Override
    public void invalidate(LocatorSpec spec) { /* Maestro resolves per command; nothing cached */ }

    @Override
    public void invalidateAll() { /* nothing cached */ }

    @Override
    public void close() {
        AlumniumMcpClient c = mcp;
        if (c != null) {
            if (sessionId != null) {
                c.stopSession(sessionId);
                sessionId = null;
            }
            if (ownsMcp) c.close();
            mcp = null;
        }
    }

    // ---- actions -------------------------------------------------------------------------

    /**
     * Runs {@code command} against the selector (plus an optional follow-up command); on
     * element-not-found, tries a learned selector, then asks Alumnium to perform
     * {@code healInstruction} instead.
     */
    void perform(LocatorSpec spec, MaestroSelector selector, String command, String followUpYaml, String healInstruction) {
        if (selector == null) {
            startSession();
            mcp().doAction(sessionId, healInstruction);   // description-only: Alumnium is the executor
            return;
        }

        MaestroResult result = device.runFlow(flow(selector, command, followUpYaml));
        if (result.success()) return;

        MaestroFailure failure = new MaestroFailure(spec, command, selector, result);
        if (!result.elementNotFound()) {
            throw failure;   // real failure (crash, timeout, assertion) — not a locator problem
        }

        startSession();
        String summary = heal(spec, selector.toString(), failure,
                learned -> runLearned(learned, command, followUpYaml),
                () -> mcp().doAction(sessionId, healInstruction),
                s -> abbreviate(s),
                s -> suggestSelector(spec));
        log.log(System.Logger.Level.DEBUG, "Alumnium performed \"{0}\": {1}", healInstruction, summary);
    }

    void assertVisible(LocatorSpec spec, MaestroSelector selector) {
        String statement = "the " + spec.description() + " is visible on screen";
        if (selector == null) {
            startSession();
            mcp().check(sessionId, statement);
            return;
        }

        MaestroResult result = device.run(selector.command("assertVisible"));
        if (result.success()) return;

        MaestroFailure failure = new MaestroFailure(spec, "assertVisible", selector, result);
        startSession();
        try {
            heal(spec, selector.toString(), failure,
                    learned -> runLearned(learned, "assertVisible", null),
                    () -> mcp().check(sessionId, statement),
                    s -> "visible (per Alumnium check)",
                    s -> suggestSelector(spec));
        } catch (HealingException e) {
            for (Throwable suppressed : e.getSuppressed()) {
                if (suppressed instanceof AlumniumMcpClient.McpToolException) {
                    failure.addSuppressed(suppressed);
                    throw failure;               // Alumnium agrees it is not there: a real assertion failure
                }
            }
            throw e;                             // strict mode, budget exhausted, MCP outage, ...
        }
    }

    private static List<String> flow(MaestroSelector selector, String command, String followUpYaml) {
        List<String> flow = new ArrayList<>();
        flow.add(selector.command(command));
        if (followUpYaml != null) flow.add(followUpYaml);
        return flow;
    }

    /** Learned tier: replay the remembered id/text through Maestro itself; {@code null} on miss. */
    private String runLearned(LocatorSuggestion learned, String command, String followUpYaml) {
        MaestroSelector sel = MaestroSelector.fromSuggestion(learned);
        if (sel == null) return null;
        MaestroResult r = device.runFlow(flow(sel, command, followUpYaml));
        return r.success() ? "learned selector " + sel + " matched" : null;
    }

    /**
     * Asks Alumnium for the attributes of the element it just used and turns the answer into a
     * structured suggestion (id preferred, then text) so it can be reported and learned.
     */
    private LocatorSuggestion suggestSelector(LocatorSpec spec) {
        try {
            String answer = mcp().get(sessionId,
                    "For the " + spec.description() + ": its accessibility id (resource-id on Android, "
                            + "accessibility identifier on iOS) and its exact visible text. "
                            + "Answer only with JSON like {\"id\": \"...\", \"text\": \"...\"}; use null when unknown.");
            return parseSuggestion(answer);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static LocatorSuggestion parseSuggestion(String answer) {
        if (answer == null) return null;
        Matcher m = JSON_BLOCK.matcher(answer);
        if (!m.find()) return null;
        try {
            JsonObject o = JsonParser.parseString(m.group()).getAsJsonObject();
            String id = str(o, "id");
            if (id != null) return LocatorSuggestion.of("id", id);
            String text = str(o, "text");
            if (text != null) return LocatorSuggestion.of("text", text);
        } catch (RuntimeException ignored) {
            // free-text answer; nothing to learn
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String v = e.getAsString().trim();
        return v.isEmpty() || v.equalsIgnoreCase("null") || v.equalsIgnoreCase("unknown") ? null : v;
    }

    private static String abbreviate(String s) {
        if (s == null) return null;
        String one = s.strip().replace('\n', ' ');
        return one.length() > 200 ? one.substring(0, 197) + "..." : one;
    }

    /** A Maestro command that did not succeed. */
    public static final class MaestroFailure extends RuntimeException {
        private final transient MaestroResult result;

        MaestroFailure(LocatorSpec spec, String command, MaestroSelector selector, MaestroResult result) {
            super("Maestro " + command + " (" + selector + ") failed for " + spec.displayName() + ": "
                    + result.failureSummary());
            this.result = result;
        }

        public MaestroResult result() { return result; }
    }
}
