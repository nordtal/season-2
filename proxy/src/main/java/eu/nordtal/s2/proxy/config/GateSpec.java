package eu.nordtal.s2.proxy.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code config/gate.yml}: everything the login gate and the expiry check need that is not a credential. */
@ConfigSpec(
        header = {
            "proxy: the login gate and the mid-session expiry check",
            "",
            "Every setting here can be overridden with an environment variable",
            "named NORDTAL_PROXY_GATE_<PATH>, with '.' and '-' both",
            "becoming '_':",
            "",
            "  link-code-ttl-minutes  ->  NORDTAL_PROXY_GATE_LINK_CODE_TTL_MINUTES",
            "",
            "The environment wins over this file and is never written back into it."
        })
public interface GateSpec {

    @Order(1)
    @Name("Discord invite URL")
    @Key("discord-invite-url")
    @Comment({
        "Shown on every disconnect screen that points a player at Discord.",
        "THE WEBSITE, NOT AN INVITE LINK: nordtal.eu forwards to the Discord and never expires.",
        "Empty is allowed; every message makes sense without it."
    })
    @Explain("The website, not a Discord invite link, since it never expires the way an invite can.")
    default String discordInviteUrl() {
        return "https://nordtal.eu";
    }

    @Order(2)
    @Name("Link code lifetime (minutes)")
    @Key("link-code-ttl-minutes")
    @Comment({
        "How long a freshly issued link code stays valid. A repeated join attempt inside",
        "this window returns the same code rather than minting a new one."
    })
    @Explain("How long a freshly issued link code stays valid; a repeated join within it returns the same code.")
    default int linkCodeTtlMinutes() {
        return 10;
    }

    @Order(3)
    @Name("Fallback cache window (minutes)")
    @Key("fallback-cache-window-minutes")
    @Comment({
        "How long a player's last-known state stays usable once the database is unreachable.",
        "Only entries that had active access when cached let somebody in."
    })
    @Explain(
            "How long a player's last-known access stays usable once the database is unreachable; only access already active is honoured.")
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    @Order(4)
    @Name("Expiry check interval (seconds)")
    @Key("expiry-check-interval-seconds")
    @Comment({
        "How often every connected player's access is re-checked against the database, which",
        "also notices a mid-session revoke or renewal. A failed pass is skipped, never a kick."
    })
    @Explain("How often a connected player's access is re-checked live, so a mid-session revoke or renewal is noticed.")
    default int expiryCheckIntervalSeconds() {
        return 60;
    }

    @Order(5)
    @Name("Warning before expiry (minutes)")
    @Key("expiry-warning-lead-minutes")
    @Comment("How long before access ends the in-chat warning is shown, once, per remaining period.")
    @Explain("Shown once per remaining period, never repeated.")
    default int expiryWarningLeadMinutes() {
        return 5;
    }

    @Order(8)
    @Name("Playtime flush interval (seconds)")
    @Key("playtime-flush-interval-seconds")
    @Comment({
        "How often online time is written to player_playtime for connected players. It is",
        "always written on disconnect too, so this only bounds what a proxy crash costs."
    })
    @Explain(
            "Bounds how much playtime a proxy crash can cost a connected player; always flushed on disconnect regardless.")
    default int playtimeFlushIntervalSeconds() {
        return 300;
    }

    @Order(9)
    @Name("Limbo server")
    @Key("server-limbo")
    @Comment({
        "The three keys below name the backends this proxy routes to, per phase:",
        "",
        "  PRE_EVENT / START_EVENT  ->  server-hunger-games",
        "  SMP                      ->  server-smp",
        "  MAINTENANCE              ->  server-limbo",
        "",
        "Only the names are configurable, and they must match velocity.toml. A name this proxy",
        "has no server for disconnects the player when that phase is entered."
    })
    @Explain("The backend name for MAINTENANCE; must match a real server in velocity.toml.")
    default String serverLimbo() {
        return "limbo";
    }

    @Order(10)
    @Name("Hunger Games server")
    @Key("server-hunger-games")
    @Comment("The backend for PRE_EVENT and START_EVENT. See server-limbo above.")
    @Explain("The backend name for PRE_EVENT and START_EVENT; must match a real server in velocity.toml.")
    default String serverHungerGames() {
        return "hunger-games";
    }

    @Order(11)
    @Name("SMP server")
    @Key("server-smp")
    @Comment("The backend for SMP. See server-limbo above.")
    @Explain("The backend name for SMP; must match a real server in velocity.toml.")
    default String serverSmp() {
        return "smp";
    }

    @Order(12)
    @Name("Limbo standby server")
    @Key("server-limbo-standby")
    @Comment({
        "The second waiting room, used while `limbo` itself is being updated. It behaves",
        "exactly like `limbo` and runs only in compose.yml's `standby` profile, so its absence",
        "is the ordinary case."
    })
    @Explain("The backend that stands in for the waiting room while the waiting room itself is being updated.")
    default String serverLimboStandby() {
        return "limbo-standby";
    }

    @Order(13)
    @Name("Limbo sweep interval (seconds)")
    @Key("limbo-sweep-interval-seconds")
    @Comment({
        "How often the players held in the waiting room are re-examined, which is the only way",
        "to notice a backend coming up. It also enforces pack#apply-timeout-seconds, so it",
        "must stay well below it."
    })
    @Explain(
            "How often waiting players are re-checked for a backend coming up; must stay well below the pack's apply-timeout-seconds.")
    default int limboSweepIntervalSeconds() {
        return 5;
    }

    @Order(14)
    @Name("Limbo ready grace (seconds)")
    @Key("limbo-ready-grace-seconds")
    @Comment({
        "How long a player waits only for limbo's join confirmation before being released",
        "anyway. WITHOUT THIS GRACE A LOST MESSAGE STRANDS A PLAYER FOR EVER: Velocity can drop",
        "that one message. A release on this clock is logged as a WARNING."
    })
    @Explain(
            "A safety window against a lost join confirmation; without it, a dropped message strands a player in limbo forever.")
    default int limboReadyGraceSeconds() {
        return 5;
    }
}
