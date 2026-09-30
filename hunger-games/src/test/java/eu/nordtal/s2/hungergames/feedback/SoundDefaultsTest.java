package eu.nordtal.s2.hungergames.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.hungergames.config.SoundsSpec;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.Setting;
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
 * Checks that the ten sounds a fresh {@code sounds.yml} ships exist, on this module's copy.
 *
 * Only {@code minecraft:} keys are checked, by {@code getField} on {@code org.bukkit.Sound}.
 */
class SoundDefaultsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundDefaultsTest.class);

    @TempDir
    Path directory;

    @Test
    void everyCategoryHasADefaultAndEveryDefaultIsARealVanillaSound() throws Exception {
        final SoundsSpec spec = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();
        final List<String> problems = new ArrayList<>();

        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec sound = entryOf(category, spec);
            if (category == Feedback.STAGING) {
                // Ships blank until its sound arrives with the artwork; filling it in has to be a visible decision.
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
                // Reaching here without a NoSuchFieldException is the check.
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

    /** Checks that the values survive being written to a file and read back, nesting and floats included. */
    @Test
    void theSoundsRoundTripThroughSoundsYml() throws Exception {
        final SoundsSpec written = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();
        final SoundsSpec reread = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();

        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec before = entryOf(category, written);
            final SoundsSpec.SoundSpec after = entryOf(category, reread);
            assertEquals(before.key(), after.key(), category.name());
            assertEquals(before.volume(), after.volume(), category.name());
            assertEquals(before.pitch(), after.pitch(), category.name());
        }
    }

    /** Checks that the parsed form answers for all ten. */
    @Test
    void theParsedVocabularyHasNoSilentCategoryByDefault() throws Exception {
        final List<String> problems = new ArrayList<>();
        final HungerGamesSounds sounds = HungerGamesSounds.of(
                FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                        .load("sounds", SoundsSpec.class)
                        .get(),
                problems::add);

        assertEquals(
                List.of(),
                problems,
                "a shipped default that the parser has to correct is a default that was never" + " checked");
        for (final Feedback category : Feedback.values()) {
            if (category == Feedback.STAGING) {
                // Silent on purpose, see the exception in the test above.
                assertTrue(
                        sounds.isSilent(category),
                        "STAGING is no longer silent out of the box, which is a decision and not a" + " tidy-up");
                continue;
            }
            assertFalse(sounds.isSilent(category), category + " is silent out of the box");
        }
    }

    /** Checks that a blanked key survives the real file as an empty string, rather than a refused load or a default. */
    @Test
    void blankingAKeyInTheFileReallyDoesSilenceThatCategory() throws Exception {
        FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER).load("sounds", SoundsSpec.class);
        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file).replace("key: minecraft:entity.villager.no", "key: ''"));

        final List<String> problems = new ArrayList<>();
        final SoundsSpec spec = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();
        assertEquals(
                "", spec.loss().key(), "jcore handed back something other than the empty string the operator wrote");

        final HungerGamesSounds sounds = HungerGamesSounds.of(spec, problems::add);
        assertTrue(sounds.isSilent(Feedback.LOSS));
        assertFalse(sounds.isSilent(Feedback.COUNTDOWN_TICK), "only the blanked category goes quiet");
        assertEquals(List.of(), problems, "silencing a category on purpose must not read as a misconfiguration");
    }

    /** Checks that a reload picks the blanking up on the instance every listener already holds. */
    @Test
    void aReloadSilencesACategoryOnTheInstanceTheListenersAlreadyHold() throws Exception {
        final Setting<SoundsSpec> handle =
                FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER).load("sounds", SoundsSpec.class);
        final HungerGamesSounds running = HungerGamesSounds.of(handle.get(), problem -> {});
        assertFalse(running.isSilent(Feedback.LOSS), "it has to start audible for this to prove" + " anything");

        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file).replace("key: minecraft:entity.villager.no", "key: ''"));

        handle.reload();
        running.reload(handle.get());

        assertTrue(
                running.isSilent(Feedback.LOSS),
                "the operator blanked a key and ran /hg reload; the same object every listener"
                        + " holds has to answer silent from the next death on");
        assertFalse(running.isSilent(Feedback.COUNTDOWN_TICK), "only the blanked category goes quiet");
    }

    /** Checks that {@code LOSS} and {@code COUNTDOWN_TICK}, which can play in the same tick, sound different. */
    @Test
    void theTwoCategoriesADeathPlaysAtOnceDoNotShipAsTheSameNoise() throws Exception {
        final SoundsSpec spec = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();
        assertTrue(
                !spec.loss().key().equals(spec.countdownTick().key())
                        || spec.loss().pitch() != spec.countdownTick().pitch(),
                "LOSS and COUNTDOWN_TICK land in the same tick on every death that moves the" + " border");
    }

    /**
     * Mirrors the adapter's exhaustive switch, so a new {@link Feedback} category fails to compile without a default.
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
            case STAGING -> spec.staging();
            case RECLAIMED -> spec.reclaimed();
        };
    }
}
