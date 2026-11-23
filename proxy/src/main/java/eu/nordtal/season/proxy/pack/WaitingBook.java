package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.proxy.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /** The offers and the applied pack of one {@link PackKind}. */
    private static final class Slot {

        /** The time of the newest offer, for its timeout. */
        private @Nullable Instant offeredAt;

        /** The SHA-1 of the newest pack offered to the player. */
        private @Nullable String offeredSha1;

        /** The SHA-1s of pending offers that a newer one replaced, which go once the client applies them. */
        private final Set<String> replacedSha1s = new HashSet<>();

        /** The SHA-1 of the pack the client last reported as applied. */
        private @Nullable String appliedSha1;

        /** Returns whether {@code packId} names the newest offer; no pack id names the newest language pack. */
        private boolean newest(final PackKind kind, final @Nullable UUID packId) {
            return offeredSha1 != null
                    && (packId == null ? kind == PackKind.LANGUAGE : packId.equals(PackOffer.idOf(offeredSha1)));
        }
    }

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

        /** The packs, one slot for each kind. */
        private final Map<PackKind, Slot> slots = new EnumMap<>(PackKind.class);

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

        Session() {
            for (final PackKind kind : PackKind.values()) {
                slots.put(kind, new Slot());
            }
        }

        private Slot slot(final PackKind kind) {
            return Objects.requireNonNull(slots.get(kind), "every kind has a slot");
        }
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
     * Returns {@code true} when the player is owed the pack {@code sha1} of {@code kind}, which the caller sends.
     *
     * That is when the player is not exempt and the pack is neither applied nor already waiting for an answer.
     */
    public boolean claimOffer(final UUID uuid, final PackKind kind, final String sha1) {
        final Session session = session(uuid);
        synchronized (session) {
            final Slot slot = session.slot(kind);
            if (!session.exempt
                    && sha1.equals(slot.appliedSha1)
                    && slot.offeredSha1 != null
                    && !sha1.equals(slot.offeredSha1)) {
                // The pack went back to the applied one while another waited: that one is replaced, nothing is sent.
                slot.replacedSha1s.add(slot.offeredSha1);
                slot.offeredSha1 = sha1;
                return false;
            }
            if (!PackHash.owed(session.exempt, slot.appliedSha1, slot.offeredSha1, sha1)) {
                return false;
            }
            if (slot.offeredSha1 != null && !slot.offeredSha1.equals(slot.appliedSha1)) {
                slot.replacedSha1s.add(slot.offeredSha1);
            }
            slot.offeredSha1 = sha1;
            slot.offeredAt = clock.instant();
            return true;
        }
    }

    /** Returns the SHA-1 of the newest pack of {@code kind} offered to the player, if any. */
    public Optional<String> offeredSha1(final UUID uuid, final PackKind kind) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (session) {
            return Optional.ofNullable(session.slot(kind).offeredSha1);
        }
    }

    /**
     * Returns whether the client's answer about {@code packId} is about the newest offer of one kind.
     * A client that names no pack answers about the newest language pack.
     */
    public boolean answersNewestOffer(final UUID uuid, final @Nullable UUID packId) {
        return newestAnswered(uuid, packId).isPresent();
    }

    /** Returns the SHA-1 of the newest offer if the answer about {@code packId} is about it, read in one step. */
    public Optional<String> newestAnswered(final UUID uuid, final @Nullable UUID packId) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (session) {
            return newestSlot(session, packId).map(slot -> slot.offeredSha1);
        }
    }

    /**
     * The newest offer an answer applied, and the pack applied before it if that was another one.
     *
     * @param before the pack the caller removes from the client, or {@code null}
     */
    public record Applied(String sha1, @Nullable String before) {}

    /**
     * Records the newest offer as applied if the answer about {@code packId} is about it, in one step with the match.
     * A pack change between the two cannot then record an offer the client never answered.
     */
    public Optional<Applied> newestApplied(final UUID uuid, final @Nullable UUID packId) {
        final Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (session) {
            final Optional<Slot> newest = newestSlot(session, packId);
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            final Slot slot = newest.get();
            final String offered = Objects.requireNonNull(slot.offeredSha1, "a newest slot has an offer");
            final String before = slot.appliedSha1;
            slot.appliedSha1 = offered;
            return Optional.of(new Applied(offered, before == null || before.equals(offered) ? null : before));
        }
    }

    /**
     * Returns the SHA-1 of the pending offer a newer one replaced that {@code packId} names, and forgets it.
     * The caller then removes it from the client; a pack a backend sent is never such an offer.
     */
    public Optional<String> takeReplaced(final UUID uuid, final @Nullable UUID packId) {
        final Session session = sessions.get(uuid);
        if (session == null || packId == null) {
            return Optional.empty();
        }
        synchronized (session) {
            for (final Slot slot : session.slots.values()) {
                final Optional<String> replaced = slot.replacedSha1s.stream()
                        .filter(sha1 -> packId.equals(PackOffer.idOf(sha1)))
                        .findFirst();
                if (replaced.isPresent()) {
                    slot.replacedSha1s.remove(replaced.get());
                    return replaced;
                }
            }
            return Optional.empty();
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

    /**
     * Records that the client applied the pack {@code sha1} of {@code kind}.
     *
     * @return the pack applied before, if it was another one, so the caller removes it from the client
     */
    public Optional<String> packApplied(final UUID uuid, final PackKind kind, final String sha1) {
        final Session session = session(uuid);
        synchronized (session) {
            final Slot slot = session.slot(kind);
            final String before = slot.appliedSha1;
            slot.appliedSha1 = sha1;
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

            final boolean packSettled =
                    !packOffered.getAsBoolean() || unsettled(session).isEmpty();
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

    /** Returns whether an offer the player has not applied waited longer than the apply timeout. */
    private boolean timedOut(final Session session) {
        return unsettled(session).stream()
                .map(slot -> slot.offeredAt)
                .filter(Objects::nonNull)
                .anyMatch(at -> Duration.between(at, clock.instant()).compareTo(applyTimeout.get()) >= 0);
    }

    /** Returns the slots that keep the player in the waiting room. */
    private static List<Slot> unsettled(final Session session) {
        return session.slots.entrySet().stream()
                .filter(entry -> !PackHash.settled(
                        entry.getKey(), session.exempt, entry.getValue().appliedSha1, entry.getValue().offeredSha1))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** Returns the slot whose newest offer {@code packId} names. */
    private static Optional<Slot> newestSlot(final Session session, final @Nullable UUID packId) {
        return session.slots.entrySet().stream()
                .filter(entry -> entry.getValue().newest(entry.getKey(), packId))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Session session(final UUID uuid) {
        return sessions.computeIfAbsent(Objects.requireNonNull(uuid, "uuid"), ignored -> new Session());
    }
}
