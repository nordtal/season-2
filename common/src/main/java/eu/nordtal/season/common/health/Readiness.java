package eu.nordtal.season.common.health;

import eu.nordtal.season.common.time.Scheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The file that a process writes after a successful startup and then every {@link #BEAT}.
 * The health check reads its modification time.
 */
public final class Readiness {

    /** The marker file. It lives in {@code /tmp}, so a restart deletes it. */
    public static final Path MARKER = Path.of("/tmp/nordtal-ready");

    public static final Duration BEAT = Duration.ofSeconds(30);

    /** The age from which a marker is stale: three beats. {@code compose.yml} repeats it as {@code -lt 90}. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(90);

    private final Path marker;
    private final Consumer<String> complaints;

    private final AtomicBoolean complained = new AtomicBoolean();

    private final Clock clock;

    public Readiness(final Path marker, final Clock clock, final Consumer<String> complaints) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.marker = marker;
        this.complaints = complaints;
    }

    /** Returns a readiness that writes to {@link #MARKER}. {@code complaints} receives the report of a failed write. */
    public static Readiness onDefaultPath(final Clock clock, final Consumer<String> complaints) {
        return new Readiness(MARKER, clock, complaints);
    }

    public Path marker() {
        return marker;
    }

    /**
     * Writes the marker and its parent directories.
     * A failure is logged once and never thrown.
     * Returns whether the write succeeded.
     */
    public boolean refresh() {
        try {
            final Path parent = marker.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(marker, clock.instant() + System.lineSeparator(), StandardCharsets.UTF_8);
            complained.set(false);
            return true;
        } catch (final IOException | RuntimeException failure) {
            if (complained.compareAndSet(false, true)) {
                complaints.accept("Could not write the readiness marker " + marker + " (" + failure
                        + "). This process is running, but its container will report unhealthy until"
                        + " the marker can be written again.");
            }
            return false;
        }
    }

    /**
     * Writes the marker now and then every {@link #BEAT} on {@code scheduler}.
     * Returns whether the first write succeeded.
     */
    public boolean keepBeating(final Scheduler scheduler) {
        final boolean written = refresh();
        final var _ = scheduler.every(BEAT, BEAT, this::refresh);
        return written;
    }

    /**
     * Returns whether a marker written at {@code lastBeat} is fresh at {@code now}.
     * An age equal to {@code staleAfter} is stale.
     */
    public static boolean fresh(final Instant lastBeat, final Instant now, final Duration staleAfter) {
        return Duration.between(lastBeat, now).compareTo(staleAfter) < 0;
    }

    /** Returns when the marker was last written, or empty if it is missing or unreadable. */
    public static Optional<Instant> lastBeat(final Path marker) {
        try {
            return Optional.of(Files.getLastModifiedTime(marker).toInstant());
        } catch (final IOException absentOrUnreadable) {
            // Both answers are the same one: nothing here says a process is alive.
            return Optional.empty();
        }
    }

    public static boolean fresh(final Path marker, final Instant now, final Duration staleAfter) {
        return lastBeat(marker).map(beat -> fresh(beat, now, staleAfter)).orElse(false);
    }
}
