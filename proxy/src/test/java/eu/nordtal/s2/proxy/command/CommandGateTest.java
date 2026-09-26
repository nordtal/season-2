package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the shipped allowlist actually lets through, held against the command surface it is a list of.
 *
 * <b>Why the list and the catalogue have to be compared by a test.</b>
 * They are two descriptions of the same thing written in two places: {@code network.yml} says what a
 * player may type, and {@code Catalogue} says which commands are not admin-only. A command declared
 * for players and left out of the list is refused with "that command does not exist" - which is
 * exactly the sentence that makes it undiscoverable, so nobody would ever report it. Nothing else in
 * the build compares them.
 *
 * The other direction is deliberately <b>not</b> asserted. The list carries {@code /navigate},
 * {@code /poi} and the SMP's own {@code /aura}, none of which is a {@link Declaration} at all: they
 * are Brigadier trees a Paper plugin builds for itself. A list that could only name catalogue
 * entries would be a list that cannot name most of what a player types.
 */
class CommandGateTest {

    /** The list exactly as a fresh {@code network.yml} writes it. */
    private static final CommandAllowlist SHIPPED =
            CommandAllowlist.parse(Specs.createDefault(NetworkSpec.class).commandAllowlist());

    @Test
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
        assertEquals(
                List.of(),
                unreachable,
                "a command declared for players is not on the allowlist, so every player who types"
                        + " it is told it does not exist - and being told that is what makes nobody"
                        + " report it");
    }

    @Test
    void adminCommandsAreNotOnIt() {
        // Admins bypass the filter; /smp looks like an exception, but only lets a bare /smp reach its own help.
        assertFalse(SHIPPED.allows("/phase set SMP"));
        assertFalse(SHIPPED.allows("/smp reload"));
        assertFalse(SHIPPED.allows("/update now"));
        assertFalse(SHIPPED.allows("/hg start"));
        assertFalse(SHIPPED.allows("/network reload"));
    }

    @Test
    void theVanillaAndProxySurfaceIsGone() {
        // /server is finding 148 itself: Velocity refuses only on an explicit FALSE; the rest are Paper defaults.
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
