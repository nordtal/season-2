package eu.nordtal.s2.hungergames.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.hungergames.config.Configs;
import eu.nordtal.s2.hungergames.config.SoundsSpec;
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
 * That the ten sounds a fresh {@code sounds.yml} ships actually exist, on this module's copy.
 *
 * Why the same test twice, in two modules: because the two files are two files. {@code smp}'s copy
 * proves {@code smp}'s defaults resolve, and a key mistyped here would be silent on the event server
 * with nothing in any log to say so - which is the single hardest kind of regression to notice,
 * because there is nothing to see. The values are deliberately identical to {@code smp}'s today;
 * nothing enforces that and nothing should, since a config file that cannot diverge is not a config
 * file.
 *
 * How it resolves a key without a server: {@code org.bukkit.Sound} is an interface of constants
 * generated from the registry, each named after its key with every {@code .} replaced by
 * {@code _} and upper-cased - {@code entity.experience_orb.pickup} is
 * {@code ENTITY_EXPERIENCE_ORB_PICKUP}. Asking for the field is enough, and it is deliberately
 * {@code getField} rather than reading the value: reading one initialises the class, which does a
 * registry lookup, which needs the running server this test does not have.
 *
 * Only {@code minecraft:} keys are checked. A key in our own namespace is a resource pack sound
 * the server has never heard of and never will, which is the whole reason the config carries keys
 * rather than constants.
 */
class SoundDefaultsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundDefaultsTest.class);

    @TempDir
    Path directory;

    @Test
    void everyCategoryHasADefaultAndEveryDefaultIsARealVanillaSound() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
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
                // The field itself is unused - reaching here without a NoSuchFieldException is the check.
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
    void theSoundsRoundTripThroughSoundsYml() throws Exception {
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
    void theParsedVocabularyHasNoSilentCategoryByDefault() throws Exception {
        final List<String> problems = new ArrayList<>();
        final HungerGamesSounds sounds =
                HungerGamesSounds.of(Configs.sounds(directory, LOGGER).get(), problems::add);

        assertEquals(
                List.of(),
                problems,
                "a shipped default that the parser has to correct is a default that was never" + " checked");
        for (final Feedback category : Feedback.values()) {
            if (category == Feedback.STAGING) {
                // Silent on purpose - see the exception in the test above.
                assertTrue(
                        sounds.isSilent(category),
                        "STAGING is no longer silent out of the box, which is a decision and not a" + " tidy-up");
                continue;
            }
            assertFalse(sounds.isSilent(category), category + " is silent out of the box");
        }
    }

    /**
     * The escape hatch, driven through the real file rather than through the parser alone.
     *
     * {@code FeedbackSoundsTest} proves that a blank key silences a category. What it cannot
     * prove is that a blank key <em>survives the config system</em>: jcore's loader is strict, and
     * "the operator blanked a value" has to come back as an empty string rather than as a refused
     * load or a default quietly written back over the top. That is the difference between an escape
     * hatch and a promise.
     *
     * {@code LOSS} is the one blanked here rather than an arbitrary category, because it is the
     * sound this module plays most and therefore the first one anybody would reach for the hatch
     * over - on a server where twenty people are eliminated inside an hour.
     */
    @Test
    void blankingAKeyInTheFileReallyDoesSilenceThatCategory() throws Exception {
        Configs.sounds(directory, LOGGER);
        final Path file = directory.resolve("sounds.yml");
        Files.writeString(file, Files.readString(file).replace("key: minecraft:entity.villager.no", "key: ''"));

        final List<String> problems = new ArrayList<>();
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertEquals(
                "", spec.loss().key(), "jcore handed back something other than the empty string the operator wrote");

        final HungerGamesSounds sounds = HungerGamesSounds.of(spec, problems::add);
        assertTrue(sounds.isSilent(Feedback.LOSS));
        assertFalse(sounds.isSilent(Feedback.COUNTDOWN_TICK), "only the blanked category goes quiet");
        assertEquals(List.of(), problems, "silencing a category on purpose must not read as a misconfiguration");
    }

    /**
     * And a reload picks the blanking up, on the instance every listener is already holding.
     *
     * This is the whole reason {@code sounds.yml} is a file of its own rather than a block in
     * {@code config.yml}, and the reason is sharper here than on the SMP: {@code /hg reload}
     * re-reads no game parameter at all, because a border schedule must not move while players are
     * running from it. Without a separate file there would be no way at all to silence a chime
     * during the one hour a year this server is used.
     *
     * The assertion that matters is the last one: the plugin hands <em>one</em>
     * {@code HungerGamesSounds} to every listener at enable and never hands out another, so a reload
     * that returned a new object would change nothing a player can hear.
     */
    @Test
    void aReloadSilencesACategoryOnTheInstanceTheListenersAlreadyHold() throws Exception {
        final ConfigHandle<SoundsSpec> handle = Configs.sounds(directory, LOGGER);
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

    /**
     * The two categories this module plays closest together have to be tellable apart.
     *
     * An elimination books {@code LOSS} for the victim and, in the same tick, a border shrink
     * announces {@code COUNTDOWN_TICK} to everybody standing in the world - the victim included.
     * Identical key and pitch would make "you are out" and "the wall is moving" one noise, in the
     * one moment of the game where a player most needs to know which of the two just happened.
     */
    @Test
    void theTwoCategoriesADeathPlaysAtOnceDoNotShipAsTheSameNoise() throws Exception {
        final SoundsSpec spec = Configs.sounds(directory, LOGGER).get();
        assertTrue(
                !spec.loss().key().equals(spec.countdownTick().key())
                        || spec.loss().pitch() != spec.countdownTick().pitch(),
                "LOSS and COUNTDOWN_TICK land in the same tick on every death that moves the" + " border");
    }

    /**
     * Same exhaustive switch as the adapter's, and here for the same reason.
     *
     * A category added to {@link Feedback} has to stop this test compiling until somebody has
     * given it a default.
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
