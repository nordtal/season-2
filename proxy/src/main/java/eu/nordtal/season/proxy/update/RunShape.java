package eu.nordtal.season.proxy.update;

import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.value.Occasion;
import eu.nordtal.season.proxy.online.OnlineCounts;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The effect of an update run on the players.
 *
 * @param occasion the name of the run for the players
 * @param moving the compose services that the run stops
 * @param proxyMoves whether the run stops this proxy
 * @param waitingRoom whether a waiting room runs during the run
 * @param standbyProxy whether a standby proxy runs when this proxy stops
 */
public record RunShape(
        Occasion occasion, Set<String> moving, boolean proxyMoves, boolean waitingRoom, boolean standbyProxy) {

    /** What happens to a player, ordered from the least to the most disruptive. */
    public enum Fate {

        /** The server of the player and this proxy keep running. */
        NOTHING,

        /** The player is transferred to the standby proxy and back. The server of the player keeps running. */
        RECONNECT,

        /** The player waits in the waiting room until the server of the player returns. */
        WAITING_ROOM,

        /** The player is disconnected. */
        DISCONNECT
    }

    public RunShape {
        moving = Set.copyOf(moving == null ? Set.of() : moving);
    }

    /**
     * Returns the shape of a run.
     *
     * @param kind the kind of the request
     * @param moving the services that the report of the run names
     * @param waitingRoom whether {@link Evacuation#roomFor} found a waiting room
     * @param standbyProxy whether the standby proxy answers
     */
    public static RunShape of(
            final UpdateKind kind, final Set<String> moving, final boolean waitingRoom, final boolean standbyProxy) {
        final Set<String> services = Set.copyOf(moving == null ? Set.of() : moving);
        final boolean proxyMoves = services.contains(OnlineCounts.PROXY);
        return new RunShape(occasionOf(kind, waitingRoom), services, proxyMoves, waitingRoom, standbyProxy);
    }

    private static Occasion occasionOf(final UpdateKind kind, final boolean waitingRoom) {
        if (!waitingRoom) {
            return Occasion.MAINTENANCE;
        }
        return switch (kind) {
            case DOWN -> Occasion.DOWN;
            case RESTART, RESTORE, RECREATE, DEPLOY, REMOVE_PLUGIN -> Occasion.RECREATE;
            case BACKUP -> Occasion.BACKUP;
            case UPDATE, START, INSTALL -> Occasion.UPDATE;
        };
    }

    /**
     * Returns what happens to the player.
     *
     * @param on the server of the player, {@code null} while the player logs in
     */
    public Fate fateFor(final @Nullable String on) {
        if (on != null && moving.contains(on)) {
            return waitingRoom ? Fate.WAITING_ROOM : Fate.DISCONNECT;
        }
        if (proxyMoves) {
            return standbyProxy ? Fate.RECONNECT : Fate.DISCONNECT;
        }
        return Fate.NOTHING;
    }

    /**
     * Returns whether the player loses voice chat. It is true for a reconnect outside a waiting room.
     *
     * @param fate what happens to the player
     * @param inWaitingRoom whether the player is in a waiting room
     */
    public static boolean losesVoice(final Fate fate, final boolean inWaitingRoom) {
        return fate == Fate.RECONNECT && !inWaitingRoom;
    }

    /** Returns whether the run stops any service. */
    public boolean touchesAnybody() {
        return !moving.isEmpty();
    }

    /** Returns the only service of the run, {@code null} for several. */
    public @Nullable String onlyService() {
        return moving.size() == 1 ? moving.iterator().next() : null;
    }
}
