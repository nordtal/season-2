package eu.nordtal.s2.proxy;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.proxy.config.GateSpec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which backend a player in each phase belongs on.
 *
 * The mapping is fixed; the names come from {@code gate.yml} and may name a server this proxy lacks.
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

    /** Reads the server names out of the loaded {@code gate.yml}. */
    public static PhaseServers from(final GateSpec config) {
        Objects.requireNonNull(config, "config");
        return new PhaseServers(
                config.serverLimbo(), config.serverLimboStandby(), config.serverHungerGames(), config.serverSmp());
    }

    /** Returns the backend a player in {@code phase} belongs on, never blank but not necessarily one this proxy has. */
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
     * Where an admitted player goes once the waiting room lets them out.
     *
     * The same as {@link #forPhase}, except that an admin in a closed phase goes to the SMP instead of limbo.
     */
    public String forAdmitted(final SeasonPhase phase, final boolean admin) {
        Objects.requireNonNull(phase, "phase");
        if (admin && (phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH)) {
            return smp;
        }
        return forPhase(phase);
    }

    /** Returns the waiting room, which is also every "not yet" destination. */
    public String limbo() {
        return limbo;
    }

    /** Returns the second waiting room, which stands in while {@link #limbo()} is being updated. */
    public String limboStandby() {
        return limboStandby;
    }

    /**
     * Whether a backend name is either waiting room.
     *
     * Use this instead of comparing against {@link #limbo()}, which would miss a player parked on the standby.
     */
    public boolean isWaitingRoom(final @Nullable String server) {
        return limbo.equals(server) || limboStandby.equals(server);
    }

    /** Returns the {@code PRE_EVENT} and {@code START_EVENT} backend. */
    public String hungerGames() {
        return hungerGames;
    }

    /** Returns the SMP backend. */
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
