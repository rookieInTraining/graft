package tech.ishabbi.graft;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One successful heal: which locator, why it failed, what Alumnium resolved it to, and a
 * locator the engineer can paste back.
 */
public record HealEvent(
        Framework framework,
        String elementKey,
        String origin,
        String description,
        String originalLocator,
        String failureReason,
        String healedTo,
        LocatorSuggestion suggestion,
        Duration duration,
        Instant at) {

    /** Annotation-form suggestion, or {@code null}. */
    public String suggestedLocator() {
        return suggestion == null ? null : suggestion.toAnnotation();
    }

    /** Flat, JSON-friendly form (no java.time types, so any serializer handles it). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("framework", framework.name());
        m.put("element", elementKey);
        m.put("origin", origin);
        m.put("description", description);
        m.put("originalLocator", originalLocator);
        m.put("failureReason", failureReason);
        m.put("healedTo", healedTo);
        m.put("suggestedLocator", suggestedLocator());
        m.put("suggestionKind", suggestion == null ? null : suggestion.kind());
        m.put("suggestionValue", suggestion == null ? null : suggestion.value());
        if (suggestion != null && !suggestion.within().isEmpty()) m.put("suggestionWithin", suggestion.within());
        m.put("durationMs", duration.toMillis());
        m.put("at", at.toString());
        return m;
    }

    public String summary() {
        return "[heal] " + origin + " (\"" + description + "\"): "
                + originalLocator + " failed (" + failureReason + ") -> " + healedTo
                + (suggestion == null ? "" : "; suggested: " + suggestion.toAnnotation())
                + " [" + duration.toMillis() + " ms]";
    }
}
