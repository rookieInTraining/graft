package tech.rookieintraining.graft.maestro;

import tech.rookieintraining.graft.ElementSpec;
import tech.rookieintraining.graft.ElementSpec.LocatorKind;
import tech.rookieintraining.graft.HealingSelector;
import tech.rookieintraining.graft.LocatorSpec;
import tech.rookieintraining.graft.LocatorSuggestion;

import java.util.Objects;

/**
 * A Maestro element selector: {@code id:}, {@code text:} or {@code point:}.
 * Renders itself as the argument block of a Maestro command.
 */
public final class MaestroSelector {

    public enum Kind { ID, TEXT, POINT }

    private final Kind kind;
    private final String value;

    private MaestroSelector(Kind kind, String value) {
        this.kind = kind;
        this.value = Objects.requireNonNull(value);
    }

    public static MaestroSelector id(String id) { return new MaestroSelector(Kind.ID, id); }

    public static MaestroSelector text(String text) { return new MaestroSelector(Kind.TEXT, text); }

    /** {@code "x,y"} in pixels or {@code "50%,50%"}. */
    public static MaestroSelector point(String point) { return new MaestroSelector(Kind.POINT, point); }

    /** The selector a locator spec declares, or {@code null} for description-only ones. */
    public static MaestroSelector from(LocatorSpec spec) {
        if (spec instanceof ElementSpec es) return from(es);
        if (spec instanceof HealingSelector hs) return hs.primary(MaestroSelector.class, "Maestro");
        throw new IllegalArgumentException("Maestro cannot derive a selector from " + spec.getClass().getName());
    }

    /** A learned suggestion replayed as a Maestro selector; {@code null} if the kind does not apply. */
    public static MaestroSelector fromSuggestion(LocatorSuggestion s) {
        if (s == null) return null;
        switch (s.kind()) {
            case "id":
            case "testId":
            case "accessibilityId": return id(s.value());
            case "text":            return text(s.value());
            default:                return null;
        }
    }

    /** Structured form for the report / learned tier (points are never learned). */
    public LocatorSuggestion toSuggestion() {
        return kind == Kind.POINT ? null : LocatorSuggestion.of(kind == Kind.ID ? "id" : "text", value);
    }

    /** The selector an {@code @Element} declares, or {@code null} for description-only elements. */
    public static MaestroSelector from(ElementSpec spec) {
        if (!spec.hasInlineLocator()) return null;
        LocatorKind kind = spec.locatorKind().orElseThrow();
        switch (kind) {
            case ID:
            case TEST_ID:
            case ACCESSIBILITY_ID:
                return id(spec.locatorValue());
            case TEXT:
                return text(spec.locatorValue());
            default:
                throw new IllegalStateException("@Element on " + spec.displayName() + " uses " + kind
                        + "; Maestro supports id, testId, accessibilityId (all -> id:) and text");
        }
    }

    public Kind kind() { return kind; }

    public String value() { return value; }

    /** e.g. {@code id: "login_button"} — placed under a command key by the caller. */
    public String toYamlArg() {
        String key = switch (kind) {
            case ID -> "id";
            case TEXT -> "text";
            case POINT -> "point";
        };
        return key + ": " + yamlString(value);
    }

    /** Renders a full command, e.g. {@code - tapOn:\n    id: "login_button"}. */
    public String command(String maestroCommand) {
        return "- " + maestroCommand + ":\n    " + toYamlArg();
    }

    static String yamlString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase() + ": " + value;
    }
}
