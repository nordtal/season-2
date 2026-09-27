package eu.nordtal.s2.smp.npc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The label the config promises is the label a player sees.
 *
 * The name is set and the description cleared to {@code null}, since an empty one still draws a blank line.
 */
class SpawnNpcLabelTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java";

    @Test
    void theNameIsAVisibleCustomName() {
        final String source = read();

        assertTrue(
                source.contains("mannequin.customName("),
                "the NPC's configured name is not set as the entity's custom name, which is the"
                        + " one label that was watched being drawn on a real client");
        assertTrue(
                source.contains("mannequin.setCustomNameVisible(true)"),
                "the NPC has a custom name that is never made visible, which is the default and"
                        + " looks exactly like having no name at all");
        assertTrue(
                source.contains("mannequin.setDescription(null)"),
                "the NPC's mannequin description is not cleared with null. Left at its default,"
                        + " vanilla draws it as a second smaller line reading the English word"
                        + " \"NPC\" under the name - two labels, and the lower one in one language"
                        + " for every reader. Cleared with Component.empty() instead, the line is"
                        + " drawn blank rather than not drawn, leaving a narrow empty strip above"
                        + " the figure instead of a second label");
    }

    /** Anchored on the directory holding {@code settings.gradle.kts}, the way every reader here is. */
    private static String read() {
        try {
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
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
