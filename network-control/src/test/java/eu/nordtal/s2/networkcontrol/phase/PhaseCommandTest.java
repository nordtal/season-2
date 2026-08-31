package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@code /phase} that exist without a proxy: what an argument parses to, and whether
 * every reply it can print has a translation.
 * <p>
 * <b>The command itself is not exercised.</b> Building the Brigadier tree needs
 * {@code com.velocitypowered.api.command.CommandSource}, running it needs a {@code Player} and a
 * dispatcher, and authorising it needs a login to have happened. That whole path - including the
 * one thing most worth checking, that a non-admin does not even see the command in their tab
 * completion - needs a running Velocity proxy with two real clients.
 * </p>
 */
class PhaseCommandTest {

    @Test
    void everyPhaseNameParses() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(phase, PhaseCommand.parse(phase.name()));
            assertEquals(phase, PhaseCommand.parse(phase.name().toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    void aTypoIsRejectedRatherThanBecomingMaintenance() {
        // SeasonPhase.fromDatabase maps anything unrecognised to MAINTENANCE, which is right when
        // reading a row and catastrophic when reading a command line: "/phase set SMPP" would take
        // the network down instead of saying it did not understand.
        assertNull(PhaseCommand.parse("SMPP"));
        assertNull(PhaseCommand.parse("START-EVENT"));
        assertNull(PhaseCommand.parse(""));
        assertNull(PhaseCommand.parse("RESOURCE_PACK_INSTALL"));
    }

    @Test
    void everyReplyHasBothTranslations() {
        // docs/architecture.md#commands: "Every string a command prints comes from Messages and the
        // player's locale. A command that hardcodes English is a bug, not a shortcut."
        final Messages messages = Messages.load("messages/network-control", Locale.ENGLISH, Locale.GERMAN);

        for (final String key : new String[]{"phase.current", "phase.current.unread", "phase.unknown",
                "phase.changed", "phase.unchanged", "phase.failed"}) {
            assertTrue(messages.hasTranslation(Locale.ENGLISH, key), "missing en: " + key);
            assertTrue(messages.hasTranslation(Locale.GERMAN, key), "missing de: " + key);
        }
    }
}
