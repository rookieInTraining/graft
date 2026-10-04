package tech.rookieintraining.graft;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The heal flow shared by every framework adapter. Subclasses supply "find it with the primary
 * locator", "find it with a learned locator" and "find it with Alumnium"; this class owns the policy:
 *
 * <ol>
 *   <li>Is healing enabled, globally and for this locator?</li>
 *   <li>Learned tier: a locator remembered from an earlier heal, tried once, evicted if it fails.</li>
 *   <li>Is the per-locator heal budget exhausted?</li>
 *   <li>Run the Alumnium lookup, time it, derive a suggestion, remember it.</li>
 *   <li>Notify listeners (incl. the global {@link HealReport}).</li>
 *   <li>In strict mode, fail the step even though healing succeeded.</li>
 * </ol>
 */
public abstract class AbstractHealer implements Healer {

    protected final System.Logger log = System.getLogger(getClass().getName());
    private final HealingConfig config;
    private final LearnedLocators learned;
    private final List<HealListener> listeners;
    private final Map<String, Integer> healCounts = new ConcurrentHashMap<>();

    protected AbstractHealer(HealingConfig config) {
        this.config = config;
        this.learned = config.learnedLocators();
        this.listeners = new ArrayList<>(config.listeners());
        this.listeners.add(HealReport.global());
        HealReport.writeOnExit(config.reportPath());
    }

    @Override
    public HealingConfig config() { return config; }

    public LearnedLocators learnedLocators() { return learned; }

    /** Alumnium lookups performed so far for the locator in this session. */
    public int healCount(LocatorSpec spec) {
        return healCounts.getOrDefault(spec.key(), 0);
    }

    /**
     * Runs the heal policy around a framework-specific Alumnium lookup.
     *
     * @param spec            the locator being healed
     * @param originalLocator human-readable form of the locator that failed
     * @param cause           the framework exception that triggered healing
     * @param tryLearned      resolves a learned suggestion with the framework, returning {@code null} on miss
     * @param lookup          calls Alumnium and returns the native element/locator
     * @param describeResult  short description of what Alumnium found (for the event)
     * @param suggest         derives a replacement locator from the result, may return null
     */
    protected <T> T heal(LocatorSpec spec,
                         String originalLocator,
                         Throwable cause,
                         Function<LocatorSuggestion, T> tryLearned,
                         Supplier<T> lookup,
                         Function<T, String> describeResult,
                         Function<T, LocatorSuggestion> suggest) {
        if (!config.enabled() || !spec.healEnabled()) {
            notifySkipped(spec, config.enabled() ? "heal disabled for this locator" : "healing disabled");
            throw rethrow(cause);
        }

        // Learned tier: no LLM, one quick attempt.
        Optional<LearnedLocators.Entry> remembered = learned.entry(spec.key());
        if (remembered.isPresent()) {
            T viaLearned = null;
            try {
                viaLearned = tryLearned.apply(remembered.get().suggestion());
            } catch (RuntimeException ignored) {
                // treated as a miss
            }
            if (viaLearned != null) {
                log.log(System.Logger.Level.INFO, "{0}: {1} failed, learned locator {2} matched",
                        spec.displayName(), originalLocator, remembered.get().suggestion());
                return viaLearned;
            }
            learned.forget(spec.key(), remembered.get().learnedAt());
        }

        int count = healCounts.merge(spec.key(), 1, Integer::sum);
        if (count > config.maxHealsPerElement()) {
            notifySkipped(spec, "heal budget exhausted (" + config.maxHealsPerElement() + ")");
            throw new HealingException("Heal budget exhausted for " + spec.displayName()
                    + " after " + config.maxHealsPerElement() + " Alumnium lookups; "
                    + "the primary locator keeps failing: " + originalLocator, spec, cause);
        }

        log.log(System.Logger.Level.INFO, "Healing {0}: {1} failed ({2}); asking Alumnium for \"{3}\"",
                spec.displayName(), originalLocator, reason(cause), spec.description());

        Instant start = Instant.now();
        T result;
        try {
            result = lookup.get();
        } catch (RuntimeException alumniumFailure) {
            notifyFailed(spec, alumniumFailure);
            HealingException ex = new HealingException("Could not heal " + spec.displayName()
                    + ": primary locator " + originalLocator + " failed and Alumnium could not find \""
                    + spec.description() + "\" (" + alumniumFailure.getMessage() + ")", spec, cause);
            ex.addSuppressed(alumniumFailure);
            throw ex;
        }
        if (result == null) {
            notifyFailed(spec, new IllegalStateException("Alumnium returned null"));
            throw new HealingException("Could not heal " + spec.displayName()
                    + ": Alumnium returned nothing for \"" + spec.description() + "\"", spec, cause);
        }

        LocatorSuggestion suggestion = safely(() -> suggest.apply(result), null);
        if (suggestion != null) learned.learn(spec.key(), suggestion, spec.origin(), framework().name());

        HealEvent event = new HealEvent(
                framework(), spec.key(), spec.origin(), spec.description(), originalLocator, reason(cause),
                safely(() -> describeResult.apply(result), "<unavailable>"),
                suggestion, Duration.between(start, Instant.now()), start);
        notifyHealed(event);

        if (config.strict()) {
            throw new HealingException("[strict] " + spec.displayName() + " was healed by Alumnium; "
                    + "update the locator. " + event.summary(), spec, event, cause);
        }
        return result;
    }

    /** Convenience for adapters without a learned tier (Maestro's free-text suggestions, for instance). */
    protected <T> T heal(LocatorSpec spec, String originalLocator, Throwable cause, Supplier<T> lookup,
                         Function<T, String> describeResult, Function<T, LocatorSuggestion> suggest) {
        return heal(spec, originalLocator, cause, s -> null, lookup, describeResult, suggest);
    }

    // ---- helpers --------------------------------------------------------------------------

    protected static String reason(Throwable t) {
        if (t == null) return "unknown";
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        int nl = msg.indexOf('\n');
        return t.getClass().getSimpleName() + ": " + (nl > 0 ? msg.substring(0, nl) : msg);
    }

    protected static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }

    private static <T> T safely(Supplier<T> s, T fallback) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void notifyHealed(HealEvent event) {
        log.log(System.Logger.Level.WARNING, event.summary());
        for (HealListener l : listeners) {
            try { l.onHeal(event); } catch (RuntimeException e) { warn(l, e); }
        }
    }

    private void notifyFailed(LocatorSpec spec, Throwable cause) {
        for (HealListener l : listeners) {
            try { l.onHealFailed(spec, cause); } catch (RuntimeException e) { warn(l, e); }
        }
    }

    private void notifySkipped(LocatorSpec spec, String why) {
        for (HealListener l : listeners) {
            try { l.onHealSkipped(spec, why); } catch (RuntimeException e) { warn(l, e); }
        }
    }

    private void warn(HealListener l, RuntimeException e) {
        log.log(System.Logger.Level.WARNING, "HealListener " + l + " threw", e);
    }
}
