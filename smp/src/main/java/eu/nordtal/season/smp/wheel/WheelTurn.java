package eu.nordtal.season.smp.wheel;

import java.util.Objects;

/**
 * One turn's end: the prize is paid once, whether the wheel comes to rest, the player closes it or leaves.
 * A player who is still there hears the prize sound with it. Used on the main thread only.
 */
final class WheelTurn {

    private final Runnable cheer;
    private final Runnable pay;
    private Runnable clicks = () -> {};
    private boolean paid;

    /** A turn whose {@code cheer} plays the prize sound and whose {@code pay} gives the prize. */
    WheelTurn(final Runnable cheer, final Runnable pay) {
        this.cheer = Objects.requireNonNull(cheer, "cheer");
        this.pay = Objects.requireNonNull(pay, "pay");
    }

    /** Sets what stops the turning sound, which an end runs first. */
    void clicks(final Runnable stop) {
        this.clicks = Objects.requireNonNull(stop, "stop");
    }

    /** Ends the turn: the turning sound stops, and the prize is paid, with its sound when {@code there}. Once only. */
    void end(final boolean there) {
        if (paid) {
            return;
        }
        paid = true;
        clicks.run();
        if (there) {
            cheer.run();
        }
        pay.run();
    }
}
