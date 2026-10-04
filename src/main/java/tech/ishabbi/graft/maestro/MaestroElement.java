package tech.ishabbi.graft.maestro;

import tech.ishabbi.graft.LocatorSpec;

/**
 * The field type for {@code @Element} in Maestro screen objects, and what
 * {@link MaestroHealer#element(tech.ishabbi.graft.HealingSelector)} returns for constants. Each
 * action renders one Maestro command against the declared selector; when Maestro reports the
 * element missing, the action is re-done by Alumnium's Maestro driver from the description.
 *
 * <pre>{@code
 * public class LoginScreen {
 *     @Element(value = "Email input on the login screen", id = "email_input")   MaestroElement email;
 *     @Element(value = "'Sign in' button", text = "Sign in")                     MaestroElement signIn;
 * }
 * static final HealingSelector WELCOME = Graft.selector(MaestroSelector.text("Welcome"), "the welcome banner");
 *
 * screen.email.inputText("ish@example.com");
 * screen.signIn.tap();
 * healer.element(WELCOME).assertVisible();
 * }</pre>
 */
public final class MaestroElement {

    private final MaestroHealer healer;
    private final LocatorSpec spec;
    private final MaestroSelector selector;   // null → description-only

    MaestroElement(MaestroHealer healer, LocatorSpec spec, MaestroSelector selector) {
        this.healer = healer;
        this.spec = spec;
        this.selector = selector;
    }

    public LocatorSpec spec() { return spec; }

    public MaestroSelector selector() { return selector; }

    public MaestroElement tap() {
        healer.perform(spec, selector, "tapOn", null, "tap on the " + spec.description());
        return this;
    }

    public MaestroElement longPress() {
        healer.perform(spec, selector, "longPressOn", null, "long press on the " + spec.description());
        return this;
    }

    /** Taps the element, then types. Healed as a single "type X into Y" instruction. */
    public MaestroElement inputText(String text) {
        healer.perform(spec, selector, "tapOn", "- inputText: " + MaestroSelector.yamlString(text),
                "type " + MaestroSelector.yamlString(text) + " into the " + spec.description());
        return this;
    }

    /** Taps the element, then clears the field (Maestro's eraseText defaults to 50 chars). */
    public MaestroElement clearText() {
        healer.perform(spec, selector, "tapOn", "- eraseText", "clear the text in the " + spec.description());
        return this;
    }

    /**
     * {@code assertVisible}. If Maestro cannot find the element, Alumnium is asked whether the
     * described element is visible; a passing check heals the assertion, a failing one surfaces
     * the original Maestro failure.
     */
    public MaestroElement assertVisible() {
        healer.assertVisible(spec, selector);
        return this;
    }

    @Override
    public String toString() {
        return "MaestroElement(" + spec.displayName() + ", \"" + spec.description() + "\")";
    }
}
