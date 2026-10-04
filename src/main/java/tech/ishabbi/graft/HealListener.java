package tech.ishabbi.graft;

/**
 * Observes healing. Register via {@link HealingConfig.Builder#addListener(HealListener)} to
 * push heals into Allure/ReportPortal, metrics, or a Slack bot that opens locator-fix PRs.
 */
public interface HealListener {

    /** Alumnium resolved the element after the primary locator failed. */
    void onHeal(HealEvent event);

    /** The primary locator failed and Alumnium could not resolve the element either. */
    default void onHealFailed(LocatorSpec spec, Throwable cause) {
        // no-op
    }

    /** Healing was skipped because it is disabled or the per-element budget is exhausted. */
    default void onHealSkipped(LocatorSpec spec, String reason) {
        // no-op
    }
}
