package eu.nordtal.season.smp.wheel;

import org.jspecify.annotations.Nullable;

/** Settles a turn once, even when its animation fails or smp stops. */
final class WheelTurn {

    private @Nullable Runnable refund;
    private @Nullable Runnable give;
    private Runnable store = () -> {};
    private Runnable clicks = () -> {};
    private boolean ended;

    synchronized boolean ended() {
        return ended;
    }

    synchronized void spent(final Runnable refund) {
        this.refund = refund;
    }

    synchronized void drawn(final Runnable give, final Runnable store) {
        refund = null;
        this.give = give;
        this.store = store;
    }

    synchronized void clicks(final Runnable stop) {
        clicks = stop;
    }

    void end(final boolean online) {
        end(online, () -> {});
    }

    /** Stops the sound and settles the spin even when a sound fails. */
    synchronized void end(final boolean online, final Runnable cheer) {
        if (ended) {
            return;
        }
        ended = true;
        final Runnable payout = give;
        final Runnable restore = refund;
        try {
            clicks.run();
            if (online && payout != null) {
                cheer.run();
            }
        } finally {
            if (payout != null) {
                (online ? payout : store).run();
            } else if (restore != null) {
                restore.run();
            }
        }
    }
}
