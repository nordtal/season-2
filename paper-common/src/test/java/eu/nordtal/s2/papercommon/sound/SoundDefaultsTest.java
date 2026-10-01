package eu.nordtal.s2.papercommon.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

/**
 * That the ten sounds every server starts with actually exist.
 *
 * Each {@code minecraft:} key is looked up as a {@code Sound} field by name, which needs no server.
 */
class SoundDefaultsTest {

    @Test
    void everyDefaultKeyResolvesAgainstBukkitsSoundList() throws Exception {
        final SoundsSpec spec = Specs.createDefault(SoundsSpec.class);
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

    /** The pitches are what makes two categories in one sound family tell apart. */
    @Test
    void theTwoNoteBlockCategoriesDiffer() throws Exception {
        final SoundsSpec spec = Specs.createDefault(SoundsSpec.class);
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
