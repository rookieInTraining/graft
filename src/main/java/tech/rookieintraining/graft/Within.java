package tech.rookieintraining.graft;

import java.util.List;

/**
 * The context chain ("within") a locator lives in: an ordered, outside-in list of hops, each
 * written as a string.
 *
 * <ul>
 *   <li>{@code frame=<css>}: enter the iframe matched by {@code <css>}.</li>
 *   <li>{@code shadow=<css>}: enter the open shadow root of the host matched by {@code <css>}.</li>
 * </ul>
 *
 * <p>Each hop's CSS is resolved in the scope left by the previous hop. The CSS is everything after
 * the first {@code =}, so it may itself contain {@code =} ({@code frame=iframe[name='pay']}).
 */
public final class Within {

    public enum Type { FRAME, SHADOW }

    /** One hop of the chain. */
    public record Hop(Type type, String css) {

        /** The string form, e.g. {@code frame=#pay}. */
        public String format() {
            return (type == Type.FRAME ? "frame=" : "shadow=") + css;
        }
    }

    private Within() {}

    /** Parse {@code frame=<css>} / {@code shadow=<css>}; the prefix is case-sensitive. */
    public static Hop parse(String hop) {
        if (hop == null) throw new IllegalArgumentException("Invalid within hop: null");
        int eq = hop.indexOf('=');
        if (eq < 0) throw invalid(hop, "expected frame=<css> or shadow=<css>");
        String prefix = hop.substring(0, eq).trim();
        String css = hop.substring(eq + 1).trim();
        Type type = switch (prefix) {
            case "frame" -> Type.FRAME;
            case "shadow" -> Type.SHADOW;
            default -> throw invalid(hop, "unknown prefix \"" + prefix + "\", expected frame or shadow");
        };
        if (css.isEmpty()) throw invalid(hop, "the CSS is blank");
        return new Hop(type, css);
    }

    public static List<Hop> parseAll(String... hops) {
        return hops == null ? List.of() : parseAll(List.of(hops));
    }

    /** An immutable list; {@code null} or empty gives an empty list. */
    public static List<Hop> parseAll(List<String> hops) {
        if (hops == null || hops.isEmpty()) return List.of();
        return hops.stream().map(Within::parse).toList();
    }

    public static List<String> formatAll(List<Hop> hops) {
        return hops.stream().map(Hop::format).toList();
    }

    private static IllegalArgumentException invalid(String hop, String why) {
        return new IllegalArgumentException("Invalid within hop \"" + hop + "\": " + why);
    }
}
