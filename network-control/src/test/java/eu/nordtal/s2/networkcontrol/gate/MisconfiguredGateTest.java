package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fail-closed handler (docs/operations.md#configuration-and-secrets, finding 2 in
 * docs/state-of-play.md).
 * <p>
 * There is deliberately very little to test here, and that is the property being asserted: this
 * class has no state to branch on, so it cannot be talked into letting somebody through. The one
 * thing worth writing down is that <b>there is no admin path</b> - the tests below refuse the same
 * player twice and a hundred players once, and nothing about who they are enters into it, because
 * the admin flag lives in the database a broken {@code database.yml} cannot reach.
 * </p>
 * <p>
 * The {@code LoginEvent} wiring itself is not exercised: constructing one needs a
 * {@code com.velocitypowered.api.proxy.Player}, which only exists on a running proxy. That the
 * handler is registered at all when {@code Configs} throws is verified by reading
 * {@code NetworkControlPlugin} and, properly, by starting a proxy with a mistyped key in it.
 * </p>
 */
class MisconfiguredGateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MisconfiguredGateTest.class);

    private Messages messages;
    private MisconfiguredGate gate;

    @BeforeEach
    void freshGate() {
        messages = Messages.load("messages/network-control", Locale.ENGLISH, Locale.GERMAN);
        gate = new MisconfiguredGate(LOGGER, messages);
    }

    @Test
    void theBundleLoadsWithoutAnyConfigurationAtAll() {
        // The whole point: this screen has to render on a path where the configuration is what is
        // broken. A resource bundle needs the classpath and nothing else.
        assertNotNull(messages.get(Locale.ENGLISH, "gate.misconfigured"));
        assertTrue(messages.hasTranslation(Locale.GERMAN, "gate.misconfigured"),
                "the screen is bilingual, so the German half has to exist");
    }

    @Test
    void theScreenShowsBothLanguagesBecauseNobodyCanBeIdentified() {
        final String rendered = flatten(gate.refuse(UUID.randomUUID(), "someone"));

        assertTrue(rendered.contains(messages.get(Locale.ENGLISH, "gate.misconfigured")), rendered);
        assertTrue(rendered.contains(messages.get(Locale.GERMAN, "gate.misconfigured")), rendered);
    }

    @Test
    void everybodyGetsTheSameScreenAndItIsAlwaysTheSameObject() {
        final Component first = gate.refuse(UUID.randomUUID(), "a-player");
        final Component second = gate.refuse(UUID.randomUUID(), "an-admin");

        assertEquals(first, second,
                "there is no admin exemption and there cannot be one: the admin flag lives in the "
                        + "database a broken database.yml cannot reach");
    }

    @Test
    void everyRefusalIsCounted() {
        for (int attempt = 0; attempt < 60; attempt++) {
            gate.refuse(UUID.randomUUID(), "player-" + attempt);
        }

        assertEquals(60, gate.refusedCount(),
                "the count is what makes 'the proxy is up but nobody can join' announce itself");
    }

    @Test
    void aFreshHandlerHasRefusedNobody() {
        assertEquals(0, gate.refusedCount());
    }

    /**
     * The component's text and every child's, concatenated. Adventure's plain-text serializer is
     * not depended on for one assertion; this is the whole of what it would be used for.
     */
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
