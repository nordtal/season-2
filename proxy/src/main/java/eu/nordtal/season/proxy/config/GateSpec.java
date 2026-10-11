package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code gate} group: everything the login gate and the expiry check need that is not a credential. */
@ConfigSpec
public interface GateSpec {

    /** The website never expires the way an invite can. */
    @Order(1)
    @Name("Discord invite URL")
    @Key("discord-invite-url")
    @Explain("The website's address, not an invite link.")
    default String discordInviteUrl() {
        return "https://nordtal.eu";
    }

    /** A repeated join within the lifetime returns the same code. */
    @Order(2)
    @Name("Link code lifetime (minutes)")
    @Key("link-code-ttl-minutes")
    @NoExplanationNeeded
    default int linkCodeTtlMinutes() {
        return 10;
    }

    /**
     * How long a player's last-known access stays usable once the database is unreachable.
     *
     * Only access already active is honoured.
     */
    @Order(3)
    @Name("Fallback cache window (minutes)")
    @Key("fallback-cache-window-minutes")
    @NoExplanationNeeded
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    /** Shown once per remaining period, never repeated. */
    @Order(5)
    @Name("Warning before expiry (minutes)")
    @Key("expiry-warning-lead-minutes")
    @NoExplanationNeeded
    default int expiryWarningLeadMinutes() {
        return 5;
    }

    /** Bounds how much playtime a proxy crash can cost a connected player; always flushed on disconnect regardless. */
    @Order(8)
    @Name("Playtime flush interval (seconds)")
    @Key("playtime-flush-interval-seconds")
    @NoExplanationNeeded
    default int playtimeFlushIntervalSeconds() {
        return 300;
    }

    /** The backend for MAINTENANCE. */
    @Order(9)
    @Name("Limbo server")
    @Key("server-limbo")
    @Explain("Must match a server in velocity.toml.")
    default String serverLimbo() {
        return "limbo";
    }

    /** The backend for PRE_EVENT and START_EVENT. */
    @Order(10)
    @Name("Hunger Games server")
    @Key("server-hunger-games")
    @Explain("Must match a server in velocity.toml.")
    default String serverHungerGames() {
        return "hunger-games";
    }

    /** The backend for SMP. */
    @Order(11)
    @Name("SMP server")
    @Key("server-smp")
    @Explain("Must match a server in velocity.toml.")
    default String serverSmp() {
        return "smp";
    }

    /** The backend that stands in for the waiting room while the waiting room itself is being updated. */
    @Order(12)
    @Name("Limbo standby server")
    @Key("server-limbo-standby")
    @NoExplanationNeeded
    default String serverLimboStandby() {
        return "limbo-standby";
    }

    /** How often waiting players are re-checked for a backend coming up. */
    @Order(13)
    @Name("Limbo sweep interval (seconds)")
    @Key("limbo-sweep-interval-seconds")
    @Explain("Keep it well below the pack's apply timeout.")
    default int limboSweepIntervalSeconds() {
        return 5;
    }

    /**
     * A safety window against a lost join confirmation.
     *
     * Without it, a dropped message strands a player in limbo forever.
     */
    @Order(14)
    @Name("Limbo ready grace (seconds)")
    @Key("limbo-ready-grace-seconds")
    @NoExplanationNeeded
    default int limboReadyGraceSeconds() {
        return 5;
    }
}
