package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the proxy knows about each player in the waiting room, and the rule that turns it into a
 * {@link WaitingDecision}. No Velocity type is involved, because the ordering this class exists to
 * get right cannot be asserted through a connection.
 *
 * <p><b>State is per session, not per visit.</b> The three facts that end a wait - the arrival, the
 * pack status, and {@code limbo}'s {@code READY} - arrive on unrelated paths in any order. Velocity
 * makes {@code READY} beat the arrival by construction: {@code TransitionSessionHandler} stops
 * reading from the backend socket on join, and the packets buffered meanwhile are read on the Netty
 * loop before {@code ServerPostConnectEvent} is dispatched. Keeping {@code ready} in an object the
 * arrival creates therefore drops it, and {@code limbo} sends it once per join - which strands the
 * player for ever, since no timeout applies after the pack is applied.</p>
 *
 * <p>The deliberate cost: {@code ready} is <b>not</b> cleared when a player leaves the waiting room
 * and returns within the same session, so a second visit is released without a second
 * {@code READY}. Clearing it would reintroduce the window this class removes.</p>
 *
 * <p><b>The grace period</b> covers a {@code READY} that is genuinely lost: a plugin message decoded
 * in the same read batch as the join is written straight to the client by
 * {@code TransitionSessionHandler} and never becomes a {@code PluginMessageEvent}. {@link #decide}
 * therefore releases a player once everything else has been settled for
 * {@code gate.yml#limbo-ready-grace-seconds}, and says so. No single message may strand a player.</p>
 *
 * <p>Every public method is safe to call from any thread. {@link #decide} synchronises on the
 * session, which is what makes a release happen once: a sweep and a pack status arriving together
 * would otherwise both pass the same checks and connect the same player twice.</p>
 */
public final class WaitingBook {

    /** Everything known about one player between their login and their disconnect. */
    private static final class Session {

        /** Whether the proxy currently believes they are sitting in the waiting room. */
        private boolean waiting;
        /** Set by {@link #releaseFailed}: the destination is registered but did not take them. */
        private Instant backendDownUntil;
        /** Which destination that was - the window applies to it and to no other. */
        private String backendDown;
        /** Whether {@link #entered} has ever been called - what makes a READY "early". */
        private boolean visited;

        /** When the pack offer went out, or {@code null} if it has not. */
        private Instant offeredAt;

        /** Whether the client reported the pack as applied. */
        private boolean applied;

        /** Whether {@code limbo} has said {@code READY} at any point this session. */
        private boolean ready;

        /** When the wait last came down to {@code READY} alone; {@code null} whenever it has not. */
        private Instant settledAt;

        /** The reason currently on this player's screen, so an unchanged one is not re-sent. */
        private WaitReason shown;
    }

    private final boolean packOffered;
    private final Duration applyTimeout;
    private final Duration readyGrace;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param packOffered  whether there is a pack to wait for at all - {@code pack.yml#enabled}.
     *                     When false the wait has one fewer thing in it and no timeout to enforce
     * @param applyTimeout how long a player may sit with an unanswered pack offer
     * @param readyGrace   how long everything else may be settled before the player is released
     *                     without {@code limbo}'s confirmation
     * @param clock        the clock both periods are measured on
     */
    public WaitingBook(final boolean packOffered, final Duration applyTimeout,
                       final Duration readyGrace, final Clock clock) {
        this.packOffered = packOffered;
        this.applyTimeout = Objects.requireNonNull(applyTimeout, "applyTimeout");
        this.readyGrace = Objects.requireNonNull(readyGrace, "readyGrace");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ the three facts

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
     * Records that the player is no longer in the waiting room - released, moved, or on their way
     * out. The session's facts survive; only this visit's does.
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
     * @param uuid the player
     * @return {@code true} if the caller should actually send the offer, {@code false} if it has
     *         already gone out. A player bounced back into the waiting room by a phase change is
     *         not asked a second time for a pack they already have
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
     * Records {@code limbo}'s {@code READY}, whether or not the arrival event has been seen yet.
     *
     * @param uuid the player
     * @return {@code true} when this {@code READY} arrived <b>before</b> the proxy had processed the
     *         arrival - the race described on this class, and worth a log line. A READY after the
     *         player has already left the room is <em>not</em> early: {@code limbo} repeats it every
     *         second until the player is moved, so one arriving mid-transfer is ordinary
     */
    public boolean ready(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.ready = true;
            return !session.waiting && !session.visited;
        }
    }

    /** How long a release that failed keeps a player waiting before the connection is tried again. */
    public static final Duration RELEASE_RETRY = Duration.ofSeconds(10);

    /**
     * Records that the connection a release asked for did not succeed, and puts the player back on
     * the books.
     * <p>
     * The station releases a player once the destination is <em>registered</em>; only the connection
     * attempt can say whether it is <em>up</em>. A backend that is down must hold rather than kick,
     * so the player is shown the {@code BACKEND} title and the release is tried again after
     * {@link #RELEASE_RETRY}.
     * </p>
     *
     * @param uuid        the player, still standing on limbo
     * @param destination the backend that did not take them. The window is recorded against it and
     *                    applies to nothing else: a phase switched inside the window points at a
     *                    different server, and a player must not be held away from a backend that
     *                    never refused them
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
     * Drops everything known about a player. Called on disconnect, or the map grows for the life of
     * the process.
     *
     * @param uuid the player who has gone
     */
    public void forget(final UUID uuid) {
        sessions.remove(uuid);
    }

    /** @return how many sessions are on the books, for tests and for a future admin surface */
    public int size() {
        return sessions.size();
    }

    // ------------------------------------------------------------------ the decision

    /**
     * Looks at one held player and says what should happen to them.
     * <p>
     * Called on every pack status, every arrival, every {@code READY} and every sweep, so it must be
     * cheap and idempotent.
     * </p>
     *
     * @param uuid                 the player
     * @param phase                the phase the network is in
     * @param admin                whether the player carries {@code discord_user.admin} - maintenance
     *                             does not hold an admin, see {@link LimboHold}
     * @param destinationAvailable whether the backend that phase points at is registered
     * @param destination          its name, so a retry window set for one backend is not applied to
     *                             another one the phase has since moved to
     * @return what to do. A {@code RELEASE}, {@code RELEASE_UNCONFIRMED} or {@code TIMED_OUT} also
     *         ends the visit, so a second concurrent caller gets {@code IDLE} and the player is not
     *         connected onward twice
     */
    public WaitingDecision decide(final UUID uuid, final SeasonPhase phase, final boolean admin,
                                  final boolean destinationAvailable, final String destination) {
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

            // A destination that refused the last connection counts as unavailable until the retry
            // window has passed - registered is not the same as up. Scoped to that destination: a
            // phase change inside the window points somewhere else, and an unscoped window would
            // hold the player away from a server that never refused them.
            final boolean stillDown = session.backendDownUntil != null
                    && java.util.Objects.equals(session.backendDown, destination)
                    && clock.instant().isBefore(session.backendDownUntil);
            final boolean available = destinationAvailable && !stillDown;
            final Optional<WaitReason> reason =
                    LimboHold.reason(packSettled, phase, admin, available);
            if (reason.isPresent()) {
                // Something other than READY is still in the way, so the grace period restarts.
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
                // First moment at which READY is the only thing left. Deliberately silent: a title
                // sent now would flicker as it is about to disappear.
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
        return sessions.computeIfAbsent(Objects.requireNonNull(uuid, "uuid"),
                ignored -> new Session());
    }
}
