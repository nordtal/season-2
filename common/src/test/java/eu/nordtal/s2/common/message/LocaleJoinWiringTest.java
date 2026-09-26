package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks that every Paper backend asks {@link PlayerLocales} for the language at join and releases it at quit.
 *
 * A text search, because whether anything calls it cannot be reached from a JVM without a server.
 */
class LocaleJoinWiringTest {

    /** Each backend's join/quit listener - the one file per module where the session begins. */
    private static final List<String> PRESENCE_LISTENERS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/listener/PresenceListener.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/PresenceListener.java");

    @Test
    void everyBackendLoadsTheLanguageAtJoinOffTheMainThreadAndForgetsItAtQuit() throws IOException {
        for (final String relative : PRESENCE_LISTENERS) {
            final String text = read(relative);
            assertTrue(
                    text.contains("locales.joinAsync("),
                    relative + " never calls PlayerLocales#joinAsync, so of() answers English for"
                            + " every player on this server for the whole session. docs/i18n.md's"
                            + " whole design rests on this one call per join.");
            assertFalse(
                    text.contains("locales.join("),
                    relative + " calls the blocking PlayerLocales#join on what is presumably the"
                            + " main thread - one database round trip per login, and the pool's"
                            + " whole connection timeout with the server stopped behind it on a"
                            + " database that has stopped answering. joinAsync is the only form"
                            + " a Paper plugin may use (decided 2026-09-01).");
            assertTrue(
                    text.contains("locales.quit("),
                    relative + " never calls PlayerLocales#quit, so the map grows by one entry per"
                            + " login for the life of the process.");
        }
    }

    @Test
    void theJoinLineWaitsForTheLanguageItIsAboutOnBothServersThatPrintOne() throws IOException {
        // The join line has one moment, while the query is still in flight, so it is announced from the callback.
        final String lines = read("paper-common/src/main/java/eu/nordtal/s2/papercommon/chat/SystemLines.java");
        final int join = lines.indexOf("public void onJoin(");
        assertTrue(join >= 0, "SystemLines has no onJoin");
        final String body = lines.substring(join, lines.indexOf("\n    }\n", join));
        assertFalse(
                body.contains("broadcast("),
                "SystemLines#onJoin broadcasts the join line at join, which is one moment before"
                        + " the joining player's language is known");
        assertTrue(
                lines.contains("public void announceJoin("),
                "SystemLines has no announceJoin for the callback to call");

        for (final String presence : List.of(
                "smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java",
                "hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/PresenceListener.java")) {
            final String source = read(presence);
            assertTrue(
                    source.contains("lines.announceJoin("),
                    presence + " never calls announceJoin, so that server prints no join line at"
                            + " all - which is what makes this worth a test rather than a comment");
            // Inside the locale callback, which from here can only be checked as coming after joinAsync.
            assertTrue(
                    source.indexOf("joinAsync(") < source.indexOf("lines.announceJoin("),
                    presence + " announces the join line before joinAsync, which is the one moment"
                            + " at which the joining player's own language is not known yet");
        }
    }

    private static String read(final String relative) throws IOException {
        final Path path = repositoryRoot().resolve(relative);
        assertTrue(
                Files.isRegularFile(path),
                relative + " is missing - a renamed module has to move with this list, because a"
                        + " missing file is a check that silently stops running");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }
}
