package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fail-closed handler.
 *
 * There is deliberately little to test, and that is the property being asserted: this class has no
 * state to branch on, so it cannot be talked into letting somebody through. In particular there is
 * <b>no admin path</b> - the admin flag lives in the database a broken {@code database.yml} cannot
 * reach.
 *
 *
 * The {@code LoginEvent} wiring is not exercised: constructing one needs a Velocity
 * {@code Player}, which only exists on a running proxy.
 */
class MisconfiguredGateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MisconfiguredGateTest.class);

    private Messages messages;
    private MisconfiguredGate gate;

    @BeforeEach
    void freshGate() {
        messages = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);
        gate = new MisconfiguredGate(LOGGER, messages);
    }

    @Test
    void theBundleLoadsWithoutAnyConfigurationAtAll() {
        // The whole point: this screen must render when the configuration itself is what is broken.
        assertNotNull(messages.get(Locale.ENGLISH, "gate.misconfigured"));
        assertTrue(
                messages.hasTranslation(Locale.GERMAN, "gate.misconfigured"),
                "the screen is bilingual, so the German half has to exist");
    }

    @Test
    void theScreenShowsBothLanguagesBecauseNobodyCanBeIdentified() {
        final String rendered = flatten(gate.refuse(UUID.randomUUID(), "someone"));

        // Compared against the drawn text, not the raw value: a raw comparison would fail on colour as if missing.
        assertTrue(rendered.contains(drawn(messages.get(Locale.ENGLISH, "gate.misconfigured"))), rendered);
        assertTrue(rendered.contains(drawn(messages.get(Locale.GERMAN, "gate.misconfigured"))), rendered);
    }

    @Test
    void everybodyGetsTheSameScreenAndItIsAlwaysTheSameObject() {
        final Component first = gate.refuse(UUID.randomUUID(), "a-player");
        final Component second = gate.refuse(UUID.randomUUID(), "an-admin");

        assertEquals(
                first,
                second,
                "there is no admin exemption and there cannot be one: the admin flag lives in the "
                        + "database a broken database.yml cannot reach");
    }

    @Test
    void everyRefusalIsCounted() {
        for (int attempt = 0; attempt < 60; attempt++) {
            gate.refuse(UUID.randomUUID(), "player-" + attempt);
        }

        assertEquals(
                60,
                gate.refusedCount(),
                "the count is what makes 'the proxy is up but nobody can join' announce itself");
    }

    @Test
    void aFreshHandlerHasRefusedNobody() {
        assertEquals(0, gate.refusedCount());
    }

    /** A bundle value with its MiniMessage tags taken off - what a player reads off this screen. */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "");
    }

    /** The component's text and every child's, concatenated. */
    private static String flatten(final Component component) {
        final StringBuilder text = new StringBuilder();
        if (component instanceof TextComponent textComponent) {
            text.append(textComponent.content());
        }
        for (final Component child : component.children()) {
            text.append(flatten(child));
        }
        return text.toString();
    }
}
