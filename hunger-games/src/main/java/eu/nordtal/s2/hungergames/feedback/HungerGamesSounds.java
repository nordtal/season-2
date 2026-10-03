package eu.nordtal.s2.hungergames.feedback;

import eu.nordtal.s2.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.messages.feedback.FeedbackSound;
import eu.nordtal.s2.papercommon.sound.SoundsSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The one place in {@code hunger-games} that names a sound to Bukkit, on the main thread.
 *
 * {@code :common} cannot reference Paper, and {@code :architecture} allows no second call site.
 */
public final class HungerGamesSounds {

    /** Volatile because a settings change swaps the whole registry mid-game. */
    private volatile FeedbackSounds sounds;

    private final Consumer<String> problems;

    public HungerGamesSounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /** Reads the {@code sounds} group. */
    public static HungerGamesSounds of(final SoundsSpec spec, final Consumer<String> problems) {
        return new HungerGamesSounds(parse(spec, problems), problems);
    }

    /**
     * Re-reads an already-reloaded {@code sounds} group, after the settings signal.
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

    /**
     * Plays {@code category} for one player, where they are standing, on the main thread.
     *
     * {@code player} may be null: a body standing in for a disconnected participant has nobody to hear it.
     */
    public void play(final @Nullable Player player, final Feedback category) {
        // One read of the volatile field for lookup and failure; a reload could swap registries between two reads.
        final FeedbackSounds current = sounds;
        final FeedbackSound sound = current.sound(category);
        if (sound == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            // MASTER rather than a themed category.
            player.playSound(
                    java.util.Objects.requireNonNull(player.getLocation()),
                    sound.key(),
                    SoundCategory.MASTER,
                    sound.volume(),
                    sound.pitch());
        } catch (final RuntimeException exception) {
            // The platform disagreed; silence the category once rather than log a stack trace per death.
            current.failed(category, exception, problems);
        }
    }

    /** Whether this category will play nothing, so a caller can skip work it would only discard. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
