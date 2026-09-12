package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployer must never recreate itself, and the refusal has to happen before anything runs.
 *
 * <p>These tests need no docker daemon on purpose: the guard is checked while the command line is
 * being assembled, which is the only point at which refusing still helps. A guard that fired after
 * {@code docker compose up} had started would be a container recreating the process that asked.</p>
 */
class ComposeRefusesItselfTest {

    private final Compose compose = new Compose(
            Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    @DisplayName("recreating steward-deployer is refused, and the message says who does it instead")
    void refusesToRecreateItself() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> compose.recreate(Compose.SELF, line -> { }));

        assertTrue(refused.getMessage().contains("setup script"), refused.getMessage());
    }

    @Test
    @DisplayName("and so is an `up` that names it among other services")
    void refusesToBringItselfUpByName() {
        assertThrows(IllegalArgumentException.class,
                () -> compose.up(List.of("smp", Compose.SELF), line -> { }));
    }

    @Test
    @DisplayName("the name it refuses is the compose service name, not a class name")
    void theNameIsTheComposeServiceName() {
        // If this ever drifts from compose.yml the guard silently stops guarding: it would refuse a
        // service nobody deploys and let the real one through.
        assertEquals("steward-deployer", Compose.SELF);
    }
}
