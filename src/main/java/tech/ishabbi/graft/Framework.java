package tech.ishabbi.graft;

/** The automation framework a {@link Healer} fronts. */
public enum Framework {
    SELENIUM,
    APPIUM,
    PLAYWRIGHT,
    MAESTRO;

    public boolean isMobile() {
        return this == APPIUM || this == MAESTRO;
    }
}
