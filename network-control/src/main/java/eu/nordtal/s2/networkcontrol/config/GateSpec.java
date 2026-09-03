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
            "Shown on every disconnect screen that points a player at Discord: not yet a member",
            "(or banned), not linked, and the 'buy your first month' screen before the opening.",
            "",
            "IT IS THE WEBSITE, NOT AN INVITE LINK (decided 2026-09-03). nordtal.eu forwards to",
            "the Discord and is expected to keep doing so, and an address that never changes is",
            "worth more on a screen somebody reads once than an invite link that can expire and",
            "then silently sends nobody anywhere.",
            "",
            "Empty is still allowed - every message makes sense without it - but there is no",
            "reason to empty it."
    })
    default String discordInviteUrl() {
        return "https://nordtal.eu";
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
            "Turning this off is the documented fallback in docs/state-of-play.md#the-unverified-assumptions",
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

    @Order(9)
    @Key("server-limbo")
    @Comment({
            "The three keys below name the backends this proxy routes to, per phase:",
            "",
            "  PRE_EVENT / START_EVENT  ->  server-hunger-games",
            "  SMP                      ->  server-smp",
            "  MAINTENANCE              ->  server-limbo",
            "",
            "That mapping is docs/season-phases.md's phase table and is not configurable; only",
            "the names are. NOTHING IN docs/ SAYS WHAT THE SERVERS ARE CALLED IN velocity.toml,",
            "so these defaults are the module directory names, which are already the runtime",
            "identity of the three Paper plugins. If velocity.toml calls them something else,",
            "these are the keys to change - the proxy resolves them with",
            "ProxyServer.getServer(name) and never discovers a backend any other way.",
            "",
            "A name this proxy has no server for is not a startup failure, because the phase it",
            "belongs to may never be entered. It fails at the moment it is needed: the player is",
            "disconnected rather than dropped somewhere undefined. See routing/PhaseRouting."
    })
    default String serverLimbo() {
        return "limbo";
    }

    @Order(10)
    @Key("server-hunger-games")
    @Comment("The backend for PRE_EVENT and START_EVENT. See server-limbo above.")
    default String serverHungerGames() {
        return "hunger-games";
    }

    @Order(11)
    @Key("server-smp")
    @Comment("The backend for SMP. See server-limbo above.")
    default String serverSmp() {
        return "smp";
    }

    @Order(12)
    @Key("limbo-sweep-interval-seconds")
    @Comment({
            "How often the players currently held in the waiting room are re-examined.",
            "",
            "Two of the three things that end a wait announce themselves and need no sweep: a",
            "pack status arrives as an event, and a phase switch already re-routes everybody.",
            "The third does not. 'The backend for this phase is now up' is not an event",
            "Velocity has - a RegisteredServer that was refusing connections a moment ago looks",
            "identical to one that was not - so a player waiting on a backend is only released",
            "by somebody looking again. This is that.",
            "",
            "It is also what enforces pack.yml#apply-timeout-seconds, so it should stay well",
            "below it. Five seconds against a set that is empty on an ordinary network costs",
            "nothing; the sweep touches no database and makes no network call of its own."
    })
    default int limboSweepIntervalSeconds() {
        return 5;
    }

    @Order(13)
    @Key("limbo-ready-grace-seconds")
    @Comment({
            "How long the waiting room may be down to its last condition - limbo's own",
            "confirmation that the player has finished joining it - before the player is",
            "released anyway.",
            "",
            "THIS EXISTS BECAUSE A MISSING MESSAGE ONCE STRANDED A PLAYER FOR EVER, on the",
            "first deployment (finding 38). limbo sends that confirmation exactly once per",
            "join, over a plugin-message channel, and Velocity has a path that loses one: a",
            "message decoded in the same read batch as the join is handled by the proxy's",
            "transition handler, which writes it to the client and never asks whether a plugin",
            "wanted it. Nothing retries, and no other condition can end the wait once the pack",
            "is applied - so the player sits on a black screen with a title that is no longer",
            "true, and no log line anywhere says so.",
            "",
            "The confirmation is worth keeping and is not worth waiting on for ever. It",
            "normally arrives within a tick of the join, so anything above a second or two is",
            "already generous; the release it guards only orders the player's arrival against",
            "their onward connection, and the proxy has its own evidence of that arrival.",
            "A release that runs out this clock is logged as a WARNING naming the channel,",
            "because a network where it happens routinely has a broken one."
    })
    default int limboReadyGraceSeconds() {
        return 5;
    }
}
