package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.messages.feedback.FeedbackSound;
import eu.nordtal.s2.papercommon.sound.SoundsSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * The one place in {@code smp} that names a sound to Bukkit, on the main thread.
 *
 * {@code :common} cannot reference Paper, and {@code :architecture} allows no second call site.
 */
public final class SmpSounds {

    /** Volatile because {@code /smp reload} swaps the whole registry while players are clicking. */
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
     * A category switched off by {@link FeedbackSounds#failed} comes back, and switches off again if it still throws.
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
     * No {@code default}, so a new {@link Feedback} category does not compile until it has a sound.
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
     * Plays {@code category} at {@code location} for everyone in range, not for one player.
     *
     * A throw silences the category, as in {@link #play}.
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

    /** Whether this category will play nothing, so a caller can skip work it would only discard. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
