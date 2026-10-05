package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.hungergames.db.RosterEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Computes the effective participant list at countdown time, after demotion.
 *
 * A duo whose partner never linked an account becomes a solo team with full hearts, keeping its name and colour.
 */
public final class Demotion {

    private Demotion() {}

    /** One {@link Participant} per member with an {@code mc_uuid}; only they can be teleported or given a body. */
    public static List<Participant> resolve(final List<RosterEntry> roster) {
        final Map<UUID, List<RosterEntry>> byTeam = new LinkedHashMap<>();
        for (final RosterEntry entry : roster) {
            byTeam.computeIfAbsent(entry.teamId(), key -> new ArrayList<>()).add(entry);
        }

        final List<Participant> participants = new ArrayList<>();
        for (final List<RosterEntry> team : byTeam.values()) {
            final List<RosterEntry> linked =
                    team.stream().filter(entry -> entry.mcUuid() != null).toList();
            final boolean demoted = team.size() == 2 && linked.size() == 1;

            for (final RosterEntry entry : linked) {
                participants.add(new Participant(
                        entry.memberId(),
                        entry.teamId(),
                        entry.teamName(),
                        Objects.requireNonNull(entry.mcUuid()),
                        true,
                        demoted));
            }
        }
        return participants;
    }

    /** How many distinct teams the resolved participants belong to, which is the colour count. */
    public static int effectiveTeamCount(final List<Participant> participants) {
        final Set<UUID> teams = new java.util.HashSet<>();
        for (final Participant participant : participants) {
            teams.add(participant.teamId());
        }
        return teams.size();
    }
}
