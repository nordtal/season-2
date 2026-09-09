package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/network.yml} - what the server browser shows, and how many players the network
 * takes.
 *
 * <h2>Why this is a config file and not {@code velocity.toml}</h2>
 * It used to be {@code velocity.toml}, seeded once by the entrypoint on a fresh volume and owned by
 * the operator ever after. That made the MOTD unchangeable in practice: editing {@code .env} did
 * nothing on a volume that already existed, and nothing said so. The proxy refuses to start without
 * this plugin ({@code EXPECTED_PLUGINS}), so nothing is lost by moving both values in here and
 * having {@code eu.nordtal.s2.networkcontrol.ping.NetworkPing} answer every ping - and the whole
 * "seeded once" trap goes with them. The entrypoint no longer writes {@code motd} or
 * {@code show-max-players} at all.
 *
 * <h2>{@link #maxPlayers()} is the only limit on the network, since 2026-09-03</h2>
 * Velocity enforces no limit of its own - {@code show-max-players} is a display value - so before
 * this the number that actually decided was {@code max-players} on whichever Paper backend the
 * player landed on, which is always {@code limbo} first. The proxy refuses, once, at the login
 * gate, where it can say why.
 *
 * <h2>How it reaches the backends, since 2026-09-04</h2>
 * <b>It is the same number, not a smaller one.</b> This file used to carry a second key,
 * {@code backend-limit}: a copy of {@code BACKEND_MAX_PLAYERS}, the deliberately unreachable
 * {@code server.properties#max-players} the three Paper backends were given so that the proxy would
 * be the only thing that ever refused a player. The proxy refused to start when the two crossed,
 * which made the arrangement safe without making it right - the backends' number is what every
 * screen <em>on</em> a backend can reach, so the browser advertised 500 while the tab list said
 * {@code 3/1000}. Two numbers were visible at once and only one of them was true.
 *
 * <p>{@code NETWORK_MAX_PLAYERS} in {@code .env} is now written into this file <em>and</em> into
 * every backend's {@code server.properties}, so there is nothing left to keep in step. What that
 * costs is the admin exemption below: a Paper server on the network's own limit will refuse the
 * admins this proxy deliberately lets past it, so each backend rebuilds the exemption at its own
 * login - see {@code eu.nordtal.s2.common.access.FullServerAdmission}.</p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  network-control - what the server browser shows, and how many",
        "  players the network takes",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_NETWORK_CONTROL_NETWORK_<PATH>, with '.' and '-' both",
        "becoming '_':",
        "",
        "  max-players  ->  NORDTAL_NETWORK_CONTROL_NETWORK_MAX_PLAYERS",
        "  motd.smp     ->  NORDTAL_NETWORK_CONTROL_NETWORK_MOTD_SMP",
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
            "the same NETWORK_MAX_PLAYERS in .env: this file is an environment override of it and",
            "so is each backend. That is why a backend's tab list can say 3/500 rather than the",
            "3/1000 it said while the backends carried a separate, unreachable number. Changing it",
            "therefore needs the backends restarted as well as this proxy - the entrypoint writes",
            "server.properties on every start, and `docker compose restart network-control` alone",
            "moves the half that advertises and not the half that runs the servers.",
            "",
            "Two logins arriving in the same instant can exceed this by one. That is accepted",
            "rather than fixed with a reservation scheme: the count is read live from the proxy,",
            "and one player over a limit of several hundred is not a state anybody can observe."
    })
    default int maxPlayers() {
        return 500;
    }

    // There is no backend-limit here any more, and this comment is the reason rather than a gap in
    // the numbering. It held a copy of BACKEND_MAX_PLAYERS - the unreachable number the Paper
    // backends were given so that only this proxy ever refused a player - and this proxy refused to
    // start when max-players reached it. Retired 2026-09-04 together with the second number itself:
    // the backends are written from NETWORK_MAX_PLAYERS now, so there is no pair left to cross. A
    // network.yml in a volume that still carries the key stops the proxy with the key named, which
    // is jcore's strict load doing exactly what it is for; ConfigsTest asserts that it does.

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
            "Ten seconds is chosen against what the numbers are for: a team count in a server",
            "browser is a reason to look, not a scoreboard. A failed refresh keeps the previous",
            "snapshot rather than blanking it - see NetworkSnapshot."
    })
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
            "WHY THIS IS A LIST AND NOT A SET OF PERMISSIONS: Velocity's own /server is open to",
            "every player - its permission check only refuses on an explicit FALSE, and nothing",
            "ever set one - so a player could type '/server hunger-games' during the SMP phase and",
            "land there, past every routing decision this proxy takes. A list of what IS allowed",
            "cannot have that shape of hole, because a command nobody thought about is refused",
            "rather than permitted.",
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
            "publishes it on every start: it is one truth and an edit here reaches all four",
            "processes. Until a proxy has published it once, a backend filters nothing and says so",
            "in its log - this proxy's own enforcement never waits for anything."
    })
    default java.util.List<String> commandAllowlist() {
        return java.util.List.of(
                // Ours, and only ours. Every vanilla command is deliberately absent, including the
                // harmless-looking ones: /help lists what a player may not run, /trigger and /me
                // are surfaces this season has no answer for, and /tell is replaced by /msg below.
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
    default MotdSpec motd() {
        // createDefault, not createUnsafe with a hand-written map: it fills the instance from
        // MotdSpec's own default bodies, so the five strings exist exactly once. A createUnsafe map
        // would be a second copy of them, and the copy that goes stale is always the one nobody
        // reads.
        return Specs.createDefault(MotdSpec.class);
    }

    /**
     * One MOTD per phase. There is deliberately no shared default to fall back on: five values,
     * five meanings, and no rule about empties to remember when reading the file.
     */
    @ConfigSpec
    interface MotdSpec {

        // The name is the brand and never changes colour. It used to carry a gradient per phase -
        // light blue before the start, orange in the event, green on the SMP, grey in maintenance -
        // which is four different marks rather than one seen four times (owner, 2026-09-09). The
        // phase is what the SECOND line is for, and it already says it.
        //
        // NORDTAL_BLUE is the logo's own blue, measured off resource-pack/src/pack.png: the mark
        // is built from #24357d down through #1d2a62 and #1b285e to #13182f on near-black, and
        // #24357d is the one a person would name. That value is the brand wherever the ground is
        // light. This is not such a place: the server browser paints an almost black list, and
        // #24357d on it is a dark blue on a dark grey. So the name is rendered in a lightened tone
        // of the same hue - one brand, two applications, both written down in
        // docs/presentation.md#the-palette. A future MOTD writes NORDTAL_BLUE and nothing else;
        // BrandColourTest fails a phase that colours the name for itself again.
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
        default String preEvent() {
            return NORDTAL_BLUE
                    + "<newline><gray>Hunger Games: <white>{hg-teams}</white> teams,"
                    + " <white>{hg-participants}</white> players registered</gray>";
        }

        @Order(3)
        @Key("start-event")
        @Comment({
                "The hunger games themselves, countdown to winner. {hg-alive} is what is left of",
                "{hg-participants}; both come from the running game and drop to 0 between games."
        })
        default String startEvent() {
            return NORDTAL_BLUE
                    + "<newline><gray>Hunger Games running: <white>{hg-alive}</white> of"
                    + " <white>{hg-participants}</white> alive</gray>";
        }

        @Order(4)
        @Key("smp")
        @Comment({
                "The season proper. {smp-milestone} is the milestone the whole server is working",
                "on right now and {smp-milestone-progress} how far it has got, in percent."
        })
        default String smp() {
            return NORDTAL_BLUE
                    + "<newline><gray>Working on <white>{smp-milestone}</white>"
                    + " (<white>{smp-milestone-progress}%</white>) - <white>{online}</white>/{max} online</gray>";
        }

        @Order(5)
        @Key("maintenance")
        @Comment({
                "Planned work. Players are still let onto the proxy and held in limbo, so this is",
                "not a closed sign - it is a \"we are working, come back shortly\" sign."
        })
        default String maintenance() {
            return NORDTAL_BLUE
                    + "<newline><gray>Maintenance - back shortly</gray>";
        }
    }
}
