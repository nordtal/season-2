package eu.nordtal.s2.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A wheel still spinning when the server goes down pays out.
 *
 * Paper disables plugins before disconnecting players, so {@code onDisable} must hand the prize over itself.
 */
class SpinSurvivesShutdownTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    void anInFlightSpinIsPaidAtDisable() {
        final String source = read();

        final int step = source.indexOf("quietly(\"wheel.payOutInFlight\"");
        assertTrue(
                step > 0,
                "nothing pays out a spin whose animation is still running when the server stops -"
                        + " the spin is spent in SQL before the first frame, so the player would"
                        + " simply have lost it");

        final int pool = source.indexOf("quietly(\"database.close\"") > 0
                ? source.indexOf("quietly(\"database.close\"")
                : source.indexOf("database.close");
        if (pool > 0) {
            assertTrue(
                    step < pool,
                    "the payout runs after the database pool is closed, which is where the refund"
                            + " branch inside it would have nothing left to write through");
        }
        assertTrue(
                source.contains("wheel.finish(player, false)"),
                "the payout does not go through WheelGui#finish, which is the one-shot latch that"
                        + " keeps a wheel from paying twice");
    }

    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
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
