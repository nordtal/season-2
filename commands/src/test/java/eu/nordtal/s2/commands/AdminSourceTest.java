package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Holds the admin check behind a command tree to a cache, never a query.
 *
 * Brigadier evaluates {@code requires} per player while building the tree, on a thread that must not block.
 */
class AdminSourceTest {

    /** Each Paper plugin, and the in-memory source it is allowed to use. */
    private static final Map<String, String> SOURCES = Map.of(
            // smp holds the flag in Identities for the nametag composition anyway.
            "smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java",
            "identities.of(mcUuid).admin()",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java",
            "adminWatch::isAdmin",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "adminWatch::isAdmin");

    /** Ways of answering the question that must not appear where a command tree is built. */
    private static final List<String> FORBIDDEN = List.of("dao.isAdmin(", "access.admins()", "admission.admits(");

    @Test
    void everyCommandTreeReadsAnInMemoryAdminSource() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Map.Entry<String, String> entry : SOURCES.entrySet()) {
            final String source = read(entry.getKey());
            if (!source.contains(entry.getValue())) {
                wrong.add(entry.getKey() + " does not pass " + entry.getValue() + " as its admin source");
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void nothingQueriesInRequires() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final String file : SOURCES.keySet()) {
            final String source = read(file);
            final int adapter = source.indexOf("new PaperCommands(");
            if (adapter < 0) {
                continue;
            }
            // The constructor call itself: that is where the predicate is handed over.
            final String call = source.substring(adapter, Math.min(source.length(), adapter + 900));
            for (final String forbidden : FORBIDDEN) {
                if (call.contains(forbidden)) {
                    wrong.add(file + " passes " + forbidden + " into PaperCommands");
                }
            }
        }
        assertEquals(
                List.of(),
                wrong,
                "a command tree's requires predicate either queries the database on the main thread"
                        + " or reads FullServerAdmission, which is only warmed on a server near its"
                        + " cap and answers false everywhere else");
    }

    @Test
    void anOpenCommandIsNotGatedByItsRoot() throws IOException {
        // Brigadier's requires gates a whole subtree, and both adapters put one on every child of a root.
        for (final String relative : List.of(
                "paper-common/src/main/java/eu/nordtal/s2/papercommon/command/PaperCommands.java",
                "proxy/src/main/java/eu/nordtal/s2/proxy/command/VelocityCommands.java")) {
            final String source = read(relative);
            assertTrue(
                    source.contains("builder.then(gate == null ? sub : sub.requires(gate));"),
                    relative + " gates every child of a root, so an open command declared under an"
                            + " admin root is invisible to the people it exists for");
            assertTrue(
                    source.contains("declaration().adminOnly()"),
                    relative + " decides the gate without asking the declaration");
            assertTrue(
                    source.contains("declaration().surfaces().contains(") && source.contains("Surface.GAME)"),
                    relative + " decides the gate without asking which surfaces the declaration"
                            + " carries - a command off Surface.GAME is not registered for a player"
                            + " at all");
        }
        // Nothing in the catalogue is open under a root any more.
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        final Path source = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(source), relative + " is missing");
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
