package eu.nordtal.s2.smp.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.SoundsSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * That the ten sounds a fresh {@code sounds.yml} ships actually exist.
 *
 * Each {@code minecraft:} key is looked up as a {@code Sound} field by name, which needs no server.
 */
class SoundDefaultsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundDefaultsTest.class);

    @TempDir
    Path directory;

    @Test
    void everyDefaultKeyResolvesAgainstBukkitsSoundList() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        final List<String> problems = new ArrayList<>();

        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec sound = entryOf(category, spec);
            if (category == Feedback.STAGING) {
                // The one category shipping blank on purpose: a staged moment's sound has no chime to borrow yet.
                assertTrue(
                        sound.key() == null || sound.key().isBlank(),
                        "STAGING ships a sound. It is meant to ship blank until the pack has one -"
                                + " if that day has come, say so here rather than leaving this"
                                + " check believing something that is no longer true");
                continue;
            }
            if (sound.key() == null || sound.key().isBlank()) {
                problems.add(category + " ships without a sound - a fresh sounds.yml should carry a"
                        + " working vocabulary, and blanking a key is the operator's escape hatch"
                        + " rather than a default");
                continue;
            }
            if (!Key.parseable(sound.key())) {
                problems.add(category + " ships '" + sound.key() + "', which is not a namespaced key");
                continue;
            }
            final Key key = Key.key(sound.key());
            if (!Key.MINECRAFT_NAMESPACE.equals(key.namespace())) {
                continue;
            }
            final String field = key.value().replace('.', '_').toUpperCase(Locale.ROOT);
            try {
                final var _ = org.bukkit.Sound.class.getField(field);
            } catch (final NoSuchFieldException missing) {
                problems.add(category + " ships '" + sound.key() + "', which this Paper API has no"
                        + " sound for (looked for the constant " + field + "). Either Minecraft"
                        + " renamed it, or it was mistyped - the category would be silent in game"
                        + " and nothing would say so");
            }
        }

        assertEquals(List.of(), problems);
    }

    /** The values survive being written to a file and read back, nesting and floats included. */
    @Test
    void theSoundsBlockSurvivesTheRoundTrip() throws Exception {
        final SoundsSpec written = Configs.sounds(directory, LOGGER).get();
        final SoundsSpec reread = Configs.sounds(directory, LOGGER).get();

        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec before = entryOf(category, written);
            final SoundsSpec.SoundSpec after = entryOf(category, reread);
            assertEquals(before.key(), after.key(), category.name());
            assertEquals(before.volume(), after.volume(), category.name());
            assertEquals(before.pitch(), after.pitch(), category.name());
        }
    }

    /** And the parsed form the plugin actually uses answers for all ten. */
    @Test
    void nothingIsSilentByDefault() throws Exception {
        final List<String> problems = new ArrayList<>();
        final SmpSounds sounds = SmpSounds.of(Configs.sounds(directory, LOGGER).get(), problems::add);

        assertEquals(
                List.of(),
                problems,
                "a shipped default that the parser has to correct is a default that was never" + " checked");
        for (final Feedback category : Feedback.values()) {
            if (category == Feedback.STAGING) {
                // Silent on purpose, as in the test above.
                assertTrue(
                        sounds.isSilent(category),
                        "STAGING is no longer silent out of the box, which is a decision and not a" + " tidy-up");
                continue;
            }
            assertFalse(sounds.isSilent(category), category + " is silent out of the box");
        }
    }

    /** The escape hatch through the real file: a blanked key comes back as an empty string. */
    @Test
    void blankingAKeyInTheFileSilencesTheCategory() throws Exception {
        Configs.sounds(directory, LOGGER);
        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file).replace("key: minecraft:ui.button.click", "key: ''"));

        final List<String> problems = new ArrayList<>();
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertEquals(
                "", spec.select().key(), "jcore handed back something other than the empty string the operator wrote");

        final SmpSounds sounds = SmpSounds.of(spec, problems::add);
        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertFalse(sounds.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
        assertEquals(List.of(), problems, "silencing a category on purpose must not read as a misconfiguration");
    }

    /** A reload picks the blanking up, on the one {@code SmpSounds} instance every listener already holds. */
    @Test
    void aReloadIsPickedUpByTheRunningInstance() throws Exception {
        final ConfigHandle<SoundsSpec> handle = Configs.sounds(directory, LOGGER);
        final SmpSounds running = SmpSounds.of(handle.get(), problem -> {});
        assertFalse(running.isSilent(Feedback.SELECT), "it has to start audible for this to prove" + " anything");

        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file).replace("key: minecraft:ui.button.click", "key: ''"));

        handle.reload();
        running.reload(handle.get());

        assertTrue(
                running.isSilent(Feedback.SELECT),
                "the operator blanked a key and ran /smp reload; the same object every listener"
                        + " holds has to answer silent from the next click on");
        assertFalse(running.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
    }

    /** The pitches are what makes two categories in one sound family tell apart. */
    @Test
    void theTwoNoteBlockCategoriesDiffer() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertTrue(
                spec.refused().pitch() != spec.countdownTick().pitch()
                        || !spec.refused().key().equals(spec.countdownTick().key()),
                "REFUSED and COUNTDOWN_TICK are both note blocks by default; identical key and"
                        + " pitch would make 'the server said no' and 'three seconds left' the same"
                        + " noise");
    }

    /** The same exhaustive switch as the adapter's, so a new {@link Feedback} category stops this compiling. */
    private static SoundsSpec.SoundSpec entryOf(final Feedback category, final SoundsSpec spec) {
        return switch (category) {
            case SMALL_SUCCESS -> spec.smallSuccess();
            case BIG_SUCCESS -> spec.bigSuccess();
            case REFUSED -> spec.refused();
            case LOSS -> spec.loss();
            case SURFACE_OPEN -> spec.surfaceOpen();
            case SURFACE_CLOSE -> spec.surfaceClose();
            case SELECT -> spec.select();
            case TRAVEL -> spec.travel();
            case COUNTDOWN_TICK -> spec.countdownTick();
            case NETWORK_EVENT -> spec.networkEvent();
            case STAGING -> spec.staging();
            case RECLAIMED -> spec.reclaimed();
        };
    }
}
