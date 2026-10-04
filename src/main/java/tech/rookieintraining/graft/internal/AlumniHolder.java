package tech.rookieintraining.graft.internal;

import ai.alumnium.Alumni;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Holds the {@link Alumni} a healer talks to. Either created lazily on the first heal (and
 * quit on {@code close()}) or handed in by the test (never quit by us).
 *
 * <p>Lazy creation keeps the Alumnium binary out of the picture entirely for test runs that
 * never need a heal; if you also use {@code al.act/check/get} in the test, pass that instance
 * in instead so both share one session and cache.
 */
public final class AlumniHolder {

    private final Supplier<Alumni> factory;
    private final boolean owned;
    private volatile Alumni instance;

    private AlumniHolder(Supplier<Alumni> factory, Alumni external) {
        this.factory = factory;
        this.owned = external == null;
        this.instance = external;
    }

    public static AlumniHolder lazy(Supplier<Alumni> factory) {
        return new AlumniHolder(Objects.requireNonNull(factory), null);
    }

    public static AlumniHolder external(Alumni alumni) {
        return new AlumniHolder(null, Objects.requireNonNull(alumni));
    }

    public Alumni get() {
        Alumni a = instance;
        if (a == null) {
            synchronized (this) {
                a = instance;
                if (a == null) {
                    instance = a = factory.get();
                }
            }
        }
        return a;
    }

    public boolean isCreated() {
        return instance != null;
    }

    public void close() {
        Alumni a = instance;
        if (owned && a != null) {
            instance = null;
            a.quit();
        }
    }
}
