package tech.ishabbi.graft;

/**
 * A replacement locator derived from the element Alumnium found. Kept structured so it can be
 * rendered as an {@code @Element} attribute for the report <em>and</em> turned back into a
 * framework locator for the learned-locator tier.
 *
 * <p>{@code kind} is one of the {@code @Element} attribute names: {@code id, css, xpath, name,
 * text, testId, accessibilityId}.
 */
public record LocatorSuggestion(String kind, String value) {

    public static LocatorSuggestion of(String kind, String value) {
        if (kind == null || value == null || value.isBlank()) return null;
        return new LocatorSuggestion(kind, value);
    }

    /** e.g. {@code @Element(testId = "login-submit")}. */
    public String toAnnotation() {
        return "@Element(" + kind + " = \"" + escape(value) + "\")";
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return kind + "=" + value;
    }
}
