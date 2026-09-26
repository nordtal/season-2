package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;
import eu.nordtal.s2.smp.config.SoundsSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * The one place in {@code smp} that names a sound to Bukkit.
 *
 * <b>Why it lives here and not in {@code :common} </b>
 *
 * {@code :common} is compiled against neither Paper nor Velocity - that is a repository rule and not a preference,
 * because the same jar is shaded into a Velocity plugin. Everything about a sound that can be decided without a
 * platform is in {@code :common} already ( {@link Feedback}, {@link FeedbackSound}, {@link FeedbackSounds}); what is
 * left here is one call, and it is deliberately the only one in the module. {@code SoundVocabularyTest} in
 * {@code :common} fails if a second one appears anywhere in the four client-facing modules, and this file is the
 * single named exception on its allowlist.
 *
 * The cost is that {@code hunger-games} will need a class of its own of about this size, and that is the accepted
 * trade: twenty lines twice, against a shared jar that references {@code org.bukkit.entity.Player} inside a Velocity
 * plugin.
 *
 * <b>Why the String overload of playSound</b>
 *
 * {@code playSound(Location, String, SoundCategory, float, float)} takes the registry key straight from the config,
 * so a custom sound out of the resource pack works with no code change and a vanilla one needs no lookup. The
 * {@code Sound} -typed overloads would need a registry get, which turns "the pack has not been updated yet" into a
 * null and then into a decision this class would have to take on a player's click path.
 *
 * Everything here must be called from the <b>main thread</b>, like every other Bukkit call. The async paths in this
 * module already hop back to send their message; the sound goes in the same hop.
 */
public final class SmpSounds {

    /**
     * Volatile because {@code /smp reload} replaces it while players are clicking.
     *
     * One reference swap rather than a mutable map: a reload has to be all-or-nothing, and every listener in this
     * plugin
     * holds the same {@code SmpSounds} instance from enable to disable. A click that lands mid-reload therefore hears
     * either the whole old file or the whole new one.
     */
    private volatile FeedbackSounds sounds;

    private final Consumer<String> problems;

    public SmpSounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /** Reads {@code sounds.yml}. */
    public static SmpSounds of(final SoundsSpec spec, final Consumer<String> problems) {
        return new SmpSounds(parse(spec, problems), problems);
    }

    /**
     * Re-reads an already-reloaded {@code sounds.yml}, after {@code /smp reload}.
     *
     * This is what the whole file being separate from {@code config.yml} buys: blanking a key to silence a category is
     * the documented escape hatch for a sound that turns out to be irritating with twenty people in a tavern, and an
     * escape hatch that costs a restart of the season is worth very little.
     *
     * A category that had been switched off by {@link FeedbackSounds#failed} comes back, which is correct: the operator
     * has just said what they want the sound to be, and if it still throws it will switch itself off again on the first
     * play.
     */
    public void reload(final SoundsSpec spec) {
        this.sounds = parse(spec, problems);
    }

    private static FeedbackSounds parse(final SoundsSpec spec, final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec entry = specOf(category, spec);
            declared.put(
                    category, new FeedbackSound(entry.key() == null ? "" : entry.key(), entry.volume(), entry.pitch()));
        }
        return FeedbackSounds.parse(declared, problems);
    }

    /**
     * Which config entry belongs to which category.
     *
     * An exhaustive {@code switch} with no {@code default}, on purpose: a category added to {@link Feedback} stops this
     * module compiling until somebody says what it sounds like, which is the only mechanism that keeps the enum and the
     * config file from drifting apart.
     */
    private static SoundsSpec.SoundSpec specOf(final Feedback category, final SoundsSpec spec) {
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

    /** Plays {@code category} for one player, where they are standing. Main thread. */
    public void play(final Player player, final Feedback category) {
        // One read of the volatile field for lookup and failure; a reload could swap registries between two reads.
        final FeedbackSounds current = sounds;
        final FeedbackSound sound = current.sound(category);
        if (sound == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            // MASTER rather than a themed category.
            final Location at = Objects.requireNonNull(player.getLocation());
            player.playSound(at, sound.key(), SoundCategory.MASTER, sound.volume(), sound.pitch());
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so reaching here means the platform disagreed.
            current.failed(category, exception, problems);
        }
    }

    /**
     * Plays {@code category} at {@code location}, for everyone in range - not for one player.
     *
     * {@link #play} reaches exactly the player it is called for, because {@code Player#playSound} is a message to one
     * client regardless of where they stand. A grave settling is not that: other people stand at a grave too, and
     * {@code World#playSound} is the overload that puts a sound at a place in the world rather than in one player's
     * ears.
     *
     * Same failure handling as {@link #play}: a bad key was already refused at load, so a throw here means the platform
     * disagreed with us, and it silences the category rather than logging once per grave.
     */
    public void playAt(final Location location, final Feedback category) {
        final FeedbackSounds current = sounds;
        final FeedbackSound sound = current.sound(category);
        if (sound == null || location == null || location.getWorld() == null) {
            return;
        }
        try {
            location.getWorld().playSound(location, sound.key(), SoundCategory.MASTER, sound.volume(), sound.pitch());
        } catch (final RuntimeException exception) {
            current.failed(category, exception, problems);
        }
    }

    /** Whether this category will play nothing - so a caller can skip work it would only discard. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
