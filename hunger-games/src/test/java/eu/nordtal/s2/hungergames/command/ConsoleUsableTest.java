package eu.nordtal.s2.hungergames.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code /hg} can still be run from the console.
 *
 * <h2>Why a text search, and why it is worth having</h2>
 * The same reason {@code AdminWatchWiringTest} and {@code ReadinessWiringTest} are text searches:
 * what it protects cannot be reached from a JVM with no server in it. Building the tree needs
 * {@code io.papermc.paper.command.brigadier.Commands}, running a handler needs a
 * {@code CommandSourceStack}, and the thing that has to hold - <em>the console reaches the handler
 * at all</em> - is a property of a {@code requires} predicate evaluated by a running server.
 *
 * <p>The regression is not hypothetical. Until 2026-09-04 every subcommand carried
 * {@code .requires(source -> source.getSender() instanceof Player)} and every handler opened with a
 * cast to {@code Player}, so the console could run none of {@code /hg} - and the start of the
 * season's flagship event therefore depended on one client being able to connect and stay connected,
 * with no second path and nothing anywhere saying so. Re-adding one of those lines while
 * refactoring is a two-character change that would restore exactly that state, silently.</p>
 *
 * <p>{@code /hg ready} is the deliberate exception and is named here rather than excluded quietly:
 * it marks <em>the sender</em> ready for a game, and the console is registered for none.</p>
 */
class ConsoleUsableTest {

    private static final String SOURCE =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java";

    /** The one subcommand that may refuse the console, and the reason it may. */
    private static final String PLAYER_ONLY = "ready";

    @Test
    @DisplayName("exactly one subcommand gates on the sender being a player")
    void nothingElseRefusesTheConsole() throws IOException {
        final String text = read();

        // The Brigadier gate specifically, not any mention of the type: this file also tests the
        // sender to build a console user (which is what opened the command up) and describes the
        // old behaviour in its own javadoc. Counting either of those would make the test pass or
        // fail for reasons that have nothing to do with who can run the command - which is how a
        // check like this quietly stops meaning anything.
        final int gates = occurrences(text, ".requires(source -> source.getSender() instanceof Player)");

        assertEquals(1, gates,
                "exactly one subcommand may refuse the console - /hg " + PLAYER_ONLY + ", because it"
                        + " marks the SENDER ready and the console is registered for no game. Any"
                        + " other gate means a subcommand has been closed to the console again,"
                        + " which is the state /hg shipped in until 2026-09-04 and which nothing"
                        + " else would report.");

        assertEquals(1, occurrences(text, "(Player) context.getSource().getSender()"),
                "only /hg " + PLAYER_ONLY + "'s handler may cast its sender to a Player. Every other"
                        + " handler takes a NordtalUser, which is what the console arrives as.");
    }

    @Test
    @DisplayName("the console reaches a handler as a NordtalUser rather than being cast to a Player")
    void theSenderIsAbstracted() throws IOException {
        final String text = read();

        assertTrue(text.contains("PaperUser.console("),
                SOURCE + " no longer builds a console user, so /hg is player-only again");
        assertTrue(text.contains("Consumer<NordtalUser> action"),
                SOURCE + " no longer hands its handlers a NordtalUser. A handler that takes a Player"
                        + " cannot be reached by the console, which is how this was broken before.");
    }

    private static int occurrences(final String text, final String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static String read() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        final Path source = candidate.resolve(SOURCE);
        assertTrue(Files.isRegularFile(source), SOURCE + " no longer exists");
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
