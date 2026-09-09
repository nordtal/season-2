package eu.nordtal.s2.hungergames.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a body's death reaches the kill feed, and that both sentences exist to reach it with.
 *
 * <h2>Why a text search</h2>
 * The same reason `AdminWatchWiringTest` is one: what has to be protected is whether anything
 * <em>calls</em> the announcement, and reaching {@code onMarkerDeath} needs a running server, an
 * armor stand and a damage source.
 *
 * <p>It is not hypothetical, and the shape of the bug is this repository's most repeated one. Until
 * 2026-09-09 `onMarkerDeath` booked the death, cleared the protection, played the sound and removed
 * the body - everything except saying so. Vanilla writes no death message for an
 * {@code EntityDeathEvent}, so {@code SystemLines#onDeath} never saw it, and the one elimination the
 * victim is not present for was also the one nobody else was told about. Every half of the wire
 * existed; nothing joined them.</p>
 */
class BodyDeathIsAnnouncedTest {

    @Test
    @DisplayName("a body's death is announced, from the handler that books it")
    void theMarkerDeathAnnounces() throws IOException {
        final String source = read("hunger-games/src/main/java/eu/nordtal/s2/hungergames/"
                + "listener/CombatListener.java");

        final int marker = source.indexOf("public void onMarkerDeath");
        assertTrue(marker > 0, "onMarkerDeath is gone - if it was renamed, this test moves with it,"
                + " because a check that cannot find its subject silently stops running");

        // Ends at the helper's DECLARATION, not at the next method: the declaration contains the
        // string this test searches for, so a slice that reaches past it goes green even when
        // onMarkerDeath has stopped calling it - the same way an indexOf of -1 does.
        final int helper = source.indexOf("private void announceBodyDeath");
        assertTrue(helper > marker, "announceBodyDeath is gone or has moved above onMarkerDeath;"
                + " this test brackets the handler and needs the helper below it");
        final String body = source.substring(marker, helper);
        assertTrue(body.contains("announceBodyDeath("),
                "onMarkerDeath books the death without announcing it. That is the state this file"
                        + " was in until 2026-09-09: the sound plays, the roster updates, the body"
                        + " disappears, and the kill feed says nothing at all.");
    }

    @Test
    @DisplayName("the two sentences are two, because 'by nobody' is not a sentence")
    void bothKeysExistInBothLanguages() throws IOException {
        for (final String language : new String[]{"en", "de"}) {
            final String bundle = read("hunger-games/src/main/resources/messages/hunger-games/"
                    + language + ".properties");
            // Two keys rather than one with an empty slot: a body that fell to the border and a body
            // somebody killed are different sentences, and no bundle can make that choice for the
            // caller with a placeholder that is sometimes blank.
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
