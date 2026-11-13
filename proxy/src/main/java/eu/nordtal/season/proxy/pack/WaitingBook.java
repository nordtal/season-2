package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.proxy.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Stores the state of each player in the waiting room and decides with it.
 * The state lasts for the session of the player.
 */
public final class WaitingBook {

    /** The state of a player from login to disconnect. */
    private static final class Session {

        /** Whether the player is in the waiting room. */
        private boolean waiting;
        /** The end of the window after {@link #releaseFailed}. */
        private @Nullable Instant backendDownUntil;
        /** The backend of that window. */
        private @Nullable String backendDown;
        /** Whether {@link #entered} was called. */
        private boolean visited;

        /** The time of the newest offer, for its timeout. */
        private @Nullable Instant offeredAt;

        /** The SHA-1 of the newest pack offered to the player. */
        private @Nullable String offeredSha1;

        /** The SHA-1 of the pack the client last reported as applied. */
        private @Nullable String appliedSha1;

        /** Whether an admin exempted the player from the pack. */
        private boolean exempt;

        /** Whether {@code limbo} sent {@code READY}. */
        private boolean ready;

        /** The time since which only {@code READY} is missing. */
        private @Nullable Instant settledAt;

        /** The reason on the screen of the player. */
        private @Nullable WaitReason shown;

        /** The percentage sent with {@link #shown}, {@code -1} for none. */
        private int percent = -1;
    }

    private final BooleanSupplier packOffered;
    private final Supplier<Duration> applyTimeout;
    private final Duration readyGrace;
    private final ProxyRole role;
    private final Clock clock;

    /** Lets the released players onto {@code smp} one at a time. */
    private final AccessQueue queue;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param packOffered whether {@code pack#enabled} is on
     * @param readyGrace the time after which a player is released without {@code READY}
     */
    public WaitingBook(
            final boolean packOffered,
            final Duration applyTimeout,
            final Duration readyGrace,
            final ProxyRole role,
            final AccessQueue queue,
            final Clock clock) {
        this(() -> packOffered, () -> applyTimeout, readyGrace, role, queue, clock);
    }

    /**
     * Reads whether the pack is offered and its timeout at each decision, so a changed setting counts at once.
     *
     * @param readyGrace the time after which a player is released without {@code READY}
     */
    public WaitingBook(
            final BooleanSupplier packOffered,
            final Supplier<Duration> applyTimeout,
            final Duration readyGrace,
            final ProxyRole role,
            final AccessQueue queue,
            final Clock clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.packOffered = Objects.requireNonNull(packOffered, "packOffered");
        this.applyTimeout = Objects.requireNonNull(applyTimeout, "applyTimeout");
        this.readyGrace = Objects.requireNonNull(readyGrace, "readyGrace");
        this.role = Objects.requireNonNull(role, "role");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // the three facts

    /** Records that the player is in the waiting room. */
    public void entered(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.waiting = true;
            session.visited = true;
        }
    }

    /** Records that the player left the waiting room. The other state of the session stays. */
    public void left(final UUID uuid) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.waiting = false;
            session.settledAt = null;
            session.shown = null;
        }
        queue.leave(uuid);
    }

    /**
     * Returns {@code true} when the player is to be offered the pack {@code sha1}, and the caller sends it.
     *
     * That is when the player is not exempt and the pack is neither applied nor already waiting for an answer.
     */
    public boolean claimOffer(final UUID uuid, final String sha1) {
        final Session session = session(uuid);
        synchronized (session) {
            if (!PackHash.owed(session.exempt, session.appliedSha1, session.offeredSha1, sha1)) {
                return false;
            }
            session.offeredSha1 = sha1;
            session.offeredAt = clock.instant();
            return true;
        }
    }

    /** Returns the SHA-1 of the newest pack offered to the player, if any. */
    public Optional<String> offeredSha1(final UUID uuid) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (session) {
            return Optional.ofNullable(session.offeredSha1);
        }
    }

    /**
     * Returns whether the client's answer about {@code packId} is about the newest pack offered to the player.
     * A client that names no pack answers about the newest.
     */
    public boolean answersNewestOffer(final UUID uuid, final @Nullable UUID packId) {
        final Optional<String> offered = offeredSha1(uuid);
        return offered.isPresent() && (packId == null || packId.equals(PackOffer.idOf(offered.get())));
    }

    /**
     * Records that an admin exempted the player from the pack.
     *
     * @return {@code true} the first time in the session
     */
    public boolean packExempt(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            final boolean first = !session.exempt;
            session.exempt = true;
            return first;
        }
    }

    /**
     * Records that the client applied the pack {@code sha1}.
     *
     * @return the pack applied before, if it was another one, so the caller removes it from the client
     */
    public Optional<String> packApplied(final UUID uuid, final String sha1) {
        final Session session = session(uuid);
        synchronized (session) {
            final String before = session.appliedSha1;
            session.appliedSha1 = sha1;
            return before == null || before.equals(sha1) ? Optional.empty() : Optional.of(before);
        }
    }

    /**
     * Records {@code READY} of {@code limbo}.
     *
     * @return whether {@code READY} came before {@link #entered}
     */
    public boolean ready(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.ready = true;
            return !session.waiting && !session.visited;
        }
    }

    /** The time before the connection is tried again after a failed release. */
    public static final Duration RELEASE_RETRY = Duration.ofSeconds(10);

    /**
     * Returns the player to the waiting list after a failed release.
     *
     * @param destination the backend that refused the connection
     */
    public void releaseFailed(final UUID uuid, final String destination) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.waiting = true;
            session.settledAt = null;
            session.backendDown = destination;
            session.backendDownUntil = clock.instant().plus(RELEASE_RETRY);
        }
        queue.refund(uuid);
    }

    public boolean isWaiting(final UUID uuid) {
        final Session session = uuid == null ? null : sessions.get(uuid);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            return session.waiting;
        }
    }

    /** Deletes the state of the player. */
    public void forget(final UUID uuid) {
        sessions.remove(uuid);
        queue.leave(uuid);
    }

    /**
     * Records the percentage of the running update for a player who sees its screen.
     *
     * @return {@code true} when the player sees the update screen with another number, so the caller sends it
     */
    public boolean claimProgress(final UUID uuid, final int percent) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (session.shown != WaitReason.UPDATE || session.percent == percent) {
                return false;
            }
            session.percent = percent;
            return true;
        }
    }

    /**
     * Puts a released player back on the waiting list with the reason on the screen.
     *
     * @return whether the player has a session, so the caller sends the screen
     */
    public boolean heldAgain(final UUID uuid, final WaitReason reason) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            session.waiting = true;
            session.settledAt = null;
            session.shown = reason;
            session.percent = -1;
        }
        queue.refund(uuid);
        return true;
    }

    /** Returns whether a released player waits for a slot onto {@code smp}. */
    public boolean anyQueued() {
        return queue.isWaiting();
    }

    /** Returns the number of sessions. */
    public int size() {
        return sessions.size();
    }

    // the decision

    /**
     * Returns the decision for the player.
     * A release or a timeout ends the visit, so the next call returns {@code IDLE}.
     *
     * @param hold what the season holds
     * @param destination the server of the active mode
     */
    public WaitingDecision decide(
            final UUID uuid,
            final SeasonHold hold,
            final boolean admin,
            final boolean destinationAvailable,
            final String destination,
            final boolean destinationUpdating,
            final boolean destinationHeld) {
        Objects.requireNonNull(hold, "hold");
        final Session session = sessions.get(uuid);
        if (session == null) {
            return WaitingDecision.idle();
        }

        synchronized (session) {
            if (!session.waiting) {
                return WaitingDecision.idle();
            }

            final boolean packSettled = !packOffered.getAsBoolean()
                    || PackHash.settled(session.exempt, session.appliedSha1, session.offeredSha1);
            if (!packSettled && timedOut(session)) {
                session.waiting = false;
                return WaitingDecision.timedOut();
            }

            final boolean stillDown = session.backendDownUntil != null
                    && java.util.Objects.equals(session.backendDown, destination)
                    && clock.instant().isBefore(session.backendDownUntil);
            final boolean available = destinationAvailable && !stillDown;
            final Optional<WaitReason> reason = LimboHold.reason(
                    packSettled, hold, admin, role.isStandby(), available, destinationUpdating, destinationHeld);
            if (reason.isPresent()) {
                session.settledAt = null;
                // A player held back for another reason would otherwise keep the head of the queue.
                queue.leave(uuid);
                if (reason.get() == session.shown) {
                    return WaitingDecision.idle();
                }
                session.shown = reason.get();
                session.percent = -1;
                return WaitingDecision.show(reason.get());
            }

            if (session.ready) {
                return release(session, uuid, destination, true);
            }

            final Instant now = clock.instant();
            if (session.settledAt == null) {
                session.settledAt = now;
                return WaitingDecision.idle();
            }
            if (Duration.between(session.settledAt, now).compareTo(readyGrace) < 0) {
                return WaitingDecision.idle();
            }

            return release(session, uuid, destination, false);
        }
    }

    /** Releases the player unless the queue onto the destination holds them back. */
    private WaitingDecision release(
            final Session session, final UUID uuid, final String destination, final boolean ready) {
        if (!queue.mayGo(uuid, destination)) {
            if (session.shown == WaitReason.QUEUE) {
                return WaitingDecision.idle();
            }
            session.shown = WaitReason.QUEUE;
            return WaitingDecision.show(WaitReason.QUEUE);
        }
        session.waiting = false;
        return WaitingDecision.release(ready);
    }

    private boolean timedOut(final Session session) {
        return session.offeredAt != null
                && Duration.between(session.offeredAt, clock.instant()).compareTo(applyTimeout.get()) >= 0;
    }

    private Session session(final UUID uuid) {
        return sessions.computeIfAbsent(Objects.requireNonNull(uuid, "uuid"), ignored -> new Session());
    }
}
