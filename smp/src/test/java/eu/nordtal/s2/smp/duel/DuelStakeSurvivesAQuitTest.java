package eu.nordtal.s2.smp.duel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging out of a duel has to cost the same as losing one.
 *
 * <p>Disconnecting is a defeat and the aura is booked, otherwise logging out is a free escape from
 * losing. The trap: {@code Identities} is a per-session cache that {@code JoinGate}'s quit handler
 * clears, and {@code JoinGate} is registered first - so a booking that reads ids from it at settle
 * time finds nothing and silently writes neither stake.
 *
 * <p>The ids are therefore captured when the duel starts, rather than the two listeners being
 * reordered: registration order is a fact nothing states and any later edit to {@code onEnable} can
 * reverse.
 */
class DuelStakeSurvivesAQuitTest {

    private static final String DUELS = "smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java";

    @Test
    @DisplayName("the duel carries its fighters' discord ids, captured when it starts")
    void theDuelCarriesTheIds() {
        final String source = read(DUELS);
        assertTrue(source.contains("Map<UUID, String> discordIds"),
                "ActiveDuel has to carry the two discord ids itself; without them the booking has"
                        + " nowhere to read an id from once a fighter has disconnected");
        assertTrue(source.contains("identities.discordIdOf(first.getUniqueId())")
                        && source.contains("identities.discordIdOf(second.getUniqueId())"),
                "the ids have to be read while both fighters are still online - that is, where the"
                        + " ActiveDuel is built, not where it is settled");
    }

    @Test
    @DisplayName("the booking never asks Identities, because a quit has already cleared it")
    void theBookingDoesNotAskTheCache() {
        final String body = methodBody(read(DUELS), "private void book(");
        assertFalse(body.contains("identities.discordIdOf"),
                "book() read the discord ids out of Identities, which JoinGate's quit handler has"
                        + " already cleared by the time a mid-fight disconnect gets here - so the"
                        + " loser paid nothing and the winner was paid nothing, silently. Take them"
                        + " from the duel (ActiveDuel#discordIds) instead.");
        assertTrue(body.contains("duel.discordIds()"),
                "book() has to take the ids from the duel it is settling");
    }

    /** The text between a method's signature and the first line that closes it at its own indent. */
    private static String methodBody(final String source, final String signature) {
        final int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException(signature + " is gone from " + DUELS
                    + " - this test names it directly and has to be pointed at its replacement");
        }
        final int end = source.indexOf("\n    }", start);
        if (end < 0) {
            throw new IllegalStateException("no close found for " + signature);
        }
        return source.substring(start, end);
    }

    private static String read(final String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }
}
