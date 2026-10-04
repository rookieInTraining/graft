package tech.ishabbi.graft.cache;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tech.ishabbi.graft.LocatorSuggestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The local JSON file ({@code .graft/learned-locators.json} by default).
 * {@link #forget} always deletes; a single file has no cross-process compare-and-delete.
 */
public final class FileLocatorStore implements LearnedLocatorStore {

    private static final System.Logger LOG = System.getLogger(FileLocatorStore.class.getName());

    private final Path path;
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    public FileLocatorStore(Path path) {
        this.path = path;
        load();
    }

    @Override
    public synchronized Optional<StoredEntry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public synchronized void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {
        if (suggestion == null) return;
        entries.put(key, new StoredEntry(suggestion, origin, Instant.now().toString(), framework));
        save();
    }

    @Override
    public synchronized ForgetResult forget(String key, String ifLearnedAt) {
        if (entries.remove(key) == null) return ForgetResult.ABSENT;
        save();
        return ForgetResult.DELETED;
    }

    @Override
    public synchronized Map<String, StoredEntry> snapshot() {
        return new LinkedHashMap<>(entries);
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                LocatorSuggestion s = LocatorSuggestion.of(str(o, "kind"), str(o, "value"), CacheJson.within(o));
                if (s != null) entries.put(e.getKey(), new StoredEntry(s, str(o, "origin"), str(o, "learnedAt"), str(o, "framework")));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable learned-locator store " + path + ": " + e.getMessage());
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        entries.forEach((k, e) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("kind", e.suggestion().kind());
            o.put("value", e.suggestion().value());
            if (!e.suggestion().within().isEmpty()) o.put("within", e.suggestion().within());
            o.put("annotation", e.suggestion().toAnnotation());
            o.put("origin", e.origin());
            if (e.framework() != null) o.put("framework", e.framework());
            o.put("learnedAt", e.learnedAt());
            root.put(k, o);
        });
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not write learned-locator store " + path + ": " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
