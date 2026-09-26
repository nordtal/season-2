package eu.nordtal.s2.proxy;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.proxy.config.GateSpec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which backend a player in each phase belongs on.
 *
 * {@code PRE_EVENT} and {@code START_EVENT} are the {@code hunger-games} lobby and event,
 * {@code SMP} is the {@code smp} backend, and {@code MAINTENANCE} is {@code limbo}. The mapping is
 * not configurable; the names are, defaulting in {@link GateSpec#serverLimbo()} to the module
 * directory names.
 *
 * This class knows nothing about Velocity and nothing about whether a named server exists, which
 * keeps "which server should this player be on" separate from "does this proxy have it".
 */
public final class PhaseServers {

    private final String limbo;
    private final String limboStandby;
    private final String hungerGames;
    private final String smp;

    public PhaseServers(final String limbo, final String limboStandby, final String hungerGames, final String smp) {
        this.limbo = requireName("limbo", limbo);
        this.limboStandby = requireName("limboStandby", limboStandby);
        this.hungerGames = requireName("hungerGames", hungerGames);
        this.smp = requireName("smp", smp);
    }

    /**
     * @param config the loaded {@code gate.yml}
     * @return the three names it carries
     */
    public static PhaseServers from(final GateSpec config) {
        Objects.requireNonNull(config, "config");
        return new PhaseServers(
                config.serverLimbo(), config.serverLimboStandby(), config.serverHungerGames(), config.serverSmp());
    }

    /**
     * @param phase the phase the network is in
     * @return the name of the backend a player in that phase belongs on, never {@code null} and
     *         never blank - but not necessarily a server this proxy has
     */
    public String forPhase(final SeasonPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return switch (phase) {
            case PRE_EVENT, START_EVENT -> hungerGames;
            case SMP -> smp;
            // PRE_LAUNCH admits nobody either: an unopened network may not have its other servers built yet.
            case MAINTENANCE, PRE_LAUNCH -> limbo;
        };
    }

    /**
     * Where a player the gate has admitted goes once the waiting room lets them out.
     *
     * The same as {@link #forPhase} except for an admin while the network is closed. Those phases
     * name {@code limbo} as the phase's backend, and releasing somebody from the waiting room into
     * the waiting room is a black screen with a stale title and no timeout - so an admin is
     * released onto the SMP, the server being worked on, and {@code /server} reaches the others
     * from there.
     *
     * @param phase the phase the network is in
     * @param admin whether the player carries {@code discord_user.admin}
     * @return the server to connect them to once nothing is left to wait for
     */
    public String forAdmitted(final SeasonPhase phase, final boolean admin) {
        Objects.requireNonNull(phase, "phase");
        if (admin && (phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH)) {
            return smp;
        }
        return forPhase(phase);
    }

    /** @return the name of the waiting room, which is also every "not yet" destination */
    public String limbo() {
        return limbo;
    }

    /**
     * @return the name of the second waiting room, which stands in while {@link #limbo()} is
     *         itself being updated
     */
    public String limboStandby() {
        return limboStandby;
    }

    /**
     * Whether a backend name is a waiting room - either of them.
     *
     * A player parked on the standby room counts as waiting too: comparing only against
     * {@link #limbo()} would treat them as somebody on an unrelated backend instead, and nothing
     * would ever release them from a room that already holds them. Ask this instead of
     * {@code name.equals(servers.limbo())} everywhere.
     *
     * @param server a backend name, or {@code null}
     * @return whether a player standing on it is a player who is waiting
     */
    public boolean isWaitingRoom(final @Nullable String server) {
        return limbo.equals(server) || limboStandby.equals(server);
    }

    /** @return the name of the PRE_EVENT / START_EVENT backend - see {@link #forPhase} */
    public String hungerGames() {
        return hungerGames;
    }

    /** @return the name of the SMP backend - see {@link #forPhase} */
    public String smp() {
        return smp;
    }

    private static String requireName(final String field, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " server name must not be blank");
        }
        return value;
    }
}
