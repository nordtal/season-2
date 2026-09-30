package eu.nordtal.s2.common.health;

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
 * The file a process touches to say it started and is still here.
 * It is first written only after startup fully succeeded and then refreshed every {@link #BEAT}; the healthcheck reads
 * its modification time.
 */
public final class Readiness {

    /** Where the marker goes: {@code /tmp}, so it is gone again after a restart. */
    public static final Path MARKER = Path.of("/tmp/nordtal-ready");

    /** How often a healthy process refreshes the marker. */
    public static final Duration BEAT = Duration.ofSeconds(30);

    /**
     * How old the marker may be before the process behind it counts as gone: three missed beats.
     * {@code compose.yml} repeats it as {@code -lt 90}, and {@code TopologyTest} keeps the two equal.
     */
    public static final Duration STALE_AFTER = Duration.ofSeconds(90);

    private final Path marker;
    private final Consumer<String> complaints;

    /** Whether the last refresh failed, so a failure is logged once and again after a recovery. */
    private final AtomicBoolean complained = new AtomicBoolean();

    private final Clock clock;

    public Readiness(final Path marker, final Clock clock, final Consumer<String> complaints) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.marker = marker;
        this.complaints = complaints;
    }

    /**
     * Returns a readiness writing to {@link #MARKER}, as every container in this stack does.
     *
     * @param complaints where a failed refresh is reported, such as a logger's warn method
     */
    public static Readiness onDefaultPath(final Clock clock, final Consumer<String> complaints) {
        return new Readiness(MARKER, clock, complaints);
    }

    /** Returns where this instance writes. */
    public Path marker() {
        return marker;
    }

    /**
     * Writes the marker, creating it and its parent directories if needed.
     * It never throws: a failure is logged once instead.
     *
     * @return true when the marker now says this process is alive
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
     * Returns whether a marker last written at {@code lastBeat} is still fresh at {@code now}.
     * It matches the {@code -lt} in {@code compose.yml}: an age equal to {@code staleAfter} is stale, a future one
     * fresh.
     */
    public static boolean fresh(final Instant lastBeat, final Instant now, final Duration staleAfter) {
        return Duration.between(lastBeat, now).compareTo(staleAfter) < 0;
    }

    /** Returns when the marker was last written, empty when it is absent or unreadable. */
    public static Optional<Instant> lastBeat(final Path marker) {
        try {
            return Optional.of(Files.getLastModifiedTime(marker).toInstant());
        } catch (final IOException absentOrUnreadable) {
            // Both answers are the same one: nothing here says a process is alive.
            return Optional.empty();
        }
    }

    /** Returns whether the marker file is still fresh at {@code now}. */
    public static boolean fresh(final Path marker, final Instant now, final Duration staleAfter) {
        return lastBeat(marker).map(beat -> fresh(beat, now, staleAfter)).orElse(false);
    }
}
