package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rule behind every countdown line, and the lines themselves.
 *
 * Message keys are derived from the enums, so a constant without a line fails here.
 */
class RunShapeTest {

    private static final String ROOT = "messages/proxy";

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
            assertEquals(RunShape.Occasion.MAINTENANCE, shape.occasion(), kind.name());
            assertEquals(RunShape.Fate.DISCONNECT, shape.fateFor("smp"), kind.name());
            assertEquals(
                    RunShape.Fate.NOTHING,
                    shape.fateFor("hunger-games"),
                    "a server that is not in the run does not stop because the limbo did");
        }
    }

    @Test
    void everyKindHasItsOwnWord() {
        assertEquals(RunShape.Occasion.DOWN, occasionOf(UpdateKind.DOWN));
        assertEquals(RunShape.Occasion.RECREATE, occasionOf(UpdateKind.RESTART));
        assertEquals(RunShape.Occasion.BACKUP, occasionOf(UpdateKind.BACKUP));
        assertEquals(RunShape.Occasion.UPDATE, occasionOf(UpdateKind.UPDATE));

        // START reaches no player but is named anyway.
        assertEquals(RunShape.Occasion.UPDATE, occasionOf(UpdateKind.START));
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
    void everyOccasionHasItsThreeLines() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final RunShape.Occasion occasion : RunShape.Occasion.values()) {
            for (final String family : new String[] {"restart.countdown.", "restart.now.", "restart.occasion."}) {
                final String key = family + key(occasion);
                assertTrue(english.containsKey(key), "no English line for " + key);
                assertTrue(german.containsKey(key), "no German line for " + key);
            }
        }
    }

    @Test
    void thePlaceholdersAreTheOnesRestartWatchPasses() throws IOException {
        final Properties english = load("en");

        for (final RunShape.Occasion occasion : RunShape.Occasion.values()) {
            final String countdown = english.getProperty("restart.countdown." + key(occasion));
            assertTrue(
                    countdown.contains("{seconds}"),
                    "a countdown that does not name the number is a line that never changes: " + countdown);
            // <what> is optional: MAINTENANCE says "nordtal", since a network-wide run is about no one service.
            final String now = english.getProperty("restart.now." + key(occasion));
            assertFalse(now.contains("{seconds}"), "zero is not a number worth printing: " + now);
        }
    }

    @Test
    void everyExitIsSpokenFor() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final RunShape.Fate fate : RunShape.Fate.values()) {
            final String key = "restart.fate." + key(fate);
            if (fate == RunShape.Fate.NOTHING) {
                // RestartWatch appends nothing here, so a line would be dead text.
                assertFalse(english.containsKey(key), key + " exists and is never printed");
                assertFalse(german.containsKey(key), key + " exists and is never printed");
                continue;
            }
            assertTrue(english.containsKey(key), "no English line for " + key);
            assertTrue(german.containsKey(key), "no German line for " + key);
        }
    }

    @Test
    void theVoiceLineIsThere() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        assertTrue(english.containsKey("restart.voice"), "no English line for restart.voice");
        assertTrue(german.containsKey("restart.voice"), "no German line for restart.voice");
        assertFalse(
                english.getProperty("restart.voice").contains("{"),
                "the hint takes no placeholder: this proxy knows no number to put in one");
    }

    @Test
    void theServicesAreNamed() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        assertTrue(english.containsKey("restart.what.network"), "restart.what.network");
        for (final String service : SERVICES) {
            final String key = "restart.what." + service;
            assertTrue(english.containsKey(key), "no English name for " + key);
            assertTrue(german.containsKey(key), "no German name for " + key);
        }
    }

    // helpers

    /** {@code RestartWatch.key}, which this must match exactly. */
    private static String key(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static RunShape.Occasion occasionOf(final UpdateKind kind) {
        return RunShape.of(kind, Set.of("smp"), true, false).occasion();
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream =
                RunShapeTest.class.getClassLoader().getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertTrue(stream != null, "no bundle for " + language);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
