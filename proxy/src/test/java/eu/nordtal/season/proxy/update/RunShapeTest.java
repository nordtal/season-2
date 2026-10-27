package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Occasion;
import eu.nordtal.season.messages.value.Service;
import eu.nordtal.season.proxy.ProxyRenderer;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The rule behind every countdown line, and the lines themselves.
 *
 * The documents pick their lines by the enums, so a constant without a line fails here.
 */
class RunShapeTest {

    private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};

    /** The backends this network has; a name without a line still falls back to its compose name. */
    private static final Set<String> SERVICES = Set.of("smp", "limbo", "hunger-games", "proxy");

    // the table

    @Test
    void aRunThatDoesNotTouchYou() {
        final RunShape shape = RunShape.of(UpdateKind.RESTART, Set.of("hunger-games"), true, false);

        assertEquals(RunShape.Fate.WAITING_ROOM, shape.fateFor("hunger-games"));
        assertEquals(RunShape.Fate.NOTHING, shape.fateFor("smp"));
        assertEquals(
                RunShape.Fate.NOTHING,
                shape.fateFor(null),
                "a player mid-login is on this proxy, and this proxy is staying");
        assertEquals("hunger-games", shape.onlyService());
        assertTrue(shape.touchesAnybody());
    }

    @Test
    void theProxySwap() {
        final RunShape caught = RunShape.of(UpdateKind.UPDATE, Set.of("proxy"), true, true);
        assertEquals(RunShape.Fate.RECONNECT, caught.fateFor("smp"));
        assertEquals(RunShape.Fate.RECONNECT, caught.fateFor(null));
        assertTrue(caught.proxyMoves());

        // Same run with nothing to hand the network to: the countdown is the only warning.
        final RunShape alone = RunShape.of(UpdateKind.UPDATE, Set.of("proxy"), true, false);
        assertEquals(RunShape.Fate.DISCONNECT, alone.fateFor("smp"));
        assertEquals(RunShape.Fate.DISCONNECT, alone.fateFor(null));
    }

    @Test
    void theOwnServerComesFirst() {
        // Both exits are open, so the player sits in the waiting room during the swap.
        final RunShape shape = RunShape.of(UpdateKind.UPDATE, Set.of("smp", "proxy"), true, true);

        assertEquals(RunShape.Fate.WAITING_ROOM, shape.fateFor("smp"));
        assertEquals(RunShape.Fate.RECONNECT, shape.fateFor("hunger-games"));
        assertNull(shape.onlyService(), "two services have no honest short name but 'the network'");
    }

    @Test
    void nowhereToPutAnybody() {
        // With limbo in the run and no standby, every kind is the same run to its players.
        for (final UpdateKind kind : UpdateKind.values()) {
            final RunShape shape = RunShape.of(kind, Set.of("smp", "limbo"), false, false);
            assertEquals(Occasion.MAINTENANCE, shape.occasion(), kind.name());
            assertEquals(RunShape.Fate.DISCONNECT, shape.fateFor("smp"), kind.name());
            assertEquals(
                    RunShape.Fate.NOTHING,
                    shape.fateFor("hunger-games"),
                    "a server that is not in the run does not stop because the limbo did");
        }
    }

    @Test
    void everyKindHasItsOwnWord() {
        assertEquals(Occasion.DOWN, occasionOf(UpdateKind.DOWN));
        assertEquals(Occasion.RECREATE, occasionOf(UpdateKind.RESTART));
        assertEquals(Occasion.BACKUP, occasionOf(UpdateKind.BACKUP));
        assertEquals(Occasion.UPDATE, occasionOf(UpdateKind.UPDATE));

        // START reaches no player but is named anyway.
        assertEquals(Occasion.UPDATE, occasionOf(UpdateKind.START));
    }

    @Test
    void anEmptyRunTouchesNobody() {
        final RunShape shape = RunShape.of(UpdateKind.START, Set.of(), true, true);

        assertFalse(shape.touchesAnybody());
        assertNull(shape.onlyService());
        assertEquals(RunShape.Fate.NOTHING, shape.fateFor("smp"));
    }

    // the voice hint

    @Test
    void theVoiceHintHasTwoConditions() {
        // The hint is only needed when players are on the SMP via proxy-standby.
        assertTrue(RunShape.losesVoice(RunShape.Fate.RECONNECT, false));
        assertFalse(
                RunShape.losesVoice(RunShape.Fate.RECONNECT, true),
                "the waiting room has neither voice nor chat, so this would be noise there");

        // The other three fates are no swap from a player's seat.
        assertFalse(RunShape.losesVoice(RunShape.Fate.NOTHING, false));
        assertFalse(RunShape.losesVoice(RunShape.Fate.WAITING_ROOM, false));
        assertFalse(RunShape.losesVoice(RunShape.Fate.DISCONNECT, false));
    }

    // the lines

    @Test
    void everyOccasionHasItsThreeLines() {
        for (final Locale locale : LOCALES) {
            for (final Occasion occasion : Occasion.values()) {
                final String countdown = plain(locale, RestartWatch.countdown(occasion, "smp", 17));
                assertTrue(
                        countdown.contains("17"),
                        "a countdown that does not name the number is a line that never changes: " + countdown);
                // The service is optional: MAINTENANCE names the network, since such a run is about no one service.
                final String now = plain(locale, RestartWatch.now(occasion, "smp"));
                assertFalse(now.isBlank(), occasion + " has no now line in " + locale);
                assertFalse(now.matches(".*\\d.*"), "zero is not a number worth printing: " + now);
                final String cancelled =
                        plain(locale, RestartWatch.CANCELLED.with(new RestartWatch.Occasioned(occasion)));
                assertFalse(cancelled.isBlank(), occasion + " has no cancelled line in " + locale);
            }
        }
    }

    @Test
    void everyExitIsSpokenFor() {
        for (final RunShape.Fate fate : RunShape.Fate.values()) {
            final String english = plain(Locale.ENGLISH, RestartWatch.FATE.with(new RestartWatch.Fated(fate)));
            final String german = plain(Locale.GERMAN, RestartWatch.FATE.with(new RestartWatch.Fated(fate)));
            if (fate == RunShape.Fate.NOTHING) {
                // RestartWatch appends nothing here, so a line would be dead text.
                assertEquals("", english, fate + " has a line and it is never printed");
                continue;
            }
            assertFalse(english.isBlank(), "no line for " + fate);
            assertNotEquals(english, german, "no German line for " + fate);
        }
    }

    @Test
    void theVoiceLineIsThere() {
        final String english = plain(Locale.ENGLISH, Sent.of(RestartWatch.VOICE));
        assertFalse(english.isBlank(), "no line for the voice hint");
        assertNotEquals(
                english, plain(Locale.GERMAN, Sent.of(RestartWatch.VOICE)), "no German line for the voice hint");
    }

    @Test
    void theServicesAreNamed() {
        assertEquals(Service.NETWORK, Service.of(null));
        for (final String service : SERVICES) {
            assertNotEquals(Service.OTHER, Service.of(service), "no name for " + service);
        }
    }

    // helpers

    private static Occasion occasionOf(final UpdateKind kind) {
        return RunShape.of(kind, Set.of("smp"), true, false).occasion();
    }

    private static String plain(final Locale locale, final Sent<?> sent) {
        return PlainTextComponentSerializer.plainText().serialize(ProxyRenderer.RENDERER.format(locale, sent));
    }
}
