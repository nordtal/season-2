package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/gate.yml} - everything the login gate and the mid-session expiry check need that
 * is not a database credential. See docs/access-system.md.
 * <p>
 * <b>{@link #linkCodeTtlMinutes()} is the only link-code TTL, decided 2026-08-31.</b> It used to be
 * duplicated by {@code access-bot}'s {@code access.yml#link-code-ttl-minutes}, whose own comment
 * claimed the value "lives [in access.yml] because the bot owns the sweep that deletes expired
 * ones" - but the bot's sweep ({@code ReconcileDao#deleteExpiredLinkCodes}) only ever compares
 * {@code link_code.expires} to {@code now()} and never read that setting. The proxy is the process
 * that calls {@code AccessDirectory#issueLinkCode}, so it is the only process that can act on a
 * TTL at all, and the bot's copy is to be deleted rather than wired through: removing a value
 * nobody reads is smaller than building a protocol for a number that never changes.
 * </p><p>
 * Deleting it from {@code AccessSpec} is free only while nothing is deployed - a key the interface
 * does not declare stops the load, so an {@code access.yml} in the wild carrying the retired key
 * would refuse to start. Nothing is deployed today.
 * </p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  network-control - the login gate and the mid-session expiry check",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_NETWORK_CONTROL_GATE_<PATH>, with '.' and '-' both",
        "becoming '_':",
        "",
        "  link-code-ttl-minutes  ->  NORDTAL_NETWORK_CONTROL_GATE_LINK_CODE_TTL_MINUTES",
        "",
        "The environment wins over this file and is never written back into it."
})
public interface GateSpec {

    @Order(1)
    @Key("discord-invite-url")
    @Comment({
            "Shown on the two disconnect screens that point a player at Discord: not yet a",
            "member (or banned), and not linked. Empty is allowed - the message still makes",
            "sense without a clickable link - but filling it in is what makes the screen",
            "actually useful to somebody who has never joined the Discord server."
    })
    default String discordInviteUrl() {
        return "";
    }

    @Order(2)
    @Key("link-code-ttl-minutes")
    @Comment({
            "How long a freshly issued link code stays valid. A repeated join attempt inside",
            "this window returns the same code rather than minting a new one.",
            "",
            "This is the only place this value lives; access-bot's copy of it is retired.",
            "See this file's class-level documentation."
    })
    default int linkCodeTtlMinutes() {
        return 10;
    }

    @Order(3)
    @Key("fallback-cache-window-minutes")
    @Comment({
            "How long a player's last-known state stays usable once the database becomes",
            "unreachable. Only entries with active access at the moment they were cached are",
            "ever used to let somebody in; everyone else is refused outright. A long outage",
            "closes the door rather than leaving it open forever - see docs/access-system.md."
    })
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    @Order(4)
    @Key("expiry-check-interval-seconds")
    @Comment({
            "How often every connected, linked player's access is re-checked against the",
            "database while they are online. Re-checked, not just counted down from the",
            "value seen at login: this is also what notices a mid-session /revoke-access or",
            "a renewal that pushes the deadline back out.",
            "",
            "A database hiccup during one pass is skipped rather than treated as a mass",
            "expiry - the fallback cache is a login-time concept only; nobody already",
            "connected is kicked because one periodic query failed."
    })
    default int expiryCheckIntervalSeconds() {
        return 60;
    }

    @Order(5)
    @Key("expiry-warning-lead-minutes")
    @Comment("How long before access ends the in-chat warning is shown, once, per remaining period.")
    default int expiryWarningLeadMinutes() {
        return 5;
    }

    @Order(6)
    @Key("phase-poll-interval-seconds")
    @Comment({
            "How often the season_phase row is re-read. THIRTY SECONDS IS THE DECIDED VALUE",
            "(docs/season-phases.md, settled 2026-08-31) and this key exists to make an",
            "emergency change possible, not to invite tuning.",
            "",
            "This poll - not the LISTEN/NOTIFY path below - is the actual guarantee. Thirty",
            "seconds is the worst case a process can sit in the wrong phase after a listener",
            "connection has silently died. Ten was rejected as triple the standing cost for a",
            "case that happens three times a season; sixty as a full minute of players on the",
            "wrong server after a switch to SMP.",
            "",
            "The login path does NOT use this: it reads the phase on the same row as the access",
            "state, in one round trip. This is for everything that is not a login."
    })
    default int phasePollIntervalSeconds() {
        return 30;
    }

    @Order(7)
    @Key("phase-listen-enabled")
    @Comment({
            "Whether to also hold a dedicated LISTEN connection on the 'nordtal_phase' channel,",
            "outside the connection pool, so that a phase switch feels instant instead of taking",
            "up to one poll interval.",
            "",
            "Turning this off is the documented fallback in docs/operations.md#open-verification",
            "if the listener turns out to be more trouble than it is worth: the poll above was",
            "always the guarantee, and nothing else changes. Notifications carry no payload and",
            "are lost while a process is disconnected, so every reconnect re-reads the row",
            "unconditionally - the notification is an optimisation, never the state.",
            "",
            "The channel name is not configurable. It has to match the pg_notify() baked into",
            "the switch statement in :common, and a listener quietly pointed at a different",
            "channel would look exactly like one that works until the first phase switch."
    })
    default boolean phaseListenEnabled() {
        return true;
    }

    @Order(8)
    @Key("playtime-flush-interval-seconds")
    @Comment({
            "How often accumulated online time is written to player_playtime for players who are",
            "still connected. It is also written on disconnect, always; this interval only bounds",
            "what a proxy crash costs.",
            "",
            "FIVE MINUTES IS THE DECIDED VALUE (settled 2026-08-31). docs/smp.md only says the",
            "proxy writes 'on disconnect and periodically in between, so a crash costs minutes",
            "rather than a whole session'; the owner picked the number inside that sentence.",
            "A proxy crash therefore costs each connected player up to five minutes of counted",
            "play time, and that is the accepted trade: play time feeds a 13-tier prestige crest",
            "earned over the length of a season, so five minutes is invisible in it, while a",
            "write per connected player every minute is not.",
            "",
            "This deliberately no longer matches expiry-check-interval-seconds. The two sweeps",
            "are unrelated - one re-reads access from the database, the other writes accumulated",
            "seconds - and running them on one cadence was never more than a coincidence.",
            "",
            "Nothing is lost to rounding either way: a flush advances the session marker by",
            "exactly the whole seconds it wrote, so the remainder survives to the next one."
    })
    default int playtimeFlushIntervalSeconds() {
        return 300;
    }
}
