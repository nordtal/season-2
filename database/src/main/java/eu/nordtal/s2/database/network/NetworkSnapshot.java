package eu.nordtal.s2.database.network;

/**
 * The numbers behind the MOTD placeholders, as of the last successful refresh, and never used to decide anything.
 *
 * @param hgTeams           teams registered in the round of the Hunger Games that has not ended
 * @param hgTeamsAlive      teams with at least one member who has not been eliminated
 * @param hgParticipants    members who are actually on a team (owner or accepted), not invitations
 * @param hgAlive           participants with no {@code DEATH} event against them in the game under way
 * @param hgEliminated      participants with one, so {@code hgAlive + hgEliminated == hgParticipants}
 * @param smpMilestone      the {@code ACTIVE} milestone's key, or empty when none is active
 * @param smpProgress       how far that milestone's objectives have got, 0 to 100, rounded down
 * @param smpMilestonesDone how many milestones are {@code UNLOCKED}
 * @param smpMilestones     how many milestones exist at all
 * @param smpAuraTotal      the sum of every player's aura, negative when deaths outweigh
 * @param smpPlayers        how many players the SMP has ever seen
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

    /** What a proxy renders before its first successful refresh: zeroes and empty strings, never nulls. */
    public static final NetworkSnapshot EMPTY = new NetworkSnapshot(0, 0, 0, 0, 0, "", 0, 0, 0, 0L, 0);
}
