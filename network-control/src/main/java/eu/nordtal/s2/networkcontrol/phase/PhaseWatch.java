package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proxy's view of the season phase: whatever the {@code season_phase} row last said, refreshed
 * by a poll every {@code phase-poll-interval-seconds} and, when it is available, by a
 * {@code LISTEN}/{@code NOTIFY} connection that makes a switch feel instant.
 *
 * <h2>This is not what the login path reads</h2>
 * The login path gets the phase on the <b>same row</b> as the access state
 * ({@code AccessState#phase()}), because docs/season-phases.md pins it to one round trip. This
 * class exists for everything that is not a login: logging a switch as it happens, answering
 * {@code /phase} without a database call, and - when routing is written - being the thing that
 * re-routes connected players when the phase moves. Nothing here is authoritative; it is a record
 * of what the row said, and the row is the truth.
 *
 * <h2>What it falls back to</h2>
 * docs/season-phases.md#the-gate: "a phase that cannot be read falls back to <b>the last known
 * phase</b>, and if there is none, to {@code MAINTENANCE} - the state that lets nobody in is the
 * safe one to guess." {@link #lastKnown()} is exactly that rule. A failed refresh therefore leaves
 * the previous value in place rather than overwriting it with a guess; only a process that has
 * <em>never</em> read the row answers {@code MAINTENANCE}.
 *
 * <h2>Thread safety</h2>
 * {@link #refresh()} is called from three places - the scheduler's poll thread, the listener
 * thread, and the {@code /phase} command after a switch - so the value is an
 * {@link AtomicReference} and the change callback fires only for the caller that actually swapped
 * it.
 */
public final class PhaseWatch {

    /** Notified once per observed change, never for a refresh that read the same value back. */
    @FunctionalInterface
    public interface ChangeListener {

        /**
         * @param previous what this process thought the phase was, {@code null} on the very first
         *                 successful read - "the proxy has just learned the phase" and "the phase
         *                 changed under us" are different events and a listener may care
         * @param current  what the row says now
         */
        void phaseChanged(SeasonPhase previous, SeasonPhase current);
    }

    private final PhaseDirectory phases;
    private final Logger logger;
    private final ChangeListener listener;

    /** {@code null} until the row has been read successfully at least once. */
    private final AtomicReference<SeasonPhase> lastKnown = new AtomicReference<>();

    public PhaseWatch(final PhaseDirectory phases, final Logger logger, final ChangeListener listener) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Re-reads the row, <b>unconditionally</b>.
     * <p>
     * There is no "only if something might have changed" short cut here on purpose: this is what a
     * reconnecting listener calls, and docs/season-phases.md is explicit that notifications are
     * lost while a process is disconnected, so every reconnect has to re-read whether or not it
     * missed anything. The notification carries no payload either, so there would be nothing to
     * short-cut on.
     * </p>
     *
     * @return {@code true} when the row was read, {@code false} when the database could not be
     *         reached - in which case {@link #lastKnown()} keeps whatever it had
     */
    public boolean refresh() {
        final SeasonPhase current;
        try {
            current = phases.currentPhase();
        } catch (final RuntimeException exception) {
            logger.warn("Could not read the season phase; staying on the last known one ({})",
                    lastKnown(), exception);
            return false;
        }

        final SeasonPhase previous = lastKnown.getAndSet(current);
        if (previous != current) {
            if (previous == null) {
                logger.info("Season phase is {}", current);
            } else {
                logger.warn("Season phase changed: {} -> {}", previous, current);
            }
            notifyListener(previous, current);
        }
        return true;
    }

    /**
     * @return the phase this process last managed to read, or {@link SeasonPhase#MAINTENANCE} if it
     *         has never read one at all
     */
    public SeasonPhase lastKnown() {
        final SeasonPhase known = lastKnown.get();
        return known == null ? SeasonPhase.MAINTENANCE : known;
    }

    /**
     * @return whether the row has ever been read successfully; {@code false} means
     *         {@link #lastKnown()} is the safe guess and not an observation, which is worth saying
     *         out loud in {@code /phase}'s reply
     */
    public boolean everRead() {
        return lastKnown.get() != null;
    }

    private void notifyListener(final SeasonPhase previous, final SeasonPhase current) {
        try {
            listener.phaseChanged(previous, current);
        } catch (final RuntimeException exception) {
            // A listener that throws must not stop the watch from having recorded the new phase -
            // the phase is already swapped by the time we get here.
            logger.error("A phase change listener failed for {} -> {}", previous, current, exception);
        }
    }
}
