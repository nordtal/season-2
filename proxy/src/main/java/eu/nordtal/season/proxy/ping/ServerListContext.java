package eu.nordtal.season.proxy.ping;

import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.messages.context.ContextType;
import eu.nordtal.season.messages.context.MessageContext;
import eu.nordtal.season.messages.value.Example;

/**
 * The numbers that the MOTD texts may use.
 *
 * @param online             the players on the network
 * @param max                the player limit of the network
 * @param countdown          the time until the network opens, as text
 * @param hgTeams            the registered Hunger Games teams
 * @param hgTeamsAlive       the teams with a living participant
 * @param hgParticipants     the registered Hunger Games players
 * @param hgAlive            the living participants
 * @param hgEliminated       the eliminated participants
 * @param smpMilestonesDone  the reached milestones
 * @param smpMilestones      the milestones of the season
 * @param smpAuraTotal       the aura of all players together
 * @param smpPlayers         the players who played on the SMP
 */
@ContextType(value = "server-list", name = "Server list")
public record ServerListContext(
        @Example("12") int online,
        @Example("100") int max,
        @Example("3 days 4 hours") String countdown,
        @Example("8") int hgTeams,
        @Example("5") int hgTeamsAlive,
        @Example("16") int hgParticipants,
        @Example("9") int hgAlive,
        @Example("7") int hgEliminated,
        @Example("2") int smpMilestonesDone,
        @Example("8") int smpMilestones,
        @Example("4200") long smpAuraTotal,
        @Example("40") int smpPlayers)
        implements MessageContext {

    /** Returns the context of the snapshot, the player count, the limit and the countdown. */
    public static ServerListContext of(
            final NetworkSnapshot snapshot, final int online, final int max, final String countdown) {
        return new ServerListContext(
                online,
                max,
                countdown,
                snapshot.hgTeams(),
                snapshot.hgTeamsAlive(),
                snapshot.hgParticipants(),
                snapshot.hgAlive(),
                snapshot.hgEliminated(),
                snapshot.smpMilestonesDone(),
                snapshot.smpMilestones(),
                snapshot.smpAuraTotal(),
                snapshot.smpPlayers());
    }
}
