package tech.ishabbi.graft;

/**
 * Thrown when the primary locator failed and healing did not (or was not allowed to) rescue
 * it. The cause is the original framework exception; {@link #event()} is non-null only in
 * strict mode, where the heal succeeded but the step is failed on purpose.
 */
public class HealingException extends RuntimeException {

    private final transient LocatorSpec spec;
    private final transient HealEvent event;

    public HealingException(String message, LocatorSpec spec, Throwable cause) {
        this(message, spec, null, cause);
    }

    public HealingException(String message, LocatorSpec spec, HealEvent event, Throwable cause) {
        super(message, cause);
        this.spec = spec;
        this.event = event;
    }

    public LocatorSpec spec() { return spec; }

    /** Present when the element was healed but strict mode failed the step anyway. */
    public HealEvent event() { return event; }
}
