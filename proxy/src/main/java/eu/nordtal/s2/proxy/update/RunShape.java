package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.proxy.online.OnlineCounts;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a run is about to do, and what that means for one player.
 *
 * @param occasion what to call this, from the seat of somebody standing in the game
 * @param moving the compose services the run stops
 * @param proxyMoves whether this proxy is one of them
 * @param waitingRoom whether a waiting room survives the run
 * @param standbyProxy whether a standby proxy is there to catch the network when this one goes
 */
public record RunShape(
        Occasion occasion, Set<String> moving, boolean proxyMoves, boolean waitingRoom, boolean standbyProxy) {

    /** What a run is called to a player: one per {@link UpdateKind} that stops anything, plus one. */
    public enum Occasion {

        /** {@link UpdateKind#DOWN}: a service goes off and stays off until somebody says otherwise. */
        DOWN,

        /** {@link UpdateKind#RESTART}: a service goes round once. */
        RECREATE,

        /** {@link UpdateKind#BACKUP}: it is being saved, so it has to hold still. */
        BACKUP,

        /** {@link UpdateKind#UPDATE}: new jars are going in. */
        UPDATE,

        /** No waiting room survives this run, so everyone is disconnected; any kind becomes this. */
        MAINTENANCE
    }

    /** What is about to happen to one player, in the order of how much it costs them. */
    public enum Fate {

        /** Nothing. Their server is not in the run and this proxy is not going anywhere. */
        NOTHING,

        /** A loading screen through the standby proxy and back; their own server never stops. */
        RECONNECT,

        /** The waiting room, and back again automatically when their server returns. */
        WAITING_ROOM,

        /** Out of the game. Nothing here can catch them; the countdown is all the warning there is. */
        DISCONNECT
    }

    public RunShape {
        moving = Set.copyOf(moving == null ? Set.of() : moving);
    }

    /**
     * The shape of a run, from the row and what this proxy knows about its own surroundings.
     *
     * @param kind what was asked for
     * @param moving the services the run's own report says are moving
     * @param waitingRoom whether {@link Evacuation#roomFor} found a room for this run
     * @param standbyProxy whether the standby proxy answers right now
     */
    public static RunShape of(
            final UpdateKind kind, final Set<String> moving, final boolean waitingRoom, final boolean standbyProxy) {
        final Set<String> services = Set.copyOf(moving == null ? Set.of() : moving);
        final boolean proxyMoves = services.contains(OnlineCounts.PROXY);
        return new RunShape(occasionOf(kind, waitingRoom), services, proxyMoves, waitingRoom, standbyProxy);
    }

    /** No waiting room makes any kind {@link Occasion#MAINTENANCE}, so the room is decided before the kind. */
    private static Occasion occasionOf(final UpdateKind kind, final boolean waitingRoom) {
        if (!waitingRoom) {
            return Occasion.MAINTENANCE;
        }
        return switch (kind) {
            case DOWN -> Occasion.DOWN;
            case RESTART -> Occasion.RECREATE;
            case BACKUP -> Occasion.BACKUP;
            // START never starts a countdown, and is named anyway.
            case UPDATE, START -> Occasion.UPDATE;
        };
    }

    /**
     * What is about to happen to the player standing on this server.
     *
     * @param on the backend they are connected to, or {@code null} while they are still logging in
     */
    public Fate fateFor(final @Nullable String on) {
        if (on != null && moving.contains(on)) {
            // Their own server is stopping; the waiting room decides between a wait and a disconnect.
            return waitingRoom ? Fate.WAITING_ROOM : Fate.DISCONNECT;
        }
        if (proxyMoves) {
            // Their proxy is stopping: a standby means two loading screens, no standby a disconnect.
            return standbyProxy ? Fate.RECONNECT : Fate.DISCONNECT;
        }
        return Fate.NOTHING;
    }

    /**
     * Whether this player loses voice chat to the run: only on a reconnect outside a waiting room.
     *
     * @param fate what is about to happen to them
     * @param inWaitingRoom whether they are standing in a waiting room right now
     */
    public static boolean losesVoice(final Fate fate, final boolean inWaitingRoom) {
        return fate == Fate.RECONNECT && !inWaitingRoom;
    }

    /** Returns whether this run stops any service at all. */
    public boolean touchesAnybody() {
        return !moving.isEmpty();
    }

    /** Returns the one service this run is about, or {@code null} when it is more than one. */
    public @Nullable String onlyService() {
        return moving.size() == 1 ? moving.iterator().next() : null;
    }
}
