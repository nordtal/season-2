package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code gate} group: everything the login gate and the expiry check need that is not a credential. */
@ConfigSpec
public interface GateSpec {

    @Order(1)
    @Name("Discord invite URL")
    @Key("discord-invite-url")
    @Explain("The website, not a Discord invite link, since it never expires the way an invite can.")
    default String discordInviteUrl() {
        return "https://nordtal.eu";
    }

    @Order(2)
    @Name("Link code lifetime (minutes)")
    @Key("link-code-ttl-minutes")
    @Explain("How long a freshly issued link code stays valid; a repeated join within it returns the same code.")
    default int linkCodeTtlMinutes() {
        return 10;
    }

    @Order(3)
    @Name("Fallback cache window (minutes)")
    @Key("fallback-cache-window-minutes")
    @Explain(
            "How long a player's last-known access stays usable once the database is unreachable; only access already active is honoured.")
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    @Order(5)
    @Name("Warning before expiry (minutes)")
    @Key("expiry-warning-lead-minutes")
    @Explain("Shown once per remaining period, never repeated.")
    default int expiryWarningLeadMinutes() {
        return 5;
    }

    @Order(8)
    @Name("Playtime flush interval (seconds)")
    @Key("playtime-flush-interval-seconds")
    @Explain(
            "Bounds how much playtime a proxy crash can cost a connected player; always flushed on disconnect regardless.")
    default int playtimeFlushIntervalSeconds() {
        return 300;
    }

    @Order(9)
    @Name("Limbo server")
    @Key("server-limbo")
    @Explain("The backend name for MAINTENANCE; must match a real server in velocity.toml.")
    default String serverLimbo() {
        return "limbo";
    }

    @Order(10)
    @Name("Hunger Games server")
    @Key("server-hunger-games")
    @Explain("The backend name for PRE_EVENT and START_EVENT; must match a real server in velocity.toml.")
    default String serverHungerGames() {
        return "hunger-games";
    }

    @Order(11)
    @Name("SMP server")
    @Key("server-smp")
    @Explain("The backend name for SMP; must match a real server in velocity.toml.")
    default String serverSmp() {
        return "smp";
    }

    @Order(12)
    @Name("Limbo standby server")
    @Key("server-limbo-standby")
    @Explain("The backend that stands in for the waiting room while the waiting room itself is being updated.")
    default String serverLimboStandby() {
        return "limbo-standby";
    }

    @Order(13)
    @Name("Limbo sweep interval (seconds)")
    @Key("limbo-sweep-interval-seconds")
    @Explain(
            "How often waiting players are re-checked for a backend coming up; must stay well below the pack's apply-timeout-seconds.")
    default int limboSweepIntervalSeconds() {
        return 5;
    }

    @Order(14)
    @Name("Limbo ready grace (seconds)")
    @Key("limbo-ready-grace-seconds")
    @Explain(
            "A safety window against a lost join confirmation; without it, a dropped message strands a player in limbo forever.")
    default int limboReadyGraceSeconds() {
        return 5;
    }
}
