package eu.nordtal.season.database.network;

/**
 * The numbers behind the MOTD placeholders as of the last refresh. They never decide anything.
 *
 * @param hgTeams teams registered in the round of the Hunger Games that has not ended
 * @param hgTeamsAlive teams with a member who is not eliminated
 * @param hgParticipants members on a team (owner or accepted), not invitations
 * @param hgAlive participants with no {@code DEATH} event in the game under way
 * @param hgEliminated participants with one
 * @param smpMilestone the key of the {@code ACTIVE} milestone, empty if none
 * @param smpProgress the progress of its objectives, 0 to 100, rounded down
 * @param smpMilestonesDone the {@code UNLOCKED} milestones
 * @param smpMilestones all milestones
 * @param smpAuraTotal the sum of every player's aura
 * @param smpPlayers the players the SMP has seen
 */
public record NetworkSnapshot(
        int hgTeams,
        int hgTeamsAlive,
        int hgParticipants,
        int hgAlive,
        int hgEliminated,
        String smpMilestone,
        int smpProgress,
        int smpMilestonesDone,
        int smpMilestones,
        long smpAuraTotal,
        int smpPlayers) {

    /** The snapshot before the first refresh: zeroes and empty strings. */
    public static final NetworkSnapshot EMPTY = new NetworkSnapshot(0, 0, 0, 0, 0, "", 0, 0, 0, 0L, 0);
}
