package tech.ishabbi.graft.cache;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tech.ishabbi.graft.LocatorSuggestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON for the HTTP API. The file store keeps its own shape, including {@code annotation}. */
final class CacheJson {

    private static final com.google.gson.Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CacheJson() {}

    static String entry(StoredEntry e) {
        return GSON.toJson(fields(e));
    }

    static String snapshot(Map<String, StoredEntry> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        entries.forEach((k, e) -> {
            if (e.suggestion() != null) root.put(k, fields(e));
        });
        return GSON.toJson(root);
    }

    static StoredEntry parseEntry(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return from(o, str(o, "learnedAt"));
    }

    static Map<String, StoredEntry> parseSnapshot(String json) {
        Map<String, StoredEntry> out = new LinkedHashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            StoredEntry entry = from(o, str(o, "learnedAt"));
            if (entry.suggestion() != null) out.put(e.getKey(), entry);
        }
        return out;
    }

    /** {@code null} when {@code kind} or {@code value} is missing. {@code learnedAt} is not taken from the client. */
    static LocatorSuggestion parseSuggestion(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return suggestion(o);
    }

    static String originOf(String json) {
        return str(JsonParser.parseString(json).getAsJsonObject(), "origin");
    }

    static String frameworkOf(String json) {
        return str(JsonParser.parseString(json).getAsJsonObject(), "framework");
    }

    private static Map<String, Object> fields(StoredEntry e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kind", e.suggestion().kind());
        o.put("value", e.suggestion().value());
        if (!e.suggestion().within().isEmpty()) o.put("within", e.suggestion().within());
        o.put("origin", e.origin() == null ? "" : e.origin());
        if (e.framework() != null) o.put("framework", e.framework());
        o.put("learnedAt", e.learnedAt());
        return o;
    }

    private static StoredEntry from(JsonObject o, String learnedAt) {
        return new StoredEntry(suggestion(o), str(o, "origin"), learnedAt, str(o, "framework"));
    }

    /** The optional {@code within} is a JSON string array; missing means top-level. */
    private static LocatorSuggestion suggestion(JsonObject o) {
        return LocatorSuggestion.of(str(o, "kind"), str(o, "value"), within(o));
    }

    static List<String> within(JsonObject o) {
        JsonElement e = o.get("within");
        if (e == null || e.isJsonNull()) return List.of();
        List<String> hops = new ArrayList<>();
        for (JsonElement hop : e.getAsJsonArray()) hops.add(hop.getAsString());
        return hops;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
