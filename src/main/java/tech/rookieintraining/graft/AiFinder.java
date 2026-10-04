package tech.rookieintraining.graft;

/**
 * The one capability healing needs from an AI backend: turn a description into a native element.
 * Alumnium's {@code Alumni.find} is the default implementation; the seam exists for tests and for
 * plugging in a different resolver.
 */
@FunctionalInterface
public interface AiFinder {

    /** Returns a native element/locator for the description, or throws if none can be found. */
    Object find(String description);
}
