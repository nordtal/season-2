package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.proxy.ProxyRenderer;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fail-closed handler, which has no state to branch on and no admin path.
 *
 * The {@code LoginEvent} wiring needs a Velocity {@code Player} and is not exercised.
 */
class MisconfiguredGateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MisconfiguredGateTest.class);

    private MisconfiguredGate gate;

    @BeforeEach
    void freshGate() {
        gate = new MisconfiguredGate(LOGGER, ProxyRenderer.RENDERER);
    }

    @Test
    void theDocumentLoadsWithoutAnyConfigurationAtAll() {
        // This screen must render when the configuration itself is broken.
        assertFalse(screen(Locale.ENGLISH).isBlank());
        assertNotEquals(
                screen(Locale.ENGLISH),
                screen(Locale.GERMAN),
                "the screen is bilingual, so the German half has to exist");
    }

    @Test
    void theScreenShowsBothLanguagesBecauseNobodyCanBeIdentified() {
        final String rendered = flatten(gate.refuse(UUID.randomUUID(), "someone"));

        assertTrue(rendered.contains(screen(Locale.ENGLISH)), rendered);
        assertTrue(rendered.contains(screen(Locale.GERMAN)), rendered);
    }

    @Test
    void theServerListTextIsRenderedWithItsTones() {
        final Component motd = gate.motd();

        assertTrue(flatten(motd).contains("misconfigured"), flatten(motd));
        assertTrue(painted(motd), "the text's tones are lost when it is rendered as plain text first");
    }

    private static boolean painted(final Component component) {
        return component.color() != null || component.children().stream().anyMatch(MisconfiguredGateTest::painted);
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

    /** The packaged screen in one language as a player reads it. */
    private static String screen(final Locale locale) {
        return flatten(ProxyRenderer.RENDERER.format(locale, Sent.of(MisconfiguredGate.SCREEN)));
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
