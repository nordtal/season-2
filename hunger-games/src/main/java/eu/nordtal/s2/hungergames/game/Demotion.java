package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.hungergames.db.RosterEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes the effective, post-demotion participant list at countdown time:
 * "a duo whose partner is not present when the countdown starts - never logged in, so not even a
 * dummy body - becomes a solo team with full hearts, keeping its name and colour"
 * (docs/hunger-games.md#teams-colours-and-hearts). "Present" here means has ever logged in and
 * linked - i.e. has an {@code mc_uuid} - not "is online right now": a disconnected-but-linked
 * player still gets a body on their tower (docs/hunger-games.md#start), and only a partner who
 * never even linked has no body to give.
 * <p>
 * No Bukkit dependency: this only groups {@link RosterEntry} rows, which is exactly what
 * {@code docs/hunger-games.md} means by "the effective (post-demotion) participant count" feeding
 * {@code BorderMath#deathStep} and {@code TeamColours#generatePalette}.
 * </p>
 */
public final class Demotion {

    private Demotion() {
    }

    /**
     * @param roster every active membership of the game, as returned by
     *               {@code HungerGamesDao#roster(UUID)}
     * @return one {@link Participant} per member who has an {@code mc_uuid} (i.e. has linked their
     *         account at least once) - a registered member who never linked cannot be teleported
     *         or given a body at all, and is therefore not a participant in the arithmetic sense,
     *         though it remains an {@code hg_member} row for history
     */
    public static List<Participant> resolve(final List<RosterEntry> roster) {
        final Map<UUID, List<RosterEntry>> byTeam = new LinkedHashMap<>();
        for (final RosterEntry entry : roster) {
            byTeam.computeIfAbsent(entry.teamId(), key -> new ArrayList<>()).add(entry);
        }

        final List<Participant> participants = new ArrayList<>();
        for (final List<RosterEntry> team : byTeam.values()) {
            final List<RosterEntry> linked = team.stream().filter(entry -> entry.mcUuid() != null).toList();
            final boolean demoted = team.size() == 2 && linked.size() == 1;

            for (final RosterEntry entry : linked) {
                participants.add(new Participant(
                        entry.memberId(), entry.teamId(), entry.teamName(), entry.discordId(),
                        entry.mcUuid(), true, demoted));
            }
        }
        return participants;
    }

    /** @return how many distinct teams the resolved participants belong to - the colour count. */
    public static int effectiveTeamCount(final List<Participant> participants) {
        final Set<UUID> teams = new java.util.HashSet<>();
        for (final Participant participant : participants) {
            teams.add(participant.teamId());
        }
        return teams.size();
    }
}
