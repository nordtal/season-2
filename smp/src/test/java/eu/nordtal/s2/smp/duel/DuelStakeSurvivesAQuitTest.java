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
 * <h2>What this is about</h2>
 * {@code docs/smp.md} states it in one sentence - "<b>Disconnecting is a defeat and the aura is
 * booked.</b> Otherwise logging out is a free escape from losing." It was not true, and the way it
 * failed is why this is a test rather than a comment.
 *
 * <p>{@code Duels#book} resolved both fighters' discord ids out of {@code Identities}, which is a
 * per-session cache. {@code JoinGate}'s quit handler clears it, and {@code JoinGate} is registered
 * before {@code DuelListener} in {@code SmpPlugin}, so by the time the duel's own quit handler ran,
 * the leaver's id was gone. {@code book} took its early return and wrote <b>nothing</b> - not the
 * loser's stake, and not the winner's either. Everything visible still happened: the arena came
 * down, the survivor was returned to the spawn with their own inventory and a "you won" title, and
 * only the number was missing. Measured on the local stack, 2026-09-07 (finding 137): a mid-fight
 * disconnect left both aura totals untouched and {@code smp_aura_event} with no row.
 *
 * <h2>Why the fix is a captured value and not a reordering</h2>
 * Moving {@code DuelListener} ahead of {@code JoinGate} would also have worked, today. It would have
 * made whether a duel pays out depend on the order two unrelated listeners happen to be registered
 * in - a fact nothing states, nothing checks, and any later edit to {@code onEnable} can reverse
 * without touching a line of duel code. A duel's two participants cannot change once it is running,
 * so the ids are knowable when it starts; taking them then removes the ordering question instead of
 * answering it.
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
