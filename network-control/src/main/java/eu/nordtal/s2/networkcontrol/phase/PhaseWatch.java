package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proxy's view of the season phase: whatever the {@code season_phase} row last said, refreshed
 * by a poll every {@code phase-poll-interval-seconds} and, when it is available, by a
 * {@code LISTEN}/{@code NOTIFY} connection that makes a switch feel instant.
 *
 * <p><b>This is not what the login path reads.</b> That gets the phase on the same row as the
 * access state, in one round trip. This class is for everything else: logging a switch, answering
 * {@code /phase} without a database call, and re-routing connected players when the phase moves.
 * Nothing here is authoritative - the row is.</p>
 *
 * <p>A phase that cannot be read falls back to the last known one, and to {@code MAINTENANCE} only
 * when the row has never been read: the state that lets nobody in is the safe guess. A failed
 * refresh therefore leaves the previous value in place.</p>
 *
 * <p>{@link #refresh()} is called from the poll thread, the listener thread and the {@code /phase}
 * command, so the value is an {@link AtomicReference} and the change callback fires only for the
 * caller that actually swapped it.</p>
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

    /**
     * The phase and the announced opening instant, as one value.
     * <p>
     * One reference rather than two, because {@code NetworkPing} renders a {@code PRE_LAUNCH} MOTD
     * out of both, and two separately published references would let a ping pair the old phase with
     * the new instant.
     * </p>
     *
     * @param phase  what the row said
     * @param launch when the network opens, {@code null} when no date is set
     */
    public record Known(SeasonPhase phase, Instant launch) {
    }

    /** {@code null} until the row has been read successfully at least once. */
    private final AtomicReference<Known> known = new AtomicReference<>();

    public PhaseWatch(final PhaseDirectory phases, final Logger logger, final ChangeListener listener) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Re-reads the row, <b>unconditionally</b>.
     * <p>
     * No short cut: notifications are lost while a process is disconnected and carry no payload, so
     * a reconnecting listener has to re-read whether or not it missed anything.
     * </p>
     *
     * @return {@code true} when the row was read, {@code false} when the database could not be
     *         reached - in which case {@link #lastKnown()} keeps whatever it had
     */
    public boolean refresh() {
        final SeasonPhase current;
        final Instant announced;
        try {
            current = phases.currentPhase();
            // A second query, off the login path: the login query carries its own copy of this
            // column, because the disconnect screens need it in the same round trip.
            announced = phases.launch().orElse(null);
        } catch (final RuntimeException exception) {
            logger.warn("Could not read the season phase; staying on the last known one ({})",
                    lastKnown(), exception);
            return false;
        }
        final Known before = known.getAndSet(new Known(current, announced));
        final SeasonPhase previous = before == null ? null : before.phase();
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
        final Known current = known.get();
        return current == null ? SeasonPhase.MAINTENANCE : current.phase();
    }

    /**
     * The phase and the opening instant as they were read together, for the one caller that needs
     * both to agree - see {@link Known}.
     *
     * @return never {@code null}; before the first successful read it is
     *         {@link SeasonPhase#MAINTENANCE} with no announced instant, the same guess
     *         {@link #lastKnown()} makes
     */
    public Known known() {
        final Known current = known.get();
        return current == null ? new Known(SeasonPhase.MAINTENANCE, null) : current;
    }

    /**
     * @return when the network opens, empty when no date is announced or the row has never been
     *         read; the MOTD renders both cases the same way, because both mean "we cannot tell
     *         you yet"
     */
    public Optional<Instant> launch() {
        return Optional.ofNullable(known().launch());
    }

    /**
     * @return whether the row has ever been read successfully; {@code false} means
     *         {@link #lastKnown()} is the safe guess rather than an observation
     */
    public boolean everRead() {
        return known.get() != null;
    }

    private void notifyListener(final SeasonPhase previous, final SeasonPhase current) {
        try {
            listener.phaseChanged(previous, current);
        } catch (final RuntimeException exception) {
            // The phase is already swapped by the time we get here, so a throwing listener must
            // not undo that.
            logger.error("A phase change listener failed for {} -> {}", previous, current, exception);
        }
    }
}
