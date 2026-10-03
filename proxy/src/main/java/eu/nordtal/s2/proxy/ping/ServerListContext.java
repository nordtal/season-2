package eu.nordtal.s2.proxy.ping;

import eu.nordtal.s2.database.network.NetworkSnapshot;
import eu.nordtal.s2.messages.context.ContextType;
import eu.nordtal.s2.messages.context.MessageContext;
import eu.nordtal.s2.messages.value.Example;

/**
 * What the server browser's text may name: the network's numbers as the last snapshot holds them.
 *
 * @param online             players on the network now
 * @param max                the network's limit
 * @param countdown          how long until the network opens, as a phrase in the text's language
 * @param hgTeams            hunger games teams registered
 * @param hgTeamsAlive       teams with somebody still alive
 * @param hgParticipants     players registered for the hunger games
 * @param hgAlive            participants still alive
 * @param hgEliminated       participants out
 * @param smpMilestonesDone  milestones finished
 * @param smpMilestones      milestones in the season
 * @param smpAuraTotal       aura earned by everybody together
 * @param smpPlayers         players who have played the SMP
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

    /** Returns the numbers of a snapshot, with the live count, the limit and the countdown beside them. */
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
