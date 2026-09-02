package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.update.UpdateStatus;

import org.jetbrains.annotations.Nullable;

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
 *
 * <h2>A vanished row is not a cancellation - finding 39, 2026-09-03</h2>
 * It was read as one until the first deployment, and that made the countdown lie about every
 * restart it ever counted down. The proxy polls for a {@code PENDING} restart every five seconds;
 * the updater sleeps until exactly {@code not_before} and claims the row on the instant. So the last
 * poll sees one second left, the row goes {@code PENDING -> RUNNING}, and the next poll finds
 * nothing pending with the counter still above zero - which the old rule called a cancellation.
 * <b>Every successful restart was announced as called off, and {@code restart.now} was unreachable:</b>
 * it needed a poll to land inside the window between the counter reaching zero and the updater
 * claiming, which is a fraction of a second wide.
 *
 * <p>The row does not disappear, it changes status, so {@link #gone(UpdateStatus)} takes the status
 * and says what actually happened. Nothing here guesses from timing any more.
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
     * @return the request this countdown is following, or {@code null} when none. The caller needs
     *         it to look the row up once it stops being pending
     */
    public @Nullable Long watching() {
        return watching;
    }

    /**
     * No restart is pending any more, and this is what became of the row.
     *
     * @param status what the row says now, or {@code null} when it is gone from the table entirely
     * @return what to say, or empty when there is nothing worth saying - a countdown that was never
     *         running, or one that had already reached zero and announced itself
     */
    public Optional<Announcement> gone(final @Nullable UpdateStatus status) {
        final boolean wasCounting = watching != null && remaining > 0L;
        watching = null;
        announced.clear();
        remaining = -1L;

        if (!wasCounting) {
            return Optional.empty();
        }
        if (status == null) {
            // The row was deleted rather than finished. Nothing is going to happen, which is what a
            // cancellation means to the person reading it.
            return Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
        }
        return switch (status) {
            case RUNNING, DONE -> Optional.of(new Announcement(Announcement.Kind.NOW, 0L));
            case FAILED -> Optional.of(new Announcement(Announcement.Kind.FAILED, 0L));
            case CANCELLED -> Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
            // Unreachable: the caller only gets here because no PENDING restart was found. Silent
            // rather than a guess, and the next pass asks again.
            case PENDING -> Optional.empty();
        };
    }
}
