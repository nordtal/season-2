package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the admin check behind a command tree is a cache and never a query.
 *
 * <h2>Why this is not obvious and was wrong twice</h2>
 * Brigadier evaluates a node's {@code requires} predicate <b>while building the command tree it
 * sends to a client</b>, on a thread that must not block - once per player, per rebuild. A
 * {@code dao.isAdmin(uuid)} there is a database round trip inside that, and it looks perfectly
 * ordinary in the constructor call that passes it. It was written into {@code hunger-games} during
 * the fold and caught by re-reading rather than by anything failing.
 *
 * <p>The second way to get it wrong is subtler and cost limbo its whole command: passing
 * {@code FullServerAdmission}, which does hold an admin flag - but only fills it at pre-login
 * <em>when the server is near its cap</em>. On limbo, which every login on the network crosses and
 * which is never near its cap, it would have answered "nobody is an admin" for ever, silently.</p>
 *
 * <p>{@code AdminWatch#isAdmin} is the source that is right everywhere: it is the same set the
 * operator grant is applied from, so a command tree and {@code ops.json} cannot disagree about who
 * is an admin either.</p>
 */
class AdminSourceTest {

    /** Each Paper plugin, and the in-memory source it is allowed to use. */
    private static final Map<String, String> SOURCES = Map.of(
            // smp holds the flag in Identities for the nametag composition anyway, and that cache is
            // fed by the same AdminWatch.
            "smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java",
            "identities.of(mcUuid).admin()",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java",
            "adminWatch::isAdmin",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "adminWatch::isAdmin");

    /** Ways of answering the question that must not appear where a command tree is built. */
    private static final List<String> FORBIDDEN = List.of(
            "dao.isAdmin(", "access.admins()", "admission.admits(");

    @Test
    @DisplayName("every command tree reads an in-memory admin source")
    void theSourceIsACache() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Map.Entry<String, String> entry : SOURCES.entrySet()) {
            final String source = read(entry.getKey());
            if (!source.contains(entry.getValue())) {
                wrong.add(entry.getKey() + " no longer passes " + entry.getValue()
                        + " as its admin source");
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("no command tree answers the admin question with a query or with the fullness cache")
    void nothingQueriesInRequires() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final String file : SOURCES.keySet()) {
            final String source = read(file);
            final int adapter = source.indexOf("new PaperCommands(");
            if (adapter < 0) {
                continue;
            }
            // The constructor call itself: that is where the predicate is handed over, and where
            // both mistakes were made.
            final String call = source.substring(adapter,
                    Math.min(source.length(), adapter + 900));
            for (final String forbidden : FORBIDDEN) {
                if (call.contains(forbidden)) {
                    wrong.add(file + " passes " + forbidden + " into PaperCommands");
                }
            }
        }
        assertEquals(List.of(), wrong,
                "a command tree's requires predicate either queries the database on the main thread"
                        + " or reads FullServerAdmission, which is only warmed on a server near its"
                        + " cap and answers false everywhere else");
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        final Path source = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(source), relative + " no longer exists");
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
