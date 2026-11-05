package eu.nordtal.season.proxy;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.proxy.config.GateSpec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Names the waiting rooms and the server of each game mode. The names come from the {@code gate} group. */
public final class NetworkServers {

    private final String limbo;
    private final String limboStandby;
    private final String hungerGames;
    private final String smp;

    public NetworkServers(final String limbo, final String limboStandby, final String hungerGames, final String smp) {
        this.limbo = requireName("limbo", limbo);
        this.limboStandby = requireName("limboStandby", limboStandby);
        this.hungerGames = requireName("hungerGames", hungerGames);
        this.smp = requireName("smp", smp);
    }

    /** Reads the server names from the {@code gate} group. */
    public static NetworkServers from(final GateSpec config) {
        Objects.requireNonNull(config, "config");
        return new NetworkServers(
                config.serverLimbo(), config.serverLimboStandby(), config.serverHungerGames(), config.serverSmp());
    }

    /** Returns the server of the game mode. */
    public String forMode(final SeasonMode mode) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case HUNGER_GAMES -> hungerGames;
            case SMP -> smp;
        };
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

    /** Returns the server of {@link SeasonMode#HUNGER_GAMES}. */
    public String hungerGames() {
        return hungerGames;
    }

    /** Returns the server of {@link SeasonMode#SMP}. */
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
