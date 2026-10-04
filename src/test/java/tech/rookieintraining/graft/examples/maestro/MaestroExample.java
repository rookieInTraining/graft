package tech.rookieintraining.graft.examples.maestro;

import tech.rookieintraining.graft.Element;
import tech.rookieintraining.graft.Graft;
import tech.rookieintraining.graft.Healer;
import tech.rookieintraining.graft.HealingSelector;
import tech.rookieintraining.graft.maestro.MaestroDevice;
import tech.rookieintraining.graft.maestro.MaestroElement;
import tech.rookieintraining.graft.maestro.MaestroHealer;
import tech.rookieintraining.graft.maestro.MaestroSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Needs the Maestro CLI, the Alumnium binary ({@code alumnium mcp}), an AI provider, and an
 * Android emulator with the app installed. Run with {@code GRAFT_E2E=true}.
 *
 * <p>Note there is no {@code launchApp} here: {@code Graft.with(device)} starts an Alumnium session,
 * which launches the app once, before any step runs.
 */
@EnabledIfEnvironmentVariable(named = "GRAFT_E2E", matches = "true")
class MaestroExample {

    static class LoginScreen {
        @Element(value = "the email input on the login screen", id = "email_input_old")   // renamed → heals
        MaestroElement email;

        @Element(value = "the password input", id = "password_input")
        MaestroElement password;

        @Element(value = "the 'Sign in' button", text = "Sign in")
        MaestroElement signIn;

        @Element(value = "the home screen greeting that says Welcome", text = "Welcome")
        MaestroElement welcome;
    }

    static final HealingSelector LOGOUT = Graft.selector(MaestroSelector.text("Log out"), "the 'Log out' button");

    private MaestroHealer healer;

    @BeforeEach
    void setUp() {
        // ANDROID_SERIAL may be a local emulator or an `adb connect host:port` device-farm serial;
        // the same identifier is handed to Alumnium's Maestro session, so both drive one device.
        MaestroDevice device = MaestroDevice.builder("com.example.app", "android")
                .device(System.getenv("ANDROID_SERIAL"))   // null → first connected device
                .build();
        healer = MaestroHealer.of(device);
    }

    @AfterEach
    void tearDown() {
        healer.close();   // stops the Alumnium session and the MCP process
    }

    @Test
    void logsIn() {
        LoginScreen screen = Graft.page(LoginScreen.class, healer);
        screen.email.inputText("ish@example.com");
        screen.password.inputText("secret");
        screen.signIn.tap();
        screen.welcome.assertVisible();
        healer.element(LOGOUT).tap();
    }
}
