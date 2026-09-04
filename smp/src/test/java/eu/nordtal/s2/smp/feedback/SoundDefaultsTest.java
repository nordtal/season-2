package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.SoundsSpec;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the ten sounds a fresh {@code sounds.yml} ships actually exist.
 *
 * <h2>Why this is worth a test rather than a careful afternoon</h2>
 * The keys were resolved by hand once, against paper-api 26.2.build.121-stable on 2026-09-04. That
 * answer is true for exactly one version of Minecraft. A sound removed or renamed in a later one
 * would otherwise reach production as a category that has quietly gone silent - and a missing chime
 * is the single hardest kind of regression to notice, because there is nothing to see and nothing in
 * any log.
 *
 * <h2>How it resolves a key without a server</h2>
 * {@code org.bukkit.Sound} is an interface of constants generated from the registry, each named
 * after its key with every {@code .} replaced by {@code _} and upper-cased -
 * {@code entity.experience_orb.pickup} is {@code ENTITY_EXPERIENCE_ORB_PICKUP}. Asking for the field
 * is enough, and it is deliberately {@code getField} rather than reading the value: reading one
 * initialises the class, which does a registry lookup, which needs the running server this test does
 * not have.
 *
 * <p>Only {@code minecraft:} keys are checked. A key in our own namespace is a resource pack sound
 * the server has never heard of and never will, which is the whole reason the config carries keys
 * rather than constants.
 */
class SoundDefaultsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundDefaultsTest.class);

    @TempDir
    Path directory;

    @Test
    @DisplayName("every category has a default, and every default is a real vanilla sound")
    void everyDefaultKeyResolvesAgainstBukkitsSoundList() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        final List<String> problems = new ArrayList<>();

        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec sound = entryOf(category, spec);
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
                org.bukkit.Sound.class.getField(field);
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
    @DisplayName("the sounds round-trip through sounds.yml")
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
    @DisplayName("the parsed vocabulary has no silent category by default")
    void nothingIsSilentByDefault() throws Exception {
        final List<String> problems = new ArrayList<>();
        final SmpSounds sounds = SmpSounds.of(Configs.sounds(directory, LOGGER).get(),
                problems::add);

        assertEquals(List.of(), problems,
                "a shipped default that the parser has to correct is a default that was never"
                        + " checked");
        for (final Feedback category : Feedback.values()) {
            assertFalse(sounds.isSilent(category), category + " is silent out of the box");
        }
    }

    /**
     * The escape hatch, driven through the real file rather than through the parser alone.
     *
     * <p>{@code FeedbackSoundsTest} proves that a blank key silences a category. What it cannot
     * prove is that a blank key <em>survives the config system</em>: jcore's loader is strict, and
     * "the operator blanked a value" has to come back as an empty string rather than as a refused
     * load or a default quietly written back over the top. That is the difference between an escape
     * hatch and a promise.
     */
    @Test
    @DisplayName("blanking a key in the file really does silence that category")
    void blankingAKeyInTheFileSilencesTheCategory() throws Exception {
        Configs.sounds(directory, LOGGER);
        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file)
                .replace("key: minecraft:ui.button.click", "key: ''"));

        final List<String> problems = new ArrayList<>();
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertEquals("", spec.select().key(),
                "jcore handed back something other than the empty string the operator wrote");

        final SmpSounds sounds = SmpSounds.of(spec, problems::add);
        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertFalse(sounds.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
        assertEquals(List.of(), problems,
                "silencing a category on purpose must not read as a misconfiguration");
    }

    /**
     * And a reload picks the blanking up, on the instance every listener is already holding.
     *
     * <p>This is the whole reason {@code sounds.yml} is a file of its own rather than a block in
     * {@code config.yml}. The escape hatch documented on {@link SoundsSpec} - blank the key when a
     * sound turns out to be irritating - is worth very little if using it costs a restart of the
     * season, and {@code config.yml} is deliberately not reloadable.
     *
     * <p>The assertion that matters is the last one: the plugin hands <em>one</em> {@code SmpSounds}
     * to fifteen listeners at enable and never hands out another, so a reload that returned a new
     * object would change nothing a player can hear.
     */
    @Test
    @DisplayName("a reload silences a category on the instance the listeners already hold")
    void aReloadIsPickedUpByTheRunningInstance() throws Exception {
        final ConfigHandle<SoundsSpec> handle = Configs.sounds(directory, LOGGER);
        final SmpSounds running = SmpSounds.of(handle.get(), problem -> { });
        assertFalse(running.isSilent(Feedback.SELECT), "it has to start audible for this to prove"
                + " anything");

        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file)
                .replace("key: minecraft:ui.button.click", "key: ''"));

        handle.reload();
        running.reload(handle.get());

        assertTrue(running.isSilent(Feedback.SELECT),
                "the operator blanked a key and ran /smp reload; the same object every listener"
                        + " holds has to answer silent from the next click on");
        assertFalse(running.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
    }

    /** The pitches are what makes two categories in one sound family tell apart. */
    @Test
    @DisplayName("the two note-block categories do not ship on the same pitch")
    void theTwoNoteBlockCategoriesDiffer() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertTrue(spec.refused().pitch() != spec.countdownTick().pitch()
                        || !spec.refused().key().equals(spec.countdownTick().key()),
                "REFUSED and COUNTDOWN_TICK are both note blocks by default; identical key and"
                        + " pitch would make 'the server said no' and 'three seconds left' the same"
                        + " noise");
    }

    /**
     * Same exhaustive switch as the adapter's, and here for the same reason: a category added to
     * {@link Feedback} has to stop this test compiling until somebody has given it a default.
     */
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
        };
    }
}
