package eu.nordtal.s2.hungergames.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * That a body's death reaches the kill feed, and that both sentences exist to reach it with.
 *
 * Why a text search: the same reason `AdminWatchWiringTest` is one: what has to be protected is
 * whether anything <em>calls</em> the announcement, and reaching {@code onMarkerDeath} needs a
 * running server, an armor stand and a damage source.
 *
 * It is not hypothetical, and the shape of the bug is this repository's most repeated one.
 * `onMarkerDeath` once booked the death, cleared the protection, played the sound and removed
 * the body - everything except saying so. Vanilla writes no death message for an
 * {@code EntityDeathEvent}, so {@code SystemLines#onDeath} never saw it, and the one elimination the
 * victim is not present for was also the one nobody else was told about. Every half of the wire
 * existed; nothing joined them.
 */
class BodyDeathIsAnnouncedTest {

    @Test
    void aBodysDeathIsAnnouncedFromTheHandlerThatBooksIt() throws IOException {
        final String source =
                read("hunger-games/src/main/java/eu/nordtal/s2/hungergames/" + "listener/CombatListener.java");

        final int marker = source.indexOf("public void onMarkerDeath");
        assertTrue(
                marker > 0,
                "onMarkerDeath is gone - if it was renamed, this test moves with it,"
                        + " because a check that cannot find its subject silently stops running");

        // Ends at the helper's declaration: a slice reaching past it goes green even if the call is gone.
        final int helper = source.indexOf("private void announceBodyDeath");
        assertTrue(
                helper > marker,
                "announceBodyDeath is gone or has moved above onMarkerDeath;"
                        + " this test brackets the handler and needs the helper below it");
        final String body = source.substring(marker, helper);
        assertTrue(
                body.contains("announceBodyDeath("),
                "onMarkerDeath books the death without announcing it. That is the state this file"
                        + " was in until 2026-09-09: the sound plays, the roster updates, the body"
                        + " disappears, and the kill feed says nothing at all.");
    }

    @Test
    void theTwoSentencesAreTwoBecauseByNobodyIsNotASentence() throws IOException {
        for (final String language : new String[] {"en", "de"}) {
            final String bundle =
                    read("hunger-games/src/main/resources/messages/hunger-games/" + language + ".properties");
            // Two keys, not one with an empty slot: a border death and a kill are different sentences.
            assertTrue(bundle.contains("hg.death.body="), language + " has no hg.death.body");
            assertTrue(bundle.contains("hg.death.body.by="), language + " has no hg.death.body.by");
        }
    }

    private static String read(final String relative) throws IOException {
        final Path path = repositoryRoot().resolve(relative);
        assertTrue(Files.isRegularFile(path), relative + " no longer exists");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
