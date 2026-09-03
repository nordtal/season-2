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
 * player landed on, which is always {@code limbo} first. The three backends are now configured far
 * above any network limit ({@code BACKEND_MAX_PLAYERS}, see {@link #backendLimit()}) and refuse
 * nobody; the proxy refuses, once, at the login gate, where it can say why.
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
            "Two logins arriving in the same instant can exceed this by one. That is accepted",
            "rather than fixed with a reservation scheme: the count is read live from the proxy,",
            "and one player over a limit of several hundred is not a state anybody can observe."
    })
    default int maxPlayers() {
        return 500;
    }

    @Order(2)
    @Key("backend-limit")
    @Comment({
            "What the entrypoint writes into every Paper backend's server.properties#max-players",
            "(BACKEND_MAX_PLAYERS in .env). It is NOT a second network limit - it is the number",
            "that must never be reached, so that the proxy is the only thing that ever refuses a",
            "player.",
            "",
            "It is repeated here so that this proxy can check it. max-players above this value",
            "would mean the backends quietly become the real limit again - the exact fault this",
            "arrangement exists to remove - so the proxy REFUSES TO START rather than run into it",
            "at some busy moment. If you raise one, raise the other.",
            "",
            "Paper's own default is 20. Until 2026-09-02 nothing set it at all, and the network",
            "advertised 500 slots while limbo refused the 21st player with \"Server full\" - after",
            "they had passed the login gate, been offered the resource pack and waited for it."
    })
    default int backendLimit() {
        return 1000;
    }

    @Order(3)
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
            return "<gradient:#5ec2ff:#a8e6ff><bold>nordtal.eu</bold></gradient>"
                    + "<newline><gray>Season 2 opens in <white>{countdown}</white></gray>";
        }

        @Order(2)
        @Key("pre-event")
        @Comment({
                "The network is open, the lobby stands and teams register for the hunger games.",
                "{hg-teams} is what registration has produced so far."
        })
        default String preEvent() {
            return "<gradient:#5ec2ff:#a8e6ff><bold>nordtal.eu</bold></gradient>"
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
            return "<gradient:#ffb457:#ff7a45><bold>nordtal.eu</bold></gradient>"
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
            return "<gradient:#7ee081:#38b000><bold>nordtal.eu</bold></gradient>"
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
            return "<gradient:#c0c0c0:#8a8a8a><bold>nordtal.eu</bold></gradient>"
                    + "<newline><gray>Maintenance - back shortly</gray>";
        }
    }
}
