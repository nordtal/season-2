package eu.nordtal.season.hungergames.feedback;

import eu.nordtal.season.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.feedback.FeedbackSound;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Plays the sounds of the {@code sounds} group on the main thread. */
public final class HungerGamesSounds {

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
     * Replaces the sounds with those of the reloaded group.
     * A category that {@link FeedbackSounds#failed} switched off is on again.
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
     * Plays the category to the player at the location of the player.
     * The player may be {@code null}, which plays nothing.
     */
    public void play(final @Nullable Player player, final Feedback category) {
        final FeedbackSounds current = sounds;
        final FeedbackSound sound = current.sound(category);
        if (sound == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            player.playSound(
                    java.util.Objects.requireNonNull(player.getLocation()),
                    sound.key(),
                    SoundCategory.MASTER,
                    sound.volume(),
                    sound.pitch());
        } catch (final RuntimeException exception) {
            current.failed(category, exception, problems);
        }
    }

    /** Returns whether the category plays nothing. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
