package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.SmpSpec;

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
 * That the ten sounds a fresh {@code config.yml} ships actually exist.
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
        final SmpSpec.SoundsSpec spec = Configs.load(directory, LOGGER).get().sounds();
        final List<String> problems = new ArrayList<>();

        for (final Feedback category : Feedback.values()) {
            final SmpSpec.SoundSpec sound = entryOf(category, spec);
            if (sound.key() == null || sound.key().isBlank()) {
                problems.add(category + " ships without a sound - a fresh config.yml should carry a"
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
    @DisplayName("the sounds block round-trips through config.yml")
    void theSoundsBlockSurvivesTheRoundTrip() throws Exception {
        final SmpSpec.SoundsSpec written = Configs.load(directory, LOGGER).get().sounds();
        final SmpSpec.SoundsSpec reread = Configs.load(directory, LOGGER).get().sounds();

        for (final Feedback category : Feedback.values()) {
            final SmpSpec.SoundSpec before = entryOf(category, written);
            final SmpSpec.SoundSpec after = entryOf(category, reread);
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
        final SmpSounds sounds = SmpSounds.of(Configs.load(directory, LOGGER).get().sounds(),
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
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(file, Files.readString(file)
                .replace("key: minecraft:ui.button.click", "key: ''"));

        final List<String> problems = new ArrayList<>();
        final SmpSpec.SoundsSpec spec = Configs.load(directory, LOGGER).get().sounds();
        assertEquals("", spec.select().key(),
                "jcore handed back something other than the empty string the operator wrote");

        final SmpSounds sounds = SmpSounds.of(spec, problems::add);
        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertFalse(sounds.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
        assertEquals(List.of(), problems,
                "silencing a category on purpose must not read as a misconfiguration");
    }

    /** The pitches are what makes two categories in one sound family tell apart. */
    @Test
    @DisplayName("the two note-block categories do not ship on the same pitch")
    void theTwoNoteBlockCategoriesDiffer() throws Exception {
        final SmpSpec.SoundsSpec spec = Configs.load(directory, LOGGER).get().sounds();
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
    private static SmpSpec.SoundSpec entryOf(final Feedback category, final SmpSpec.SoundsSpec spec) {
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
