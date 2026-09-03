package eu.nordtal.s2.networkcontrol.ping;

/**
 * The numbers behind the MOTD placeholders, as of the last successful refresh.
 *
 * <p>Immutable and replaced wholesale, never mutated in place: a ping renders from whichever
 * instance it happens to read, and a half-updated snapshot would put two different moments in one
 * MOTD - "12 of 8 alive" is the kind of thing screenshots survive far longer than the bug does.
 *
 * <p><b>Everything here is an approximation with an age</b>, up to
 * {@code network.yml#snapshot-refresh-seconds} old, and none of it decides anything. That is the
 * whole reason it can be cached at all: a server-list ping is unauthenticated and arrives in
 * bursts, so the database must not be on that path.
 *
 * <p>{@link #EMPTY} is what a proxy that has never managed a refresh renders, and it is all zeroes
 * and empty strings rather than nulls - the placeholder resolver substitutes it without a special
 * case, so a database that has never answered shows a MOTD with zeroes in it instead of no MOTD.
 *
 * @param hgState           {@code hg_game.state} of the one open game, or empty when there is none
 * @param hgTeams           registered teams in that game
 * @param hgTeamsAlive      teams with at least one member who has not been eliminated
 * @param hgParticipants    members who are actually on a team (owner or accepted), not invitations
 * @param hgAlive           participants with no {@code DEATH} event against them
 * @param hgEliminated      participants with one - {@code hgAlive + hgEliminated == hgParticipants}
 * @param smpMilestone      the {@code ACTIVE} milestone's key, or empty when none is active
 * @param smpProgress       how far that milestone's objectives have got, 0-100, rounded down
 * @param smpMilestonesDone how many milestones are {@code UNLOCKED}
 * @param smpMilestones     how many milestones exist at all
 * @param smpAuraTotal      the sum of every player's aura; it can be negative, deaths cost aura
 * @param smpPlayers        how many players the SMP has ever seen
 */
public record NetworkSnapshot(
        String hgState,
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

    /** What a proxy renders before its first successful refresh, and if one never succeeds. */
    public static final NetworkSnapshot EMPTY =
            new NetworkSnapshot("", 0, 0, 0, 0, 0, "", 0, 0, 0, 0L, 0);
}
