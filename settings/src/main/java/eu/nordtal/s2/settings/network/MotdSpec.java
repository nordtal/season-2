package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * What the server browser shows, one MOTD per season phase in MiniMessage, with no shared default to fall back on.
 *
 * Placeholders in braces are substituted before parsing; the proxy's {@code Placeholders} names every one.
 */
@ConfigSpec(
        header = {
            "network: what the server browser shows, per season phase, in MiniMessage",
            "",
            "A MOTD is two lines; <newline> starts the second. Placeholders in braces are",
            "substituted before parsing. An unanswerable one renders as 0, an unknown one is",
            "left standing.",
            "",
            "  everywhere      {season} {online} {max} {phase} {players:<server>}",
            "  pre-launch      {countdown}   (time to season_phase.launch, days and hours)",
            "  hunger games    {hg-state} {hg-teams} {hg-teams-alive} {hg-participants}",
            "                  {hg-alive} {hg-eliminated}",
            "  smp             {smp-milestone} {smp-milestone-progress} {smp-milestones-done}",
            "                  {smp-milestones-total} {smp-aura-total} {smp-players}",
            "",
            "{players:<server>} takes a server name as velocity.toml spells it, e.g.",
            "{players:smp}. The three the phases route to are limbo, hunger-games and smp."
        })
public interface MotdSpec {

    // A lightened #24357d, readable on the dark server list; NetworkSettingsTest refuses a self-coloured phase.
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
        return NORDTAL_BLUE + "<newline><gray>{season} opens in <white>{countdown}</white></gray>";
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
                + "<newline><gray>{season} running - <white>{smp-milestones-done}</white> of"
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
