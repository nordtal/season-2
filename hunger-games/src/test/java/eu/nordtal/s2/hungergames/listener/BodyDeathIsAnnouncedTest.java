package eu.nordtal.s2.hungergames.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Checks that both sentences of a body's death are in both languages of the bundle.
 *
 * Vanilla writes no death message for an {@code EntityDeathEvent}; that {@code onMarkerDeath} announces it is
 * {@code :architecture}'s rule.
 */
class BodyDeathIsAnnouncedTest {

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
