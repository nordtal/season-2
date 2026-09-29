package eu.nordtal.s2.proxy.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/network.yml}: what the server browser shows, and how many players the network takes.
 *
 * {@link #maxPlayers()} is enforced only at the login gate; every backend gets the same number from {@code .env}.
 */
@ConfigSpec(
        header = {
            "proxy: what the server browser shows, and how many players the network takes",
            "",
            "Every setting here can be overridden with an environment variable",
            "named NORDTAL_PROXY_NETWORK_<PATH>, with '.' and '-' both",
            "becoming '_':",
            "",
            "  max-players  ->  NORDTAL_PROXY_NETWORK_MAX_PLAYERS",
            "  motd.smp     ->  NORDTAL_PROXY_NETWORK_MOTD_SMP",
            "",
            "The environment wins over this file and is never written back into",
            "it. compose.yml maps the friendlier NETWORK_* names in .env onto",
            "these.",
            "",
            "READ AT PROXY START. There is no reload command: a phase change is",
            "picked up live (the MOTD follows the phase on its own), but an edit",
            "to this file needs a restart."
        })
public interface NetworkSpec {

    @Order(1)
    @Name("Max players")
    @Key("max-players")
    @Comment({
        "How many players may be on the network at once: advertised and enforced at login.",
        "Admins are exempt. Every backend's server.properties gets the same NETWORK_MAX_PLAYERS",
        "from .env, so changing it needs the backends restarted as well as this proxy."
    })
    @Explain(
            "The only real limit on the network; also written into every backend's server.properties, so changing it needs the backends restarted too.")
    default int maxPlayers() {
        return 500;
    }

    @Order(2)
    @Name("Snapshot refresh (seconds)")
    @Key("snapshot-refresh-seconds")
    @Comment({
        "How often the numbers behind the MOTD placeholders are re-read from the database.",
        "A ping never touches the database, and a failed refresh keeps the previous numbers."
    })
    @Explain("How often the MOTD's live numbers are refreshed from the database; a ping itself never touches it.")
    default int snapshotRefreshSeconds() {
        return 10;
    }

    @Order(3)
    @Name("Command allowlist")
    @Key("command-allowlist")
    @Comment({
        "Every command a player who is not an admin may type or see, anywhere on the network.",
        "Anything not listed is refused like a mistyped command. Admins are exempt.",
        "",
        "An entry is a path without the slash: 'hg ready', 'poi', 'msg'. Everything",
        "under an allowed path is allowed, and so is every path above one. This proxy",
        "publishes the list to the database on start, which is where the backends read it."
    })
    @Explain("Every command a non-admin may type anywhere on the network; anything not listed is refused.")
    default java.util.List<String> commandAllowlist() {
        return java.util.List.of(
                // Ours, and only ours: every vanilla command, including /help, is deliberately absent.
                "navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules");
    }

    @Order(4)
    @Name("Public address")
    @Key("public-address")
    @Comment({
        "How a client reaches this network from outside, host and port, as typed into",
        "Minecraft. Empty means this proxy never transfers anybody. Include the port, since a",
        "transfer resolves no SRV record: play.example.com:25565, not play.example.com."
    })
    @Explain(
            "Host and port a client reaches this network on from outside, as a transfer names it. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    @Order(5)
    @Name("Standby port")
    @Key("standby-port")
    @Comment({
        "The port the standby proxy is published on, on the same host as public-address.",
        "Set it through PROXY_STANDBY_PORT in .env, which moves both sides; changing it here",
        "only changes what players are told."
    })
    @Explain("The port the standby proxy is published on, the only thing that distinguishes it from this one.")
    default int standbyPort() {
        return 25566;
    }

    @Order(6)
    @Name("Standby")
    @Key("standby")
    @Comment({
        "Whether this process is the standby proxy. False everywhere except one service in",
        "compose.yml: a live proxy set to true would transfer every player to itself forever.",
        "A standby parks arrivals in server-limbo-standby, releases nobody, sends them back to",
        "public-address once it answers, and writes no player counts."
    })
    @Explain(
            "Whether this process is the standby proxy: false for the one players connect to, true in exactly one place in compose.yml.")
    default boolean standby() {
        return false;
    }

    @Order(7)
    @Name("MOTD")
    @Key("motd")
    @Comment({
        "What the server browser shows, per season phase, in MiniMessage. A MOTD is two lines;",
        "<newline> starts the second.",
        "",
        "Placeholders in braces are substituted before parsing. An unanswerable one renders as",
        "0, and an unknown one is left standing.",
        "",
        "  everywhere      {online} {max} {phase} {players:<server>}",
        "  pre-launch      {countdown}   (time to season_phase.launch, days and hours)",
        "  hunger games    {hg-state} {hg-teams} {hg-teams-alive} {hg-participants}",
        "                  {hg-alive} {hg-eliminated}",
        "  smp             {smp-milestone} {smp-milestone-progress} {smp-milestones-done}",
        "                  {smp-milestones-total} {smp-aura-total} {smp-players}",
        "",
        "{players:<server>} takes a server name as velocity.toml spells it, e.g.",
        "{players:smp}. The three the phases route to are limbo, hunger-games and smp."
    })
    @NoExplanationNeeded
    default MotdSpec motd() {
        return Specs.createDefault(MotdSpec.class);
    }

    /** One MOTD per phase, with no shared default to fall back on. */
    @ConfigSpec
    interface MotdSpec {

        // A lightened #24357d, readable on the dark server list; BrandColourTest refuses a self-coloured phase.
        String NORDTAL_BLUE = "<#4a63d8><bold>nordtal.eu</bold></#4a63d8>";

        @Order(1)
        @Name("Pre-launch")
        @Key("pre-launch")
        @Comment({
            "Before the network has ever opened; only admins get in. {countdown} counts to",
            "season_phase.launch, reading \"not announced yet\" until it is set and \"any moment",
            "now\" once it has passed, since nothing switches the phase on its own."
        })
        @Explain("Before the network has ever opened; {countdown} counts to season_phase.launch.")
        default String preLaunch() {
            return NORDTAL_BLUE + "<newline><gray>Season 2 opens in <white>{countdown}</white></gray>";
        }

        @Order(2)
        @Name("Pre-event")
        @Key("pre-event")
        @Comment({
            "The network is open, the lobby stands and teams register for the hunger games.",
            "{hg-teams} is what registration has produced so far."
        })
        @Explain("The lobby phase; {hg-teams} is how many teams have registered so far.")
        default String preEvent() {
            return NORDTAL_BLUE
                    + "<newline><gray>Hunger Games: <white>{hg-participants}</white> players"
                    + " in <white>{hg-teams}</white> teams</gray>";
        }

        @Order(3)
        @Name("Event start")
        @Key("start-event")
        @Comment({
            "The hunger games themselves, countdown to winner. {hg-alive} is what is left of",
            "{hg-participants}; both come from the running game and drop to 0 between games."
        })
        @Explain("The hunger games running; {hg-alive} is what remains of {hg-participants}.")
        default String startEvent() {
            return NORDTAL_BLUE
                    + "<newline><gray>Hunger Games: <white>{hg-alive}</white> of"
                    + " <white>{hg-participants}</white> still alive</gray>";
        }

        @Order(4)
        @Name("SMP")
        @Key("smp")
        @Comment({
            "The season proper. The client already shows the player count, and {smp-milestone}",
            "is an untranslated key, so this counts finished milestones instead."
        })
        @Explain(
                "The season proper; counts finished milestones rather than naming the current one, so there is no spoiler on the server list.")
        default String smp() {
            return NORDTAL_BLUE
                    + "<newline><gray>Season 2 running - <white>{smp-milestones-done}</white> of"
                    + " <white>{smp-milestones-total}</white> milestones done</gray>";
        }

        @Order(5)
        @Name("Maintenance")
        @Key("maintenance")
        @Comment({
            "Planned work. Players still get in and wait in limbo, so this says \"come back",
            "shortly\": 'Maintenance' alone in a server list looks like a dead server."
        })
        @Explain("Shown during planned work; players still get in and wait in limbo, so it is not a closed sign.")
        default String maintenance() {
            return NORDTAL_BLUE + "<newline><gray>Maintenance - back shortly, the season is not over</gray>";
        }
    }
}
