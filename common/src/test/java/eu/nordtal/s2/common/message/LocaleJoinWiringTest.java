package eu.nordtal.s2.common.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every Paper backend actually asks {@link PlayerLocales} for the language at join, and lets go
 * of it at quit.
 *
 * <h2>Why this is a text search and not a real test</h2>
 * The same reason {@code AdminWatchWiringTest} is: what it protects cannot be reached from a JVM
 * with no server in it. {@link PlayerLocales} is covered properly by {@code PlayerLocalesTest} -
 * the join, the fallback, the quit. What no unit test can reach is <b>whether anything calls it</b>.
 *
 * <p>That is not a hypothetical gap either. {@code smp} built a {@link PlayerLocales}, handed it to
 * fifteen classes, and called {@code joinAsync} from none of them - so {@code of()} answered
 * English for every player, and it was found on 2026-09-05 by a German account reading {@code /smp}
 * in English on the local stack, a minute after the proxy had answered {@code /phase} in German.
 * Both modules were internally consistent; the language was wrong only at the seam, and a season
 * in the wrong language looks exactly like one whose players all chose English.</p>
 */
class LocaleJoinWiringTest {

    /** Each backend's join/quit listener - the one file per module where the session begins. */
    private static final List<String> PRESENCE_LISTENERS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/listener/PresenceListener.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/PresenceListener.java");

    @Test
    @DisplayName("every backend loads the language at join, off the main thread, and forgets it at quit")
    void allThreeJoinAndQuit() throws IOException {
        for (final String relative : PRESENCE_LISTENERS) {
            final String text = read(relative);
            assertTrue(text.contains("locales.joinAsync("),
                    relative + " never calls PlayerLocales#joinAsync, so of() answers English for"
                            + " every player on this server for the whole session. docs/i18n.md's"
                            + " whole design rests on this one call per join.");
            assertFalse(text.contains("locales.join("),
                    relative + " calls the blocking PlayerLocales#join on what is presumably the"
                            + " main thread - one database round trip per login, and the pool's"
                            + " whole connection timeout with the server stopped behind it on a"
                            + " database that has stopped answering. joinAsync is the only form"
                            + " a Paper plugin may use (decided 2026-09-01).");
            assertTrue(text.contains("locales.quit("),
                    relative + " never calls PlayerLocales#quit, so the map grows by one entry per"
                            + " login for the life of the process.");
        }
    }

    @Test
    @DisplayName("the SMP's join line waits for the language it is about")
    void theJoinLineIsNotSentFromAJoinHandler() throws IOException {
        // The consequence of the rule above, and the one message it costs something. Every other
        // surface on the SMP is redrawn on a timer and picks the language up by itself; the join
        // line has exactly one moment, and at that moment the query is still in flight - so the
        // German player on the local stack was told "hmtill joined." under a German HUD
        // (finding 116). It is announced from the locale callback instead.
        final String lines = read("smp/src/main/java/eu/nordtal/s2/smp/chat/SystemLines.java");
        final int join = lines.indexOf("public void onJoin(");
        assertTrue(join >= 0, "SystemLines has no onJoin");
        final String body = lines.substring(join, lines.indexOf("\n    }\n", join));
        assertFalse(body.contains("broadcast("),
                "SystemLines#onJoin broadcasts the join line at join, which is one moment before"
                        + " the joining player's language is known");
        assertTrue(lines.contains("public void announceJoin("),
                "SystemLines has no announceJoin for the callback to call");
        assertTrue(read("smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java")
                        .contains("lines.announceJoin("),
                "nothing calls announceJoin, so the join line is never printed at all - which is"
                        + " what makes this worth a test rather than a comment");
    }

    private static String read(final String relative) throws IOException {
        final Path path = repositoryRoot().resolve(relative);
        assertTrue(Files.isRegularFile(path), relative + " no longer exists - if a module was renamed"
                + " this list has to move with it, because a missing file is a check that silently"
                + " stops running");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }
}
