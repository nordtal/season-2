package eu.nordtal.s2.smp.wheel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wheel still spinning when the server goes down pays out.
 *
 * <h2>The failure it exists for</h2>
 * The spin is spent in SQL <em>before</em> the first frame is drawn - deliberately, so that an
 * animation which could stop somewhere else is never a second answer about one spin. Every ordinary
 * way of ending it therefore hands something over: the animation finishing, the window being closed,
 * and the player disconnecting, which Paper turns into an {@code InventoryCloseEvent}. All three
 * were watched working on 2026-09-06 - a kick one second into the animation paid sixteen oak
 * saplings.
 *
 * <p>A <b>server shutdown</b> is the one that did not. Paper disables plugins before it saves and
 * disconnects players, so the close event arrives with no listener registered and the animation's
 * own chain simply stops at its next tick. The spin was gone and the player had nothing
 * (finding 136). At disable the players are still online, which is what makes handing the prize
 * over there both possible and enough - {@code Saving players} follows a few lines later and writes
 * it to disk.
 *
 * <h2>Why a text search</h2>
 * The subject is one call inside {@code onDisable}, and reaching {@code onDisable} needs a server.
 * The same reason every wiring test in this module is one.
 */
class SpinSurvivesShutdownTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("disable hands over any prize whose animation is still running")
    void anInFlightSpinIsPaidAtDisable() {
        final String source = read();

        final int step = source.indexOf("quietly(\"wheel.payOutInFlight\"");
        assertTrue(step > 0,
                "nothing pays out a spin whose animation is still running when the server stops -"
                        + " the spin is spent in SQL before the first frame, so the player would"
                        + " simply have lost it");

        final int pool = source.indexOf("quietly(\"database.close\"") > 0
                ? source.indexOf("quietly(\"database.close\"")
                : source.indexOf("database.close");
        if (pool > 0) {
            assertTrue(step < pool,
                    "the payout runs after the database pool is closed, which is where the refund"
                            + " branch inside it would have nothing left to write through");
        }
        assertTrue(source.contains("wheel.finish(player, false)"),
                "the payout does not go through WheelGui#finish, which is the one-shot latch that"
                        + " keeps a wheel from paying twice");
    }

    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            final Path source = candidate.resolve(PLUGIN);
            assertTrue(Files.isRegularFile(source), PLUGIN + " no longer exists");
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + PLUGIN, e);
        }
    }
}
