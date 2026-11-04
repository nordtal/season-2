package eu.nordtal.season.common.season;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The active game mode: which game server runs and where admitted members land. */
public enum SeasonMode {

    /** Members land on {@code smp} and need an active access period. */
    SMP("smp"),

    /** Every linked member lands on {@code hunger-games}. */
    HUNGER_GAMES("hunger-games");

    private final String server;

    SeasonMode(final String server) {
        this.server = server;
    }

    /** Returns the compose service and Velocity server name of this mode's game server. */
    public String server() {
        return server;
    }

    /** Returns the other mode. */
    public SeasonMode other() {
        return this == SMP ? HUNGER_GAMES : SMP;
    }

    /** Returns the mode whose game server is {@code server}, empty for any other service. */
    public static Optional<SeasonMode> ofServer(final @Nullable String server) {
        for (final SeasonMode mode : values()) {
            if (mode.server.equals(server)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** Returns the mode stored in {@code season_state.mode}, {@link #SMP} for a value unknown to this build. */
    public static SeasonMode fromDatabase(final @Nullable String value) {
        for (final SeasonMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return SMP;
    }
}
