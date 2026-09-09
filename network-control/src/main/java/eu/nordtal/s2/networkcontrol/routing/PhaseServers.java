package eu.nordtal.s2.networkcontrol.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import java.util.Objects;

/**
 * Which backend a player in each phase belongs on.
 *
 * <table>
 *   <caption>Phase to backend</caption>
 *   <tr><th>phase</th><th>server</th></tr>
 *   <tr><td>{@code PRE_EVENT}</td><td>{@code hunger-games} - the lobby</td></tr>
 *   <tr><td>{@code START_EVENT}</td><td>{@code hunger-games} - the event itself</td></tr>
 *   <tr><td>{@code SMP}</td><td>{@code smp}</td></tr>
 *   <tr><td>{@code MAINTENANCE}</td><td>{@code limbo}</td></tr>
 * </table>
 *
 * <p>The mapping is not configurable; the <b>names</b> are, defaulting in
 * {@link GateSpec#serverLimbo()} to the module directory names.</p>
 *
 * <p>This class knows nothing about Velocity and nothing about whether a named server exists, which
 * keeps "which server should this player be on" separate from "does this proxy have it".</p>
 */
public final class PhaseServers {

    private final String limbo;
    private final String hungerGames;
    private final String smp;

    public PhaseServers(final String limbo, final String hungerGames, final String smp) {
        this.limbo = requireName("limbo", limbo);
        this.hungerGames = requireName("hungerGames", hungerGames);
        this.smp = requireName("smp", smp);
    }

    /**
     * @param config the loaded {@code gate.yml}
     * @return the three names it carries
     */
    public static PhaseServers from(final GateSpec config) {
        Objects.requireNonNull(config, "config");
        return new PhaseServers(config.serverLimbo(), config.serverHungerGames(), config.serverSmp());
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
            // MAINTENANCE holds non-admins in the waiting room; PRE_LAUNCH admits nobody but
            // admins, and a network that has not opened may not have its own servers built yet.
            case MAINTENANCE, PRE_LAUNCH -> limbo;
        };
    }

    /**
     * Where a player the gate has admitted goes once the waiting room lets them out.
     * <p>
     * The same as {@link #forPhase} except for an admin while the network is closed. Those phases
     * name {@code limbo} as the phase's backend, and releasing somebody from the waiting room
     * <em>into</em> the waiting room is a black screen with a stale title and no timeout - so an
     * admin is released onto the SMP, the server being worked on, and {@code /server} reaches the
     * others from there.
     * </p>
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

    private static String requireName(final String field, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " server name must not be blank");
        }
        return value;
    }
}
