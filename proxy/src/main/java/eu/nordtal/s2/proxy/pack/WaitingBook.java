package eu.nordtal.s2.proxy.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.proxy.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * What the proxy knows about each waiting-room player, and the rule that turns it into a {@link WaitingDecision}.
 *
 * State is per session: Velocity delivers {@code READY} before the arrival, so a per-visit flag would drop it.
 */
public final class WaitingBook {

    /** Everything known about one player between their login and their disconnect. */
    private static final class Session {

        /** Whether the proxy believes they are in the waiting room. */
        private boolean waiting;
        /** Set by {@link #releaseFailed}: the destination is registered but did not take them. */
        private @Nullable Instant backendDownUntil;
        /** Which destination that was; the window applies to it alone. */
        private @Nullable String backendDown;
        /** Whether {@link #entered} has ever been called, which makes a READY "early". */
        private boolean visited;

        private @Nullable Instant offeredAt;

        private boolean applied;

        /** Whether {@code limbo} has said {@code READY} at any point this session. */
        private boolean ready;

        /** When the wait last came down to {@code READY} alone. */
        private @Nullable Instant settledAt;

        /** The reason on this player's screen, so an unchanged one is not re-sent. */
        private @Nullable WaitReason shown;
    }

    private final boolean packOffered;
    private final Duration applyTimeout;
    private final Duration readyGrace;
    private final ProxyRole role;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Takes the pack setting, both periods and the proxy's role.
     *
     * @param packOffered whether there is a pack to wait for, {@code pack.yml#enabled}
     * @param readyGrace how long the rest may be settled before release without {@code limbo}'s confirmation
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

    /**
     * Records that the player is now in the waiting room.
     *
     * @param uuid the player
     */
    public void entered(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.waiting = true;
            session.visited = true;
        }
    }

    /**
     * Records that the player left the waiting room; the session's facts survive.
     *
     * @param uuid the player
     */
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

    /**
     * Claims the one pack offer this session gets.
     *
     * @return whether the caller should send it; a player bounced back is not asked twice
     */
    public boolean claimOffer(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            if (session.offeredAt != null || session.applied) {
                return false;
            }
            session.offeredAt = clock.instant();
            return true;
        }
    }

    /**
     * Records that the client applied the pack.
     *
     * @param uuid the player
     */
    public void packApplied(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.applied = true;
        }
    }

    /**
     * Records {@code limbo}'s {@code READY}, whether or not the arrival has been seen yet.
     *
     * @return whether it came before the arrival, the race described on this class
     */
    public boolean ready(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.ready = true;
            return !session.waiting && !session.visited;
        }
    }

    /** How long a failed release waits before the connection is tried again. */
    public static final Duration RELEASE_RETRY = Duration.ofSeconds(10);

    /**
     * Puts the player back on the books after a release's connection failed.
     *
     * @param destination the backend that refused them; the retry window applies to it alone
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

    /**
     * Drops everything known about a player; called on disconnect.
     *
     * @param uuid the player who has gone
     */
    public void forget(final UUID uuid) {
        sessions.remove(uuid);
    }

    /** How many sessions are on the books. */
    public int size() {
        return sessions.size();
    }

    // the decision

    /**
     * Says what should happen to one held player; cheap and idempotent.
     *
     * @param destination the backend's name, so a retry window set for another is not applied
     * @return what to do; a release or time-out ends the visit, so a concurrent caller gets {@code IDLE}
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

            final boolean packSettled = !packOffered || session.applied;
            if (!packSettled && timedOut(session)) {
                session.waiting = false;
                return WaitingDecision.timedOut();
            }

            // A destination that refused the last connection counts as unavailable until the retry window passes.
            final boolean stillDown = session.backendDownUntil != null
                    && java.util.Objects.equals(session.backendDown, destination)
                    && clock.instant().isBefore(session.backendDownUntil);
            final boolean available = destinationAvailable && !stillDown;
            final Optional<WaitReason> reason = LimboHold.reason(
                    packSettled, phase, admin, role.isStandby(), available, destinationUpdating, destinationHeld);
            if (reason.isPresent()) {
                // Something other than READY is in the way, so the grace period restarts.
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
                // The first moment READY is the only thing left; silent to avoid a flicker.
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
