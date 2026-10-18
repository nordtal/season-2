package eu.nordtal.season.proxy.pack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Lets one player at a time leave the waiting room for {@code smp}, one per interval, first ready first. */
public final class AccessQueue {

    private final String server;
    private final Supplier<Duration> interval;
    private final Clock clock;

    /** The players ready to go, in the order they became ready. */
    private final SequencedSet<UUID> waiting = new LinkedHashSet<>();

    private Instant lastRelease = Instant.MIN;
    private Instant previousRelease = Instant.MIN;

    /** The last player let go, whose slot a failed connection gives back. */
    private @Nullable UUID released;

    /**
     * @param server the backend the queue guards
     * @param interval the time between two releases, read at every release so a change applies at once
     */
    public AccessQueue(final String server, final Supplier<Duration> interval, final Clock clock) {
        this.server = Objects.requireNonNull(server, "server");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns whether the player, ready to leave for the destination, may go now; otherwise they join the queue. */
    public synchronized boolean mayGo(final UUID uuid, final String destination) {
        if (!destination.equals(server)) {
            return true;
        }
        waiting.add(uuid);
        final Instant now = clock.instant();
        if (!uuid.equals(waiting.getFirst()) || now.isBefore(lastRelease.plus(interval.get()))) {
            return false;
        }
        waiting.removeFirst();
        released = uuid;
        previousRelease = lastRelease;
        lastRelease = now;
        return true;
    }

    /** Gives back the slot of a release whose connection failed. */
    public synchronized void refund(final UUID uuid) {
        if (uuid.equals(released)) {
            released = null;
            lastRelease = previousRelease;
        }
    }

    /** Removes the player, who no longer waits for the destination. */
    public synchronized void leave(final UUID uuid) {
        waiting.remove(uuid);
    }

    /** Returns whether anybody waits for a slot. */
    public synchronized boolean isWaiting() {
        return !waiting.isEmpty();
    }
}
