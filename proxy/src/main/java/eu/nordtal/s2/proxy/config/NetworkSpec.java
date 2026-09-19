package eu.nordtal.s2.proxy.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/network.yml} - what the server browser shows, and how many players the network
 * takes.
 *
 * <p>These live here rather than in {@code velocity.toml} because the entrypoint seeds that file
 * only on a fresh volume, which made the MOTD unchangeable in practice.</p>
 *
 * <p>{@link #maxPlayers()} is the only limit on the network: Velocity enforces none of its own
 * ({@code show-max-players} is a display value), so the proxy refuses once, at the login gate,
 * where it can say why. The same number is written into every backend's {@code server.properties},
 * so a Paper server on the network's limit would refuse the admins this proxy deliberately lets
 * past it - each backend rebuilds that exemption at its own login, see
 * {@code eu.nordtal.s2.common.access.FullServerAdmission}.</p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  proxy - what the server browser shows, and how many",
        "  players the network takes",
        "-------------------------------------------------------------------",
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
    @Key("max-players")
    @Comment({
            "How many players may be on the network at once. THE ONLY NUMBER THAT DECIDES.",
            "",
            "It is both what the server browser advertises and what the login gate enforces, so",
            "the two cannot say different things. Admins are exempt - the flag comes from the",
            "same database row as the access check, so a full network still lets in whoever has",
            "to go and fix it.",
            "",
            "It is also what every Paper backend's server.properties#max-players is set to, out of",
            "the same NETWORK_MAX_PLAYERS in .env. Changing it therefore needs the backends",
            "restarted as well as this proxy - the entrypoint writes server.properties on every",
            "start, and restarting proxy alone moves the half that advertises and not",
            "the half that runs the servers.",
            "",
            "Two logins arriving in the same instant can exceed this by one. That is accepted",
            "rather than fixed with a reservation scheme: the count is read live from the proxy,",
            "and one player over a limit of several hundred is not a state anybody can observe."
    })
    @Explain("The only real limit on the network - also written into every backend's server.properties, so changing it needs the backends restarted too.")
    default int maxPlayers() {
        return 500;
    }

    // backend-limit is retired: the backends are written from NETWORK_MAX_PLAYERS, so there is no
    // second number left to cross. A network.yml still carrying the key loses the line on load.

    @Order(2)
    @Key("snapshot-refresh-seconds")
    @Comment({
            "How often the numbers behind the MOTD placeholders are re-read from the database.",
            "",
            "A ping must never touch the database: a client in the server list sends them",
            "unprompted and in bursts, and the MOTD is the one surface an unauthenticated",
            "stranger can make the proxy do work for. So one query runs on this interval, its",
            "result is kept as an immutable snapshot, and every ping renders from that.",
            "",
            "A failed refresh keeps the previous snapshot rather than blanking it."
    })
    @Explain("How often the MOTD's live numbers are refreshed from the database; a ping itself never touches it.")
    default int snapshotRefreshSeconds() {
        return 10;
    }

    @Order(3)
    @Key("command-allowlist")
    @Comment({
            "Every command a player who is NOT an admin may type, anywhere on the network.",
            "",
            "Nothing else can be typed and nothing else is offered in tab completion - not by the",
            "proxy, not by any of the three Paper servers, and not by Velocity itself. Admins are",
            "exempt from all of it and see the network exactly as they did before this list",
            "existed.",
            "",
            "AN ALLOWLIST, NOT A SET OF PERMISSIONS: a command nobody thought about must be",
            "refused rather than permitted. Velocity's own /server is open to every player - its",
            "permission check only refuses on an explicit FALSE - so a denylist would let somebody",
            "type '/server hunger-games' during the SMP phase, past every routing decision here.",
            "",
            "AN ENTRY IS A PATH, without the slash: 'smp status', 'hg ready', 'msg'. A leading",
            "slash, extra spaces and capitals are accepted and ignored, and so is a namespace",
            "('minecraft:me' is 'me'). Everything under an allowed path is allowed, so 'msg'",
            "covers '/msg Someone hello'; and a path ABOVE an allowed one is allowed too, so",
            "'smp status' still lets '/smp' print its own help. Which subcommands an admin-only",
            "tree offers is not this list's business - Brigadier's own check decides that.",
            "",
            "A REFUSED COMMAND GETS THE SAME LINE AS A MISTYPED ONE (\"That command does not",
            "exist\"), so nobody learns what exists by being refused it.",
            "",
            "The three Paper servers read this list out of the database, where this proxy",
            "publishes it on every start, so an edit here reaches all four processes. Until a proxy",
            "has published it once a backend filters nothing and says so in its log; this proxy's",
            "own enforcement never waits for anything."
    })
    @Explain("Every command a non-admin may type anywhere on the network - an allowlist, not permissions: anything not listed is refused.")
    default java.util.List<String> commandAllowlist() {
        return java.util.List.of(
                // Ours, and only ours. Every vanilla command is deliberately absent, including
                // the harmless-looking ones: /help lists what a player may not run.
                "smp status",
                "navigate",
                "poi",
                "hg ready",
                "aura",
                "msg",
                "whisper",
                "r",
                "discord",
                "rules");
    }

    @Order(4)
    @Key("public-address")
    @Comment({
            "How a client reaches this network from outside - host and port, the way somebody",
            "types it into Minecraft. EMPTY BY DEFAULT, and empty means one thing: this proxy",
            "never transfers anybody anywhere.",
            "",
            "IT CANNOT BE WORKED OUT FROM INSIDE. A transfer hands the CLIENT an address and the",
            "client connects to it itself, so `proxy:25565` - the only address this container",
            "knows - is a name that exists nowhere but in this stack. deploy/nordtal.sh asks for",
            "this one, and compose.yml maps NETWORK_PUBLIC_ADDRESS in .env onto it.",
            "",
            "THE PORT IS PART OF IT. A SRV record can hide the port from somebody typing a name",
            "into their client; the transfer packet carries host AND port, and nothing looks a SRV",
            "record up on its behalf. So play.example.com:25565, not play.example.com."
    })
    @Explain("Host and port a client reaches this network on from outside - what a transfer names to the player. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    @Order(5)
    @Key("standby-port")
    @Comment({
            "The port the standby proxy is published on, on the same host as public-address.",
            "",
            "THE TWO PROXIES DIFFER BY THIS NUMBER AND BY NOTHING ELSE, which is why there is no",
            "second address here: proxy-standby is the same image, the same configuration and the",
            "same jars on a second port (season-2-ops/119). So the live proxy sends a player to",
            "<host of public-address>:<this>, and the standby sends them back to public-address.",
            "",
            "It is the SAME number compose.yml publishes as PROXY_STANDBY_PORT, handed to both",
            "proxies out of that one variable. Changing it in .env moves both sides; changing it",
            "here moves only what the players are told, which is the half that cannot work alone."
    })
    @Explain("The port the standby proxy is published on - the only thing that distinguishes it from this one.")
    default int standbyPort() {
        return 25566;
    }

    @Order(6)
    @Key("standby")
    @Comment({
            "Whether THIS process is the standby proxy. False everywhere except in one place.",
            "",
            "The two proxies are the same image, the same jars and the same environment block -",
            "compose.yml shares one YAML node between them on purpose - so a process cannot work",
            "out which one it is. Velocity binds 0.0.0.0:25565 inside BOTH containers and Docker",
            "does the renumbering outside, so there is nothing to look at.",
            "",
            "IT IS NOT SYMMETRIC TO GET THIS WRONG. A standby that thought it was live would",
            "simply never send anybody home. A LIVE proxy that thought it was the standby would",
            "watch public-address, find itself answering, and transfer every player to the address",
            "they are already connected to - for ever. That is why this defaults to false and why",
            "exactly one service in compose.yml overrides it.",
            "",
            "A standby proxy: puts arrivals in server-limbo-standby rather than server-limbo,",
            "never releases anybody out of the waiting room, transfers them back to",
            "public-address as soon as it answers again, and writes no player counts."
    })
    @Explain("Whether this process is the standby proxy - false for the one players connect to, and true in exactly one place in compose.yml.")
    default boolean standby() {
        return false;
    }

    @Order(7)
    @Key("motd")
    @Comment({
            "What the server browser shows, per season phase. MiniMessage, so <gradient>,",
            "<#rrggbb> and <newline> all work - a MOTD is two lines in every client, and",
            "<newline> is how you get the second one.",
            "",
            "PLACEHOLDERS are written in braces and are substituted before the MiniMessage is",
            "parsed. Anything the network cannot answer right now renders as 0 rather than as an",
            "error, and an unknown placeholder is left standing so it is visible in a screenshot.",
            "",
            "  everywhere      {online} {max} {phase} {players:<server>}",
            "  pre-launch      {countdown}   - time to season_phase.launch, days and hours",
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
        // createDefault fills the instance from MotdSpec's own default bodies, so the strings
        // exist exactly once; a createUnsafe map would be a second copy to keep in step.
        return Specs.createDefault(MotdSpec.class);
    }

    /** One MOTD per phase, with no shared default to fall back on. */
    @ConfigSpec
    interface MotdSpec {

        // The name is the brand and never changes colour; the phase is what the second line says.
        //
        // The logo's own blue is #24357d, measured off resource-pack/src/pack.png. That is the
        // brand wherever the ground is light, and the server browser is not such a place - it
        // paints an almost black list, on which #24357d is a dark blue on a dark grey. So the name
        // uses a lightened tone of the same hue. Every MOTD writes NORDTAL_BLUE and nothing else;
        // BrandColourTest fails a phase that colours the name for itself.
        String NORDTAL_BLUE = "<#4a63d8><bold>nordtal.eu</bold></#4a63d8>";

        @Order(1)
        @Key("pre-launch")
        @Comment({
                "Before the network has ever opened. Nobody but an admin gets in, and this is what",
                "the whole world sees in the meantime.",
                "",
                "{countdown} counts to season_phase.launch, which is set with an UPDATE on that",
                "row - see V8__pre_launch.sql. Until one is set it reads as \"not announced yet\";",
                "once it has passed it reads as \"any moment now\", because nothing switches the",
                "phase on its own."
        })
        @Explain("Before the network has ever opened; {countdown} counts to season_phase.launch.")
        default String preLaunch() {
            return NORDTAL_BLUE
                    + "<newline><gray>Season 2 opens in <white>{countdown}</white></gray>";
        }

        @Order(2)
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
        @Key("smp")
        @Comment({
                "The season proper.",
                "",
                "IT COUNTS MILESTONES AND NOT PLAYERS, and neither half of that is an accident.",
                "The player count is drawn by the client itself, next to the ping bars, so a MOTD",
                "that repeats it spends its one short line saying something already on screen.",
                "",
                "And it counts FINISHED milestones rather than naming the current one, because",
                "{smp-milestone} is the milestone's KEY out of milestones.yml - 'departure',",
                "lowercase, untranslated. The display names live in smp's message bundle, which",
                "this proxy does not load; until that changes, naming the milestone here puts a",
                "config identifier in the server browser. 'Three of eight done' also says more to",
                "a stranger than a word they have never seen."
        })
        @Explain("The season proper; counts finished milestones rather than naming the current one, so there is no spoiler on the server list.")
        default String smp() {
            return NORDTAL_BLUE
                    + "<newline><gray>Season 2 running - <white>{smp-milestones-done}</white> of"
                    + " <white>{smp-milestones-total}</white> milestones done</gray>";
        }

        @Order(5)
        @Key("maintenance")
        @Comment({
                "Planned work. Players are still let onto the proxy and held in limbo, so this is",
                "not a closed sign - it is a \"we are working, come back shortly\" sign.",
                "",
                "The second half of the sentence is doing the work: 'Maintenance' on its own, in",
                "a server list, is what a dead server looks like."
        })
        @Explain("Shown during planned work - players still get in and wait in limbo; not a closed sign.")
        default String maintenance() {
            return NORDTAL_BLUE
                    + "<newline><gray>Maintenance - back shortly, the season is not over</gray>";
        }
    }
}
