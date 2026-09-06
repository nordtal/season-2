package eu.nordtal.s2.smp.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * description is explicitly emptied (finding 127).
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

        assertTrue(source.contains("mannequin.customName("),
                "the NPC's configured name is not set as the entity's custom name, which is the"
                        + " one label that was watched being drawn on a real client");
        assertTrue(source.contains("mannequin.setCustomNameVisible(true)"),
                "the NPC has a custom name that is never made visible, which is the default and"
                        + " looks exactly like having no name at all");
        assertTrue(source.contains("mannequin.setDescription(Component.empty())"),
                "the NPC's mannequin description is left at its default, which vanilla draws as a"
                        + " second smaller line reading the English word \"NPC\" under the name -"
                        + " two labels, and the lower one in one language for every reader");
    }

    /** Anchored on the directory holding {@code settings.gradle.kts}, the way every reader here is. */
    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
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
