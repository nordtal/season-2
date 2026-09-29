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
 * What the shipped allowlist lets through, held against the catalogue of player commands.
 *
 * Only one direction is asserted: the list also names Paper-only trees that are no {@link Declaration}.
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
        // Admins bypass the filter.
        assertFalse(SHIPPED.allows("/smp"));
        assertFalse(SHIPPED.allows("/smp reload"));
        assertFalse(SHIPPED.allows("/hg start"));
        assertFalse(SHIPPED.allows("/network reload"));
    }

    @Test
    void theVanillaAndProxySurfaceIsGone() {
        // Velocity refuses /server only on an explicit FALSE; the rest are Paper defaults.
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
        assertTrue(SHIPPED.allows("/navigate"));
        assertTrue(SHIPPED.allows("/poi add home"));
        assertTrue(SHIPPED.allows("/hg ready"));
        assertTrue(SHIPPED.allows("/msg Someone hello"));
        assertTrue(SHIPPED.allows("/whisper Someone hello"));
        assertTrue(SHIPPED.allows("/r hello"));
        assertTrue(SHIPPED.allows("/discord"));
        assertTrue(SHIPPED.allows("/rules"));
    }
}
