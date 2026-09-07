package eu.nordtal.s2.smp.headstart;

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
 * The head start has a caller.
 *
 * <h2>Why this is worth a test of its own</h2>
 * This module has been bitten twice by the same shape in one week. {@code AdminOperators#refresh}
 * was written for a live revocation and then had no caller at all for a day (which is why
 * {@code AdminWatchWiringTest} exists), and the hunger games HUD's two counters were fed by setters
 * nothing anywhere called, so every participant read <em>Alive 0, Dead 0</em> for the whole of every
 * game (finding 139).
 *
 * <p>The head start is worse than either, because <b>nothing can ever notice</b>. It fires once per
 * season, for one person, on one join. There is no second chance to spot it missing, no screen that
 * looks wrong in the meantime, and the class can be complete, tested against a real database and
 * entirely dead. It was: {@code hg-winner-aura}, {@code hg-winner-items},
 * {@code AuraReason.HG_WINNER} and {@code smp_player.hg_winner_reward_granted} were all written on
 * 2026-09-01 and had <b>no reader at all</b> until 2026-09-07 - a whole configured, migrated,
 * documented feature that did nothing, and no test in 1305 said a word.</p>
 */
class HeadStartIsWiredTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("SmpPlugin registers the head start listener")
    void thePluginRegistersIt() {
        final String source = read(PLUGIN);

        assertTrue(source.contains("new HeadStart("),
                "nothing constructs HeadStart, so the start event's winner is paid nothing - and"
                        + " the only way to find that out is to win the start event");
        assertTrue(source.contains("registerEvents(") && source.contains("HeadStart"),
                "HeadStart is a Listener and does its whole job from PlayerJoinEvent; constructing"
                        + " one without registering it is the same as not having it");
    }

    @Test
    @DisplayName("the head start reads both halves of its config")
    void bothConfiguredHalvesAreRead() {
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/headstart/HeadStart.java");

        assertTrue(source.contains("hgWinnerAura()"),
                "config.yml#hg-winner-aura is the number on the leaderboard, which is the whole"
                        + " prize - aura buys nothing, so recognition is all there is");
        assertTrue(source.contains("hgWinnerItems()"),
                "config.yml#hg-winner-items is the other half, and a head start that pays only the"
                        + " number would look exactly like one that works");
    }

    @Test
    @DisplayName("the winner is resolved from their uuid at delivery, not carried across the hop")
    void aReconnectStillGetsTheItems() {
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/headstart/HeadStart.java");

        assertTrue(source.contains("Bukkit.getPlayer(mcUuid)"),
                "the delivery has to resolve the CURRENT session. Between the claim committing and"
                        + " the main-thread task running, the winner can reconnect - and a Player"
                        + " captured at join answers isOnline() false for ever after that, so the"
                        + " head start would be booked, the flag set, and the items left to the"
                        + " manual path for somebody standing right there. Found by review,"
                        + " 2026-09-08.");
        assertFalse(source.contains("private void hand(final Player"),
                "hand() must not take a Player: the whole point is that the instance captured at"
                        + " join is the one that goes stale");
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
