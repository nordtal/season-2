package eu.nordtal.season.smp.stage;

import eu.nordtal.season.messages.feedback.Feedback;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Everything a staging needs one player's client to do, behind an interface so tests can record it.
 *
 * Every method is called on the runner's scheduler thread, which on Paper is the main thread.
 */
public interface CinematicStage {

    /**
     * Puts one frame on screen.
     *
     * @param image    the frame
     * @param subtitle the line under it, or {@code null}
     * @param ticks    how long it stays, so the title's timing replaces frames without fading
     */
    void show(Component image, @Nullable Component subtitle, int ticks);

    /**
     * Applies the effect for the whole staging.
     *
     * @param effect what to apply
     * @param ticks  the length of the whole staging
     */
    void effect(Cinematic.Effect effect, int ticks);

    /** Plays the opening sound; a blank or broken key is silent rather than throwing. */
    void play(Feedback sound);

    /**
     * Takes the staging off the screen and removes what it applied.
     *
     * Called exactly once per run, at the end or on a cancel, so it must be safe for a player who has left.
     */
    void clear();
}
