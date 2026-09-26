package eu.nordtal.s2.smp.npc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The label the config promises is the label a player sees.
 *
 * <h2>The failure it exists for</h2>
 * {@code config.yml#npc.name} defaults to {@code Nordtal} and its comment reads "The label above
 * it". Until 2026-09-06 the figure carried that text in {@code Mannequin#setDescription} and
 * nothing else, and the figure standing in the tavern that afternoon had no label at all - while
 * {@code data get entity} reported {@code description: "Nordtal"}, the config was right and the
 * plugin logged nothing. Every check that does not involve a client agreed with the code.
 *
 * <p>Both fields render, one above the other; that was measured the same day by renaming the live
 * entity and then by removing each in turn. The description is the lower, smaller line and its
 * default is the literal English word "NPC", so leaving it alone labels the figure twice and the
 * second label is not translated. Hence both assertions: the ordinary label is set, and the
 * description is explicitly cleared (finding 127).
 *
 * <h2>Cleared means {@code null}, not an empty component</h2>
 * This asked for {@code setDescription(Component.empty())} until 2026-09-20, and Till changed the
 * code to {@code null} after looking at the figure: an empty component is still a component, and
 * the client reserves the narrow second line for it - a blank strip above the name where there
 * should be nothing. That is a client observation and no test on this side could have had it,
 * which is exactly why the assertion follows the code here rather than the other way round. What
 * has not changed is the thing being guarded: the default must be replaced, whatever with.
 *
 * <h2>Why a text search</h2>
 * Spawning a {@code Mannequin} needs a world, and the decision here has no arithmetic in it: it is
 * one call, made or not made. The same reason {@code SmpCommandWiringTest} is one.
 */
class SpawnNpcLabelTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java";

    @Test
    @DisplayName("a configured name is drawn above the figure, not only in its item description")
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
                        + " drawn blank rather than not drawn, which is the narrow empty strip"
                        + " Till saw above the figure on 2026-09-20");
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
