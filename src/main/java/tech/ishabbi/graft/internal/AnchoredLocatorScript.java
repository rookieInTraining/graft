package tech.ishabbi.graft.internal;

import tech.ishabbi.graft.LocatorSuggestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shared in-page script that derives an anchored, uniqueness-checked locator for an element,
 * including the {@code shadow=} hops of open shadow roots it sits in. Framework-neutral: Playwright
 * runs it with {@code locator.evaluate(source(), opts)}, Selenium with
 * {@code executeScript("return (" + source() + ")(arguments[0], arguments[1]);", el, opts)}.
 *
 * <p>The script returns {@code {within: string[], kind: string, value: string}} or {@code null}.
 * {@code opts} is {@code {cssOnlyInShadow, pierce, cssOnly, noText}} (all boolean): skip the text tier
 * inside shadow roots; count uniqueness across nested open shadow roots; force a CSS locator for the
 * element itself (for an iframe's {@code frame=} hop); skip the text tier altogether. The script
 * header documents each.
 *
 * <p>Internal: public only so the Selenium and Playwright adapters can share it.
 */
public final class AnchoredLocatorScript {

    private static final String RESOURCE = "/tech/ishabbi/graft/anchored-locator.js";
    private static final String SOURCE = load();

    private AnchoredLocatorScript() {}

    /** The script as a JS function expression {@code (el, opts) => result}. */
    public static String source() {
        return SOURCE;
    }

    /**
     * Convert the script's result (a {@code Map} from either framework) into a suggestion, or
     * {@code null} when the result is null or malformed.
     */
    public static LocatorSuggestion toSuggestion(Object result) {
        if (!(result instanceof Map<?, ?> map)) return null;
        if (!(map.get("kind") instanceof String kind) || !(map.get("value") instanceof String value)) return null;
        Object rawWithin = map.get("within");
        List<String> within = new ArrayList<>();
        if (rawWithin != null) {
            if (!(rawWithin instanceof List<?> hops)) return null;
            for (Object hop : hops) {
                if (!(hop instanceof String h)) return null;
                within.add(h);
            }
        }
        try {
            return LocatorSuggestion.of(kind, value, within);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String load() {
        try (InputStream in = AnchoredLocatorScript.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing resource " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }
}
