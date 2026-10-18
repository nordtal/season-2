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
 * Holds the phase of the season. The signal hub triggers {@link #refresh()}. Before the first read the phase is {@code
 * MAINTENANCE}.
 */
public final class PhaseWatch {

    /** Receives each change of the phase. */
    @FunctionalInterface
    public interface ChangeListener {

        /** @param previous the phase before the change, {@code null} on the first read */
        void phaseChanged(@Nullable SeasonPhase previous, SeasonPhase current);
    }

    private final PhaseDirectory phases;
    private final Logger logger;
    private final ChangeListener listener;

    /** The phase and the opening time. {@code launch} is {@code null} without a date. */
    public record Known(SeasonPhase phase, @Nullable Instant launch) {}

    /** The last read value, {@code null} before the first read. */
    private final AtomicReference<Known> known = new AtomicReference<>();

    public PhaseWatch(final PhaseDirectory phases, final Logger logger, final ChangeListener listener) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Reads the phase from the database.
     *
     * @return {@code false} when the database does not answer, then {@link #lastKnown()} keeps its value
     */
    public boolean refresh() {
        final SeasonPhase current;
        final Instant announced;
        try {
            current = phases.currentPhase();
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

    /** Returns the last read phase, or {@link SeasonPhase#MAINTENANCE} before the first read. */
    public SeasonPhase lastKnown() {
        final Known current = known.get();
        return current == null ? SeasonPhase.MAINTENANCE : current.phase();
    }

    /**
     * Returns the phase and the opening time. Before the first read it is {@link SeasonPhase#MAINTENANCE} without a
     * time.
     */
    public Known known() {
        final Known current = known.get();
        return current == null ? new Known(SeasonPhase.MAINTENANCE, null) : current;
    }

    /** Returns the opening time, or empty without a date. */
    public Optional<Instant> launch() {
        return Optional.ofNullable(known().launch());
    }

    /** Returns whether the phase was read once. */
    public boolean everRead() {
        return known.get() != null;
    }

    private void notifyListener(final @Nullable SeasonPhase previous, final SeasonPhase current) {
        try {
            listener.phaseChanged(previous, current);
        } catch (final RuntimeException exception) {
            logger.error("A phase change listener failed for {} -> {}", previous, current, exception);
        }
    }
}
