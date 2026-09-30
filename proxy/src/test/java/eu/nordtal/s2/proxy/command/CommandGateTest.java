package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.database.command.CommandAllowlist;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import org.junit.jupiter.api.Test;

/** What the shipped allowlist lets through. */
class CommandGateTest {

    /** The list exactly as a fresh {@code network.yml} writes it. */
    private static final CommandAllowlist SHIPPED =
            CommandAllowlist.parse(Specs.createDefault(NetworkSpec.class).commandAllowlist());

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
