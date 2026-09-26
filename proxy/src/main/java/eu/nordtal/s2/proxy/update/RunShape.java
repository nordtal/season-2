package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.proxy.online.OnlineCounts;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * What a run is about to do, and what that means for one player - season-2-ops/118.
 *
 * <h2>Why this exists</h2>
 * Every announcement this network ever made said the same thing: <i>the whole network restarts</i>.
 * That is true of one run in five. Till, 2026-09-20: <i>the countdowns and warnings should all suit
 * the actual action, not always just "the whole network is restarting"</i>, and <i>the messages
 * should also tell the player whether they are about to see a reconnect screen and carry straight
 * on, whether they are moved to the waiting room, or whether they are thrown out of the game
 * entirely.</i>
 *
 * <h2>None of this is a guess</h2>
 * Both halves are already decided before a word is said. {@code update_request.kind} says what was
 * asked for; the report steward-worker writes into the row <em>before</em> the countdown starts says
 * which services move; {@link Evacuation#roomFor} says whether there is a waiting room left; and
 * {@code ProxySwap} says whether the standby proxy answers. This class does nothing but put those
 * four together, which is why it holds no Velocity type and no database - the rule is the part worth
 * asserting.
 *
 * <h2>The fifth occasion is the absence of the other four's safety net</h2>
 * {@link Occasion#MAINTENANCE} is not a kind anybody asks for. It is what any run becomes when
 * there is nowhere left to put anybody - the waiting room is itself in the run and no standby is
 * registered - and it is the one case that is honest only if it is said: nobody is parked, everyone
 * is disconnected, and a countdown that promised a waiting room would be a lie told sixty seconds
 * before it came true.
 *
 * @param occasion     what to call this, from the seat of somebody standing in the game
 * @param moving       the compose services the run stops
 * @param proxyMoves   whether this proxy is one of them
 * @param waitingRoom  whether a waiting room survives the run
 * @param standbyProxy whether a standby proxy is there to catch the network when this one goes
 */
public record RunShape(
        Occasion occasion, Set<String> moving, boolean proxyMoves, boolean waitingRoom, boolean standbyProxy) {

    /** What a run is called to a player. One per {@link UpdateKind} that stops anything, plus one. */
    public enum Occasion {

        /** {@link UpdateKind#DOWN}: a service goes off and stays off until somebody says otherwise. */
        DOWN,

        /** {@link UpdateKind#RESTART}: a service goes round once. */
        RECREATE,

        /** {@link UpdateKind#BACKUP}: it is being saved, which is why it has to hold still. */
        BACKUP,

        /** {@link UpdateKind#UPDATE}: new jars are going in. */
        UPDATE,

        /**
         * No waiting room survives this run, so nobody is caught by anything.
         *
         * <p>Deliberately not a kind: it is a property of the plan, and a run of any kind becomes
         * this the moment the plan leaves it with nowhere to put people.</p>
         */
        MAINTENANCE
    }

    /** What is about to happen to one player, in the order of how much it costs them. */
    public enum Fate {

        /** Nothing. Their server is not in the run and this proxy is not going anywhere. */
        NOTHING,

        /**
         * A loading screen, and then they carry on where they were.
         *
         * <p>The proxy swap, from the player's seat: they are handed to the standby proxy and handed
         * back. Their own server never stops.</p>
         */
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
     * @param kind         what was asked for
     * @param moving       the services the run's own report says are moving
     * @param waitingRoom  whether {@link Evacuation#roomFor} found a room for this run
     * @param standbyProxy whether the standby proxy answers right now
     */
    public static RunShape of(
            final UpdateKind kind, final Set<String> moving, final boolean waitingRoom, final boolean standbyProxy) {
        final Set<String> services = Set.copyOf(moving == null ? Set.of() : moving);
        final boolean proxyMoves = services.contains(OnlineCounts.PROXY);
        return new RunShape(occasionOf(kind, waitingRoom), services, proxyMoves, waitingRoom, standbyProxy);
    }

    /**
     * <p>The waiting room decides before the kind does, and that order is the whole of the fifth
     * occasion: a backup that has nowhere to put anybody is not a backup as far as the person on
     * the screen is concerned, it is the network going away.</p>
     */
    private static Occasion occasionOf(final UpdateKind kind, final boolean waitingRoom) {
        if (!waitingRoom) {
            return Occasion.MAINTENANCE;
        }
        return switch (kind) {
            case DOWN -> Occasion.DOWN;
            case RESTART -> Occasion.RECREATE;
            case BACKUP -> Occasion.BACKUP;
            // UPDATE, and everything that never counts down. REPORT and START reach nobody - they
            // stop nothing, so no countdown is ever started for them - and APPLY was retired. Named
            // as an update rather than left to a default, because a default here is the sentence a
            // player reads.
            case UPDATE, REPORT, START, APPLY -> Occasion.UPDATE;
        };
    }

    /**
     * What is about to happen to the player standing on this server.
     *
     * @param on the backend they are connected to, or {@code null} while they have none - which is
     *           a player mid-login, and they are on this proxy either way
     */
    public Fate fateFor(final @Nullable String on) {
        if (on != null && moving.contains(on)) {
            // Their own server is stopping. Whether that is a wait or a disconnect is the one
            // question the waiting room answers.
            return waitingRoom ? Fate.WAITING_ROOM : Fate.DISCONNECT;
        }
        if (proxyMoves) {
            // Their server stays up and the ground under it does not. A standby to hand them to is
            // a pair of loading screens; no standby is the same disconnect as above, arrived at
            // from the other direction.
            return standbyProxy ? Fate.RECONNECT : Fate.DISCONNECT;
        }
        return Fate.NOTHING;
    }

    /**
     * Whether this player loses voice chat to the run, which is worth one sentence and only then
     * (season-2-ops/132, from Till's answer in season-2-ops/136).
     *
     * <p>Till, 2026-09-20: <i>a hint before the process starts should really only come in the case
     * where players are on the SMP via proxy-standby. If the players are waiting in the limbo
     * anyway, no message is needed.</i> Both halves are in the two arguments. {@link Fate#RECONNECT}
     * is the proxy swap seen from a player's seat - they are handed to another proxy and back, and
     * Simple Voice Chat does not survive that - and a waiting room has neither voice nor text chat
     * (season-2-ops/141), so telling somebody sitting in one that voice is about to stop is a
     * sentence about something they do not have.</p>
     *
     * @param fate          what is about to happen to them
     * @param inWaitingRoom whether they are standing in a waiting room right now
     */
    public static boolean losesVoice(final Fate fate, final boolean inWaitingRoom) {
        return fate == Fate.RECONNECT && !inWaitingRoom;
    }

    /**
     * @return whether this run touches anybody at all. A run that moves neither a backend anybody
     *         is on nor this proxy is one the network never needs to hear about
     */
    public boolean touchesAnybody() {
        return !moving.isEmpty();
    }

    /**
     * @return the one service this run is about, or {@code null} when it is more than one - in
     *         which case there is no honest short name for it but "the network"
     *
     * <p>One or all, and nothing between: a list of two service names joined in two languages is
     * grammar this file would have to own, for a case that is rarer than either end of it.</p>
     */
    public @Nullable String onlyService() {
        return moving.size() == 1 ? moving.iterator().next() : null;
    }
}
