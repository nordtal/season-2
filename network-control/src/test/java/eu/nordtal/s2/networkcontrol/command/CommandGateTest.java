package eu.nordtal.s2.networkcontrol.command;

import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.networkcontrol.config.NetworkSpec;

import eu.nordtal.jcore.config.spec.Specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shipped allowlist actually lets through, held against the command surface it is a list
 * of.
 *
 * <h2>Why the list and the catalogue have to be compared by a test</h2>
 * They are two descriptions of the same thing written in two places: {@code network.yml} says what a
 * player may type, and {@code Catalogue} says which commands are not admin-only. A command declared
 * for players and left out of the list is refused with "that command does not exist" - which is
 * exactly the sentence that makes it undiscoverable, so nobody would ever report it. Nothing else in
 * the build compares them.
 *
 * <p>The other direction is deliberately <b>not</b> asserted. The list carries {@code /navigate},
 * {@code /poi} and the SMP's own {@code /aura}, none of which is a {@link Declaration} at all: they
 * are Brigadier trees a Paper plugin builds for itself. A list that could only name catalogue
 * entries would be a list that cannot name most of what a player types.</p>
 */
class CommandGateTest {

    /** The list exactly as a fresh {@code network.yml} writes it. */
    private static final CommandAllowlist SHIPPED = CommandAllowlist.parse(
            Specs.createDefault(NetworkSpec.class).commandAllowlist());

    @Test
    @DisplayName("every command a player may run is on the list they are filtered against")
    void theCatalogueAndTheListAgree() {
        final List<String> unreachable = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            if (declaration.adminOnly() || !declaration.surfaces().contains(Surface.GAME)) {
                continue;
            }
            if (!SHIPPED.allows(declaration.name())) {
                unreachable.add(declaration.name());
            }
        }
        assertEquals(List.of(), unreachable,
                "a command declared for players is not on the allowlist, so every player who types"
                        + " it is told it does not exist - and being told that is what makes nobody"
                        + " report it");
    }

    @Test
    @DisplayName("no admin command is on the list, because the list is not how an admin gets in")
    void adminCommandsAreNotOnIt() {
        // Admins bypass the filter entirely (CommandGate), so putting an admin command on the list
        // would not help them and would hand it to everybody. /smp is the case that looks like an
        // exception and is not: "smp status" is on the list and lets a bare /smp through to its own
        // help, which lists only what the person typing it may run.
        assertFalse(SHIPPED.allows("/phase set SMP"));
        assertFalse(SHIPPED.allows("/smp reload"));
        assertFalse(SHIPPED.allows("/update now"));
        assertFalse(SHIPPED.allows("/hg start"));
        assertFalse(SHIPPED.allows("/network reload"));
    }

    @Test
    @DisplayName("the commands this whole list exists to refuse are refused")
    void theVanillaAndProxySurfaceIsGone() {
        // /server is finding 148 itself: Velocity's permission check refuses only on an explicit
        // FALSE, so before this list a player could walk onto any backend past the routing. The
        // rest are what a Paper server offers a player by default.
        assertFalse(SHIPPED.allows("/server hunger-games"));
        assertFalse(SHIPPED.allows("/glist"));
        assertFalse(SHIPPED.allows("/send Someone smp"));
        assertFalse(SHIPPED.allows("/me waves"));
        assertFalse(SHIPPED.allows("/help"));
        assertFalse(SHIPPED.allows("/trigger something"));
        assertFalse(SHIPPED.allows("/list"));
        assertFalse(SHIPPED.allows("/tell Someone hello"));
        assertFalse(SHIPPED.allows("/teammsg hello"));
        assertFalse(SHIPPED.allowsRoot("minecraft"), "the namespaced form is the same command");
    }

    @Test
    @DisplayName("what a player is left with is ours, and all of it")
    void thePlayerSurfaceIsIntact() {
        assertTrue(SHIPPED.allows("/smp"));
        assertTrue(SHIPPED.allows("/smp status"));
        assertTrue(SHIPPED.allows("/navigate"));
        assertTrue(SHIPPED.allows("/poi add home"));
        assertTrue(SHIPPED.allows("/hg ready"));
        assertTrue(SHIPPED.allows("/aura"));
        assertTrue(SHIPPED.allows("/msg Someone hello"));
        assertTrue(SHIPPED.allows("/whisper Someone hello"));
        assertTrue(SHIPPED.allows("/r hello"));
        assertTrue(SHIPPED.allows("/discord"));
        assertTrue(SHIPPED.allows("/rules"));
    }
}
