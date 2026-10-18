package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.proxy.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

        private @Nullable Instant offeredAt;

        private boolean applied;

        /** Whether an admin exempted the player from the pack. */
        private boolean exempt;

        /** Whether {@code limbo} sent {@code READY}. */
        private boolean ready;

        /** The time since which only {@code READY} is missing. */
        private @Nullable Instant settledAt;

        /** The reason on the screen of the player. */
        private @Nullable WaitReason shown;
    }

    private final boolean packOffered;
    private final Duration applyTimeout;
    private final Duration readyGrace;
    private final ProxyRole role;
    private final Clock clock;

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
            final Clock clock) {
        this.packOffered = packOffered;
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
    }

    /** Returns {@code true} once per session. The caller sends the pack offer then. */
    public boolean claimOffer(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            if (session.offeredAt != null || session.applied || session.exempt) {
                return false;
            }
            session.offeredAt = clock.instant();
            return true;
        }
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

    /** Records that the client applied the pack. */
    public void packApplied(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.applied = true;
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
     * @param destination the backend of the phase
     */
    public WaitingDecision decide(
            final UUID uuid,
            final SeasonPhase phase,
            final boolean admin,
            final boolean destinationAvailable,
            final String destination,
            final boolean destinationUpdating,
            final boolean destinationHeld) {
        Objects.requireNonNull(phase, "phase");
        final Session session = sessions.get(uuid);
        if (session == null) {
            return WaitingDecision.idle();
        }

        synchronized (session) {
            if (!session.waiting) {
                return WaitingDecision.idle();
            }

            final boolean packSettled = !packOffered || session.exempt || session.applied;
            if (!packSettled && timedOut(session)) {
                session.waiting = false;
                return WaitingDecision.timedOut();
            }

            final boolean stillDown = session.backendDownUntil != null
                    && java.util.Objects.equals(session.backendDown, destination)
                    && clock.instant().isBefore(session.backendDownUntil);
            final boolean available = destinationAvailable && !stillDown;
            final Optional<WaitReason> reason = LimboHold.reason(
                    packSettled, phase, admin, role.isStandby(), available, destinationUpdating, destinationHeld);
            if (reason.isPresent()) {
                session.settledAt = null;
                if (reason.get() == session.shown) {
                    return WaitingDecision.idle();
                }
                session.shown = reason.get();
                return WaitingDecision.show(reason.get());
            }

            if (session.ready) {
                session.waiting = false;
                return WaitingDecision.release(true);
            }

            final Instant now = clock.instant();
            if (session.settledAt == null) {
                session.settledAt = now;
                return WaitingDecision.idle();
            }
            if (Duration.between(session.settledAt, now).compareTo(readyGrace) < 0) {
                return WaitingDecision.idle();
            }

            session.waiting = false;
            return WaitingDecision.release(false);
        }
    }

    private boolean timedOut(final Session session) {
        return session.offeredAt != null
                && Duration.between(session.offeredAt, clock.instant()).compareTo(applyTimeout) >= 0;
    }

    private Session session(final UUID uuid) {
        return sessions.computeIfAbsent(Objects.requireNonNull(uuid, "uuid"), ignored -> new Session());
    }
}
