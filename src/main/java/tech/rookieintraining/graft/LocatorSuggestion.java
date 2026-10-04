package tech.rookieintraining.graft;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A replacement locator derived from the element Alumnium found. Kept structured so it can be
 * rendered as an {@code @Element} attribute for the report <em>and</em> turned back into a
 * framework locator for the learned-locator tier.
 *
 * <p>{@code kind} is one of the {@code @Element} attribute names: {@code id, css, xpath, name,
 * text, testId, accessibilityId}.
 *
 * <p>{@code within} is the outside-in context chain ({@link Within}) the locator is resolved in,
 * e.g. {@code ["frame=#pay", "shadow=card-form"]}. Empty means the top-level document.
 */
public record LocatorSuggestion(String kind, String value, List<String> within) {

    public LocatorSuggestion {
        within = within == null ? List.of() : List.copyOf(within);
        within.forEach(Within::parse);
    }

    public LocatorSuggestion(String kind, String value) {
        this(kind, value, List.of());
    }

    public static LocatorSuggestion of(String kind, String value) {
        return of(kind, value, List.of());
    }

    public static LocatorSuggestion of(String kind, String value, List<String> within) {
        if (kind == null || value == null || value.isBlank()) return null;
        return new LocatorSuggestion(kind, value, within);
    }

    /** e.g. {@code @Element(testId = "login-submit")}, or with hops {@code @Element(within = {"frame=#x"}, css = "b")}. */
    public String toAnnotation() {
        String hops = within.isEmpty() ? "" : within.stream()
                .map(h -> "\"" + escape(h) + "\"")
                .collect(Collectors.joining(", ", "within = {", "}, "));
        return "@Element(" + hops + kind + " = \"" + escape(value) + "\")";
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return within.isEmpty() ? kind + "=" + value : String.join(" > ", within) + " > " + kind + "=" + value;
    }
}
