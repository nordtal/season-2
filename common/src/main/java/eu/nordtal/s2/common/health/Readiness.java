package eu.nordtal.s2.common.health;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The file a process touches to say "I started, and I am still here".
 *
 * <h2>What it is for</h2>
 * The container healthcheck of every service in this stack answers a question nothing else can:
 * whether the JVM inside it is doing its job. Until 2026-09-04 nothing outside a Minecraft JVM
 * reported anything at all, and the one check that existed - a TCP connect on the server port -
 * cannot see the failure that actually happened on the first deployment: {@code smp}'s config threw,
 * the plugin disabled itself, and Paper carried on serving an open port on a world with no season on
 * it. That is why the three Paper plugins take their server down with them
 * ({@code FatalPathsStopTheServerTest}), and this is the other half of the same answer - a signal
 * that is <em>only</em> written by a process that finished starting.
 *
 * <h2>The two rules, and they are the whole point</h2>
 * <ol>
 *   <li><b>The first refresh happens only after startup fully succeeded</b> - at the end of
 *       {@code onEnable}, never at the top of it. A marker written before the config is read proves
 *       that a JVM exists, which is what the port already proved.</li>
 *   <li><b>It is refreshed on a timer and stops when the process stops.</b> A marker written once
 *       stays green for as long as the container's filesystem does, so a process that dies after a
 *       good start would still look healthy. {@link #BEAT} in, {@link #STALE_AFTER} out: three
 *       missed beats and the container is no longer healthy.</li>
 * </ol>
 *
 * <h2>Why it deals in {@link Path} and nothing else</h2>
 * {@code :common} is compiled against neither Paper nor Velocity, and two of the five processes that
 * use this are not Paper plugins at all. Scheduling therefore stays with the caller - each platform
 * has its own scheduler, and which one is the right one is a decision only the caller can take. On
 * Paper that is deliberately Bukkit's <em>async</em> scheduler: its repeating tasks are re-queued by
 * the main-thread heartbeat, so a server frozen mid-tick stops beating and goes stale, which a port
 * check would never notice.
 *
 * <h2>What is written, and what is read</h2>
 * The healthcheck reads the modification time, not the content - which is why the refresh writes the
 * file rather than only creating it. The content is the instant of the last beat, in ISO-8601, so
 * that {@code cat /tmp/nordtal-ready} during a drill answers "when" and not just "yes".
 */
public final class Readiness {

    /**
     * Where the marker goes.
     *
     * <p>{@code /tmp} and not a volume, for the reason {@code updater}'s own marker gives: it has to
     * be false again after a restart, and a readiness marker that outlives the process it describes
     * is worse than none. One process per container, so one path is enough.</p>
     */
    public static final Path MARKER = Path.of("/tmp/nordtal-ready");

    /** How often a healthy process refreshes the marker. */
    public static final Duration BEAT = Duration.ofSeconds(30);

    /**
     * How old the marker may be before the process behind it counts as gone.
     *
     * <p>Three missed beats. Two would make an unlucky GC pause or a busy disk look like a dead
     * process; more would delay every real answer for no gain, since nothing in the stack waits on a
     * Minecraft service being healthy. <b>{@code compose.yml} carries this number as a literal
     * ({@code -lt 90}) and cannot read it from here</b> - it is a shell test in a YAML file - so the
     * two are two copies of one fact, and {@code TopologyTest} is what keeps them equal.</p>
     */
    public static final Duration STALE_AFTER = Duration.ofSeconds(90);

    private final Path marker;
    private final Consumer<String> complaints;

    /**
     * False while the last refresh worked. It exists so that a marker that cannot be written is
     * reported once rather than twice a minute forever - and reported <em>again</em> after a
     * recovery, because a second failure after things looked fine is news.
     */
    private final AtomicBoolean complained = new AtomicBoolean();

    public Readiness(final Path marker, final Consumer<String> complaints) {
        this.marker = marker;
        this.complaints = complaints;
    }

    /**
     * The marker at {@link #MARKER}, which is what every container in this stack uses.
     *
     * @param complaints where a failed refresh is reported - a logger's warn method
     */
    public static Readiness onDefaultPath(final Consumer<String> complaints) {
        return new Readiness(MARKER, complaints);
    }

    /** Where this instance writes. */
    public Path marker() {
        return marker;
    }

    /**
     * Writes the marker, creating it and its parent directories if they are not there.
     *
     * <p>Truncate-and-write rather than a bare touch: the file is created on the first beat anyway,
     * and one call that always does the right thing is cheaper to reason about than a create path
     * and a touch path that have to agree about modification times.</p>
     *
     * <p>Never throws. A process that cannot write this file is still a process doing its job, and
     * killing a running server over its own health reporting would be the wrong trade in every
     * direction. The failure is loud instead, because the consequence - a container that reports
     * unhealthy for ever - looks exactly like the failure this marker exists to catch.</p>
     *
     * @return true when the marker now says this process is alive
     */
    public boolean refresh() {
        try {
            final Path parent = marker.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(marker, Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
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
     * The pure half: is a marker last written at {@code lastBeat} still fresh at {@code now}?
     *
     * <p>This is the same arithmetic {@code compose.yml} does in the shell, and it is deliberately
     * the same in the two places it is easy to get wrong. An age exactly equal to
     * {@code staleAfter} is <b>stale</b>, matching the {@code -lt} in that test. A marker from the
     * future - a clock that jumped, or a file copied in - is fresh, again matching the shell: a
     * negative age is less than any positive window, and inventing a stricter rule here than the
     * thing that actually runs would be two answers to one question.</p>
     *
     * @param lastBeat when the marker was last written
     * @param now      the moment being asked about
     * @param staleAfter the window, normally {@link #STALE_AFTER}
     */
    public static boolean fresh(final Instant lastBeat, final Instant now, final Duration staleAfter) {
        return Duration.between(lastBeat, now).compareTo(staleAfter) < 0;
    }

    /**
     * The same question against a real file: absent or unreadable counts as stale.
     *
     * @return empty when there is no marker to read
     */
    public static Optional<Instant> lastBeat(final Path marker) {
        try {
            return Optional.of(Files.getLastModifiedTime(marker).toInstant());
        } catch (final IOException absentOrUnreadable) {
            // Both answers are the same one: nothing here says a process is alive.
            return Optional.empty();
        }
    }

    /** Convenience over {@link #lastBeat(Path)} and {@link #fresh(Instant, Instant, Duration)}. */
    public static boolean fresh(final Path marker, final Instant now, final Duration staleAfter) {
        return lastBeat(marker).map(beat -> fresh(beat, now, staleAfter)).orElse(false);
    }
}
