package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.Within;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;

import java.util.List;

/**
 * A resolved element and how to get the driver into its frame again.
 *
 * <p>{@code frames} is the {@code within} prefix up to and including the last {@code frame=} hop
 * (shadow hops before it included, since a shadow host may hold the iframe). Hops after it are
 * dropped: an element found inside a shadow root needs no switching. Empty means "the frame the
 * test is in", which needs no switching either.
 *
 * <p>{@code from} is the context the hops are entered from: the driver for an absolute chain
 * (learned locators, heals), or the scoped search context of an inline {@code within}.
 */
record Located(WebElement element, List<Within.Hop> frames, SearchContext from) {

    Located {
        frames = List.copyOf(frames);
    }

    /** The prefix of {@code within} up to and including its last frame hop. */
    static List<Within.Hop> framePrefix(List<Within.Hop> within) {
        for (int i = within.size() - 1; i >= 0; i--) {
            if (within.get(i).type() == Within.Type.FRAME) return within.subList(0, i + 1);
        }
        return List.of();
    }
}
