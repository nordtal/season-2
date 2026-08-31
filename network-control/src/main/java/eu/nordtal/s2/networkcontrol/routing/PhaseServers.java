package eu.nordtal.s2.networkcontrol.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import java.util.Objects;

/**
 * docs/season-phases.md's "where they land" column, as a lookup.
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
 * <p>The mapping is the document and is not configurable. The <b>names</b> are, because nothing in
 * docs/ says what {@code velocity.toml} calls these three servers; the defaults in
 * {@link GateSpec#serverLimbo()} are the module directory names, which are already the runtime
 * identity of the three Paper plugins.
 *
 * <p>This class knows nothing about Velocity and nothing about whether a named server exists. That
 * is deliberate: it makes the table a value that can be asserted in memory, and it keeps
 * "which server should this player be on" separate from "does this proxy have it", which is the
 * question with the interesting failure mode.
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
            case MAINTENANCE -> limbo;
        };
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
