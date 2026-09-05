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
 * {@link WaitingDecision}. Everything {@link PackStation} used to keep in two maps of its own lives
 * here instead, without a single Velocity type - which is the whole point, because the ordering
 * this class exists to get right cannot be asserted through a connection.
 *
 * <h2>Why the state is per <em>session</em> and not per visit</h2>
 * This is <b>finding 38</b>, and it cost an evening of a deployment. The three facts that end a
 * wait arrive on three unrelated paths, and <b>none of them is ordered against the others</b>:
 * <ul>
 *   <li>the arrival, as {@code ServerPostConnectEvent};</li>
 *   <li>the pack status, as {@code PlayerResourcePackStatusEvent};</li>
 *   <li>{@code limbo}'s {@code READY}, as a plugin message.</li>
 * </ul>
 * Velocity 4.1.1 makes the first two race the third by construction. Read
 * {@code TransitionSessionHandler#handle(JoinGamePacket)}: on the backend's join it stops reading
 * from that socket, fires {@code ServerConnectedEvent} and <em>waits</em>; the continuation then
 * installs the play-session handler, turns reading <b>back on</b>, and only afterwards calls
 * {@code fireAndForget(new ServerPostConnectEvent(...))}. So the packets {@code limbo} sent while
 * the proxy was not reading - which is exactly where a {@code READY} sent one tick after
 * {@code PlayerJoinEvent} lands - are read on the Netty loop <b>before</b> the arrival event has
 * been dispatched to anybody, and the two then reach this plugin on two different threads in
 * whichever order the executor feels like.
 *
 * <p>The old code kept {@code ready} in an object created by the arrival. A {@code READY} that won
 * the race found no object, was dropped, and was never sent again - {@code limbo} sends it once per
 * join. The player then sat on a black screen <b>for ever</b>: no timeout applies once the pack is
 * applied, the sweep re-asks a question whose answer cannot change, and every log line on the path
 * is on a branch that was not taken. Recording the three facts against the session, so that any
 * order produces the same answer, is the fix.
 *
 * <p>The deliberate cost: {@code ready} is <b>not</b> cleared when a player leaves the waiting room
 * and comes back within the same session, so a second visit is released without waiting for a second
 * {@code READY}. That is the right trade - the client demonstrably finished joining {@code limbo}
 * the first time, and the alternative is to reintroduce exactly the window this class removes.
 *
 * <h2>The grace period</h2>
 * Recording {@code READY} in the right place fixes the race that was measured. It does not fix a
 * {@code READY} that is genuinely <em>lost</em>, and one path in Velocity loses it: a plugin message
 * decoded in the same read batch as the join is handled by {@code TransitionSessionHandler}, which
 * writes it to the client and never consults the channel registrar, so it never becomes a
 * {@code PluginMessageEvent} at all. {@link #decide} therefore releases a player anyway once
 * everything else has been true for {@code gate.yml#limbo-ready-grace-seconds}, and says so.
 * <b>No single message may be able to strand a player.</b>
 *
 * <h2>Threading</h2>
 * Every public method is safe to call from any thread. {@link #decide} synchronises on the session
 * it is deciding about, which is what makes a release happen once: the sweep and a pack status can
 * arrive together, and without the lock both would pass the same checks and connect the same player
 * twice.
 */
public final class WaitingBook {

    /** Everything known about one player between their login and their disconnect. */
    private static final class Session {

        /** Whether the proxy currently believes they are sitting in the waiting room. */
        private boolean waiting;
        /** Set by {@link #releaseFailed}: the destination is registered but did not take them. */
        private Instant backendDownUntil;
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
     *         arrival - the race described on this class. Worth a log line: it is the only evidence
     *         that the ordering is really the way round the fix assumes, and its absence was what
     *         made the original failure invisible. A READY after the player has already left the
     *         room is <em>not</em> early - {@code limbo} repeats its READY every second until the
     *         player is moved (2026-09-05), so one arriving mid-transfer is the ordinary case and
     *         says nothing
     */
    public boolean ready(final UUID uuid) {
        final Session session = session(uuid);
        synchronized (session) {
            session.ready = true;
            return !session.waiting && !session.visited;
        }
    }

    /**
     * @param uuid a player
     * @return whether they are being held in the waiting room right now
     */
    /** How long a release that failed keeps a player waiting before the connection is tried again. */
    public static final Duration RELEASE_RETRY = Duration.ofSeconds(10);

    /**
     * Records that the connection a release asked for did not succeed, and puts the player back on
     * the books.
     * <p>
     * The station releases a player once the destination is <em>registered</em>; whether it is
     * <em>up</em> only the connection attempt can say. Until 2026-09-05 a failed attempt was a
     * disconnect with the "no server" screen - so a backend restarting for twenty seconds threw out
     * everybody who arrived during those twenty seconds, and Velocity's own kick-to-fallback put
     * everybody who was <em>on</em> it through the same door a moment later. docs/season-phases.md
     * says a backend that is down holds rather than kicks; this is what makes that true. The player
     * is shown the {@code BACKEND} title and the release is tried again after
     * {@link #RELEASE_RETRY}, for as long as they care to wait.
     * </p>
     *
     * @param uuid the player, still standing on limbo
     */
    public void releaseFailed(final UUID uuid) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.waiting = true;
            session.settledAt = null;
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
     * cheap and idempotent: it touches nothing outside the session and returns {@link
     * WaitingDecision#idle()} for the overwhelmingly common case of a player who is already looking
     * at the right title.
     * </p>
     *
     * @param uuid                 the player
     * @param phase                the phase the network is in
     * @param admin                whether the player carries {@code discord_user.admin} - maintenance
     *                             does not hold an admin, see {@link LimboHold}
     * @param destinationAvailable whether the backend that phase points at is registered
     * @return what to do. A {@code RELEASE}, {@code RELEASE_UNCONFIRMED} or {@code TIMED_OUT} also
     *         ends the visit, so a second concurrent caller gets {@code IDLE} and the player is not
     *         connected onward twice
     */
    public WaitingDecision decide(final UUID uuid, final SeasonPhase phase, final boolean admin,
                                  final boolean destinationAvailable) {
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
            // window has passed - registered is not the same as up, and only a failed attempt can
            // tell the two apart.
            final boolean available = destinationAvailable && (session.backendDownUntil == null
                    || !clock.instant().isBefore(session.backendDownUntil));
            final Optional<WaitReason> reason =
                    LimboHold.reason(packSettled, phase, admin, available);
            if (reason.isPresent()) {
                // Something other than READY is still in the way, so the grace period has not
                // started - and if it had, it starts again from here.
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
                // First moment at which READY is the only thing left. Deliberately silent: there is
                // nothing true to tell the player that is not already on their screen, and a title
                // sent now would flicker at exactly the moment it is about to disappear.
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
