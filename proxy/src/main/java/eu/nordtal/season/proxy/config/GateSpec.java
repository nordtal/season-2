package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code gate} group of the proxy. */
@ConfigSpec
public interface GateSpec {

    @Order(1)
    @Name("Discord invite URL")
    @Key("discord-invite-url")
    @Explain("The website's address: an invite link expires.")
    default String discordInviteUrl() {
        return "https://nordtal.eu";
    }

    /** The minutes that a link code is valid. A player who joins again within that time gets the same code. */
    @Order(2)
    @Name("Link code lifetime (minutes)")
    @Key("link-code-ttl-minutes")
    @NoExplanationNeeded
    default int linkCodeTtlMinutes() {
        return 10;
    }

    /**
     * The minutes that the cache keeps the last state of a player. The cache only allows a player whose period is
     * running.
     */
    @Order(3)
    @Name("Fallback cache window (minutes)")
    @Key("fallback-cache-window-minutes")
    @NoExplanationNeeded
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    /** The minutes before the end of the period at which the player gets a warning. */
    @Order(5)
    @Name("Warning before expiry (minutes)")
    @Key("expiry-warning-lead-minutes")
    @NoExplanationNeeded
    default int expiryWarningLeadMinutes() {
        return 5;
    }

    /** The seconds between two writes of the play time of the connected players. */
    @Order(8)
    @Name("Playtime flush interval (seconds)")
    @Key("playtime-flush-interval-seconds")
    @NoExplanationNeeded
    default int playtimeFlushIntervalSeconds() {
        return 300;
    }

    /** The server for {@code MAINTENANCE}. */
    @Order(9)
    @Name("Limbo server")
    @Key("server-limbo")
    @Explain("Must match a server in velocity.toml.")
    default String serverLimbo() {
        return "limbo";
    }

    /** The server for {@code PRE_EVENT} and {@code START_EVENT}. */
    @Order(10)
    @Name("Hunger Games server")
    @Key("server-hunger-games")
    @Explain("Must match a server in velocity.toml.")
    default String serverHungerGames() {
        return "hunger-games";
    }

    /** The server for {@code SMP}. */
    @Order(11)
    @Name("SMP server")
    @Key("server-smp")
    @Explain("Must match a server in velocity.toml.")
    default String serverSmp() {
        return "smp";
    }

    /** The server that replaces the waiting room while an update stops it. */
    @Order(12)
    @Name("Limbo standby server")
    @Key("server-limbo-standby")
    @NoExplanationNeeded
    default String serverLimboStandby() {
        return "limbo-standby";
    }

    /** The seconds between two checks of the waiting players for a backend that accepts connections. */
    @Order(13)
    @Name("Limbo sweep interval (seconds)")
    @Key("limbo-sweep-interval-seconds")
    @Explain("Keep it well below the pack's apply timeout.")
    default int limboSweepIntervalSeconds() {
        return 5;
    }

    /** The seconds after which the proxy sends a player on even without a {@code READY} message. */
    @Order(14)
    @Name("Limbo ready grace (seconds)")
    @Key("limbo-ready-grace-seconds")
    @NoExplanationNeeded
    default int limboReadyGraceSeconds() {
        return 5;
    }
}
