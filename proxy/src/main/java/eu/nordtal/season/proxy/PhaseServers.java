package eu.nordtal.season.proxy;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.proxy.config.GateSpec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Maps each phase to a server. The names come from the {@code gate} group. */
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

    /** Reads the server names from the {@code gate} group. */
    public static PhaseServers from(final GateSpec config) {
        Objects.requireNonNull(config, "config");
        return new PhaseServers(
                config.serverLimbo(), config.serverLimboStandby(), config.serverHungerGames(), config.serverSmp());
    }

    /** Returns the server of the phase. */
    public String forPhase(final SeasonPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return switch (phase) {
            case PRE_EVENT, START_EVENT -> hungerGames;
            case SMP -> smp;
            case MAINTENANCE, PRE_LAUNCH -> limbo;
        };
    }

    /**
     * Returns the server for a player who leaves the waiting room.
     * It equals {@link #forPhase}, except that an admin in {@code MAINTENANCE} or {@code PRE_LAUNCH} gets the SMP.
     */
    public String forAdmitted(final SeasonPhase phase, final boolean admin) {
        Objects.requireNonNull(phase, "phase");
        if (admin && (phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH)) {
            return smp;
        }
        return forPhase(phase);
    }

    /** Returns the waiting room. */
    public String limbo() {
        return limbo;
    }

    /** Returns the standby waiting room. */
    public String limboStandby() {
        return limboStandby;
    }

    /** Returns whether the server is {@link #limbo()} or {@link #limboStandby()}. */
    public boolean isWaitingRoom(final @Nullable String server) {
        return limbo.equals(server) || limboStandby.equals(server);
    }

    /** Returns the server of {@code PRE_EVENT} and {@code START_EVENT}. */
    public String hungerGames() {
        return hungerGames;
    }

    /** Returns the SMP server. */
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
