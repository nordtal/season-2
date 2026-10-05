package eu.nordtal.season.proxy.phase;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.phase.PhaseDirectory;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The proxy's view of the season phase, refreshed by a poll and a {@code LISTEN} connection.
 *
 * Not read by the login path. Before the first read the phase is {@code MAINTENANCE}, which lets nobody in.
 */
public final class PhaseWatch {

    /** Notified once per observed change, never for a refresh that read the same value back. */
    @FunctionalInterface
    public interface ChangeListener {

        /**
         * Receives one phase change.
         *
         * @param previous the phase before, {@code null} on the very first successful read
         */
        void phaseChanged(@Nullable SeasonPhase previous, SeasonPhase current);
    }

    private final PhaseDirectory phases;
    private final Logger logger;
    private final ChangeListener listener;

    /**
     * The phase and the announced opening instant, published as one value so a ping never pairs old and new.
     *
     * @param launch when the network opens, {@code null} when no date is set
     */
    public record Known(SeasonPhase phase, @Nullable Instant launch) {}

    /** {@code null} until the row has been read successfully once. */
    private final AtomicReference<Known> known = new AtomicReference<>();

    public PhaseWatch(final PhaseDirectory phases, final Logger logger, final ChangeListener listener) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Re-reads the row unconditionally, since a notification carries no payload and may be lost.
     *
     * @return {@code false} when the database could not be reached; {@link #lastKnown()} keeps its value
     */
    public boolean refresh() {
        final SeasonPhase current;
        final Instant announced;
        try {
            current = phases.currentPhase();
            // A second query, off the login path: disconnect screens need this column.
            announced = phases.launch().orElse(null);
        } catch (final RuntimeException exception) {
            logger.warn("Could not read the season phase; staying on the last known one ({})", lastKnown(), exception);
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

    /** Returns the phase last read, or {@link SeasonPhase#MAINTENANCE} if none ever was. */
    public SeasonPhase lastKnown() {
        final Known current = known.get();
        return current == null ? SeasonPhase.MAINTENANCE : current.phase();
    }

    /**
     * Returns the phase and opening instant as read together, never {@code null}.
     *
     * Before the first read it is {@link SeasonPhase#MAINTENANCE} with no instant.
     */
    public Known known() {
        final Known current = known.get();
        return current == null ? new Known(SeasonPhase.MAINTENANCE, null) : current;
    }

    /** Returns when the network opens; empty when no date is announced or the row has never been read. */
    public Optional<Instant> launch() {
        return Optional.ofNullable(known().launch());
    }

    /** Returns whether the row has ever been read; if not, {@link #lastKnown()} is a guess. */
    public boolean everRead() {
        return known.get() != null;
    }

    private void notifyListener(final @Nullable SeasonPhase previous, final SeasonPhase current) {
        try {
            listener.phaseChanged(previous, current);
        } catch (final RuntimeException exception) {
            // The phase is already swapped, so a throwing listener must not undo that.
            logger.error("A phase change listener failed for {} -> {}", previous, current, exception);
        }
    }
}
