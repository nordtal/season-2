package eu.nordtal.s2.common.stage;

import eu.nordtal.s2.common.feedback.Feedback;

import net.kyori.adventure.text.Component;

/**
 * Everything a staging needs one player's client to be able to do.
 *
 * <h2>Why this exists rather than a {@code Player} parameter</h2>
 * Four calls, and every one of them is a packet. {@code :common} is compiled against no platform, so
 * a runner living here cannot hold a {@code Player} - and that restriction turns out to be the
 * feature: with the surface behind an interface, {@link Cinematics} can be driven in a test that
 * records what was shown and when, which is the only way "the frames ran in order, spaced correctly,
 * and a cancel really cancelled" is answerable without a client.
 *
 * <p>Every method is called on whatever thread the runner's scheduler uses. On Paper that is the
 * main thread, which is what all four of these need.
 */
public interface CinematicStage {

    /**
     * Puts one frame on screen.
     *
     * @param image    the frame
     * @param subtitle the line under it, or {@code null}
     * @param ticks    how long it stays. An implementation uses it to size the title's own timing,
     *                 so that the frames replace each other rather than fading out between them
     */
    void show(Component image, Component subtitle, int ticks);

    /**
     * Applies the effect for the whole staging.
     *
     * @param effect what to apply
     * @param ticks  the length of the whole staging, so the effect ends when the pictures do
     */
    void effect(Cinematic.Effect effect, int ticks);

    /**
     * Plays the opening sound.
     *
     * <p>Through the module's own sound adapter, so a blank key in {@code sounds.yml} is silent and
     * a broken one silences the category rather than throwing on a player's join path.
     */
    void play(Feedback sound);

    /**
     * Takes the staging off the screen and removes what it applied.
     *
     * <p>Called exactly once per run, at the end <b>and</b> on a cancel - so an implementation has
     * to be safe on a player who has already left. A staging that is interrupted must not leave
     * somebody blind: that is the difference between a cancel and a crash.
     */
    void clear();
}
