package eu.nordtal.s2.networkcontrol.update;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * When to speak during a restart countdown, and what to say - with no proxy, no database and no
 * clock in it.
 *
 * <p>Separated from {@link RestartWatch} because this is the part with rules in it and the part
 * that is worth being sure about. The rules are three:</p>
 *
 * <ol>
 *   <li><b>At most five announcements</b>, at 60, 30, 10 and 5 seconds and then the restart itself.
 *       A message every five seconds for a minute is twelve messages, which is how a warning
 *       becomes something people learn to ignore.</li>
 *   <li><b>The number spoken is what is actually left.</b> The poll runs every five seconds, so the
 *       first pass may see 57 rather than 60 - and saying 60 there would be a lie by three seconds,
 *       about the one thing this exists to be believed about.</li>
 *   <li><b>A countdown joined late does not replay.</b> A proxy that comes up with forty seconds
 *       gone says "40 seconds" once, not "60, then 40" in the same breath.</li>
 * </ol>
 */
public final class Countdown {

    /** When to speak, in seconds remaining, coarse to fine. Zero is the restart itself. */
    private static final List<Long> THRESHOLDS = List.of(60L, 30L, 10L, 5L, 0L);

    /** The request being counted down, so a second one starts a fresh set of announcements. */
    private Long watching;

    private final Set<Long> announced = new LinkedHashSet<>();

    /** What the last look saw. Above zero when the row vanishes means it was cancelled. */
    private long remaining = -1L;

    /**
     * A restart is pending.
     *
     * @param requestId   which one; a different id restarts the bookkeeping
     * @param secondsLeft what is actually left, never negative
     * @return what to say, or empty when this pass has nothing new to add
     */
    public Optional<Announcement> pending(final long requestId, final long secondsLeft) {
        if (watching == null || watching != requestId) {
            watching = requestId;
            announced.clear();
        }
        remaining = secondsLeft;

        for (final Long threshold : THRESHOLDS) {
            if (secondsLeft > threshold || announced.contains(threshold)) {
                continue;
            }
            // Every coarser threshold counts as spoken, which is rule three.
            THRESHOLDS.stream().filter(other -> other >= threshold).forEach(announced::add);
            return Optional.of(threshold == 0L
                    ? new Announcement(Announcement.Kind.NOW, 0L)
                    : new Announcement(Announcement.Kind.COUNTDOWN, secondsLeft));
        }
        return Optional.empty();
    }

    /**
     * No restart is pending any more.
     *
     * @return the cancellation line when a countdown was running and had not reached zero, and
     *         empty otherwise - a countdown that ran out is not a cancellation, and neither is a
     *         quiet network where nothing was ever asked for
     */
    public Optional<Announcement> gone() {
        final boolean cancelled = watching != null && remaining > 0L;
        watching = null;
        announced.clear();
        remaining = -1L;
        return cancelled ? Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L)) : Optional.empty();
    }
}
