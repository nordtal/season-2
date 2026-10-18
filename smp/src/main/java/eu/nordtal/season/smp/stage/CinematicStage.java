package eu.nordtal.season.smp.stage;

import eu.nordtal.season.messages.feedback.Feedback;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The actions a staging performs on the client of one player.
 * {@link Cinematics} calls every method on its scheduler thread, which is the main thread on Paper.
 */
public interface CinematicStage {

    /** Shows {@code image} and {@code subtitle} for {@code ticks}. The title timing replaces a frame without a fade. */
    void show(Component image, @Nullable Component subtitle, int ticks);

    void effect(Cinematic.Effect effect, int ticks);

    /** Plays the opening sound. A blank or invalid key plays nothing and throws nothing. */
    void play(Feedback sound);

    /**
     * Removes the title and the effect.
     * {@link Cinematics} calls it once per run, at the end or on a cancel. It must work for a player who has left.
     */
    void clear();
}
