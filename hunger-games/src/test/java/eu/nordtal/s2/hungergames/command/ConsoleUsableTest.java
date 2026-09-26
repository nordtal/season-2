package eu.nordtal.s2.hungergames.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * That {@code /hg} can still be run from the console.
 *
 * Why a text search, and why it is worth having: the same reason {@code AdminWatchWiringTest} and
 * {@code ReadinessWiringTest} are text searches: what it protects cannot be reached from a JVM with
 * no server in it. Building the tree needs
 * {@code io.papermc.paper.command.brigadier.Commands}, running a handler needs a
 * {@code CommandSourceStack}, and the thing that has to hold - <em>the console reaches the handler
 * at all</em> - is a property of a {@code requires} predicate evaluated by a running server.
 *
 * The regression is not hypothetical. Every subcommand once carried
 * {@code .requires(source -> source.getSender() instanceof Player)} and every handler opened with a
 * cast to {@code Player}, so the console could run none of {@code /hg} - and the start of the
 * season's flagship event therefore depended on one client being able to connect and stay connected,
 * with no second path and nothing anywhere saying so. Re-adding one of those lines while
 * refactoring is a two-character change that would restore exactly that state, silently.
 *
 * {@code /hg ready} is the deliberate exception and is named here rather than excluded quietly:
 * it marks <em>the sender</em> ready for a game, and the console is registered for none.
 */
class ConsoleUsableTest {

    private static final String SOURCE =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java";

    /** The one subcommand that may refuse the console, and the reason it may. */
    private static final String PLAYER_ONLY = "ready";

    @Test
    void exactlyOneSubcommandGatesOnTheSenderBeingAPlayer() throws IOException {
        final String text = read();

        // The Brigadier gate only: other mentions of the type here would pass or fail for unrelated reasons.
        final int gates = occurrences(text, ".requires(source -> source.getSender() instanceof Player)");

        assertEquals(
                1,
                gates,
                "exactly one subcommand may refuse the console - /hg " + PLAYER_ONLY + ", because it"
                        + " marks the SENDER ready and the console is registered for no game. Any"
                        + " other gate means a subcommand has been closed to the console again,"
                        + " which is the state /hg shipped in until 2026-09-04 and which nothing"
                        + " else would report.");

        assertEquals(
                1,
                occurrences(text, "(Player) context.getSource().getSender()"),
                "only /hg " + PLAYER_ONLY + "'s handler may cast its sender to a Player. Every other"
                        + " handler takes a NordtalUser, which is what the console arrives as.");
    }

    @Test
    void theAdminSubcommandsAreDeclaredNotHandGatedSoTheConsoleReachesThem() throws IOException {
        // No hand-built tree for the admin commands: the shared PaperCommands gate is what admits the console.
        final String text = read();

        assertTrue(
                text.contains("HungerGamesCommands.all()"),
                SOURCE + " no longer registers the declared /hg commands, so it has gone back to"
                        + " building its own tree - which is where the player-only gate lived");
        assertTrue(
                text.contains("commands.local(command, effects)"),
                SOURCE + " does not hand its commands to PaperCommands, whose admin check accepts"
                        + " the console. A tree built here would have to repeat that check, and"
                        + " repeating it is how it came to be wrong.");
        // extraOpen, not extra: every extra subtree is admin-gated, and this one must not be.
        assertEquals(
                1,
                occurrences(text, "commands.extraOpen(\"hg\""),
                "/hg ready is the one subcommand hung on by hand, and the one that any player may"
                        + " use. A second open extra is a command that has escaped both the"
                        + " declaration and the admin gate.");
        assertEquals(
                0,
                occurrences(text, "commands.extra(\"hg\""),
                "/hg ready was hung on with the gated extra(), which would hide it from every"
                        + " player - and the lobby's ready button runs exactly that path");
    }

    @Test
    void hgReadyKeepsTheExactPathTheLobbysClickEventRuns() throws IOException {
        // Lobby#broadcast runs this exact literal; a rename here does not fail anything, the button just stops working.
        assertTrue(
                read().contains("Commands.literal(\"ready\")"),
                "/hg ready was renamed or moved, and the lobby's ready button runs that literal"
                        + " path - it would silently do nothing");
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
