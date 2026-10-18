package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.hungergames.roster.RosterEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves the participants of a game. A team whose partner has no linked account becomes a solo team. */
public final class Demotion {

    private Demotion() {}

    /** Returns a {@link Participant} for each member that has an {@code mc_uuid}. */
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

    /** Returns the number of teams of the participants. */
    public static int effectiveTeamCount(final List<Participant> participants) {
        final Set<UUID> teams = new java.util.HashSet<>();
        for (final Participant participant : participants) {
            teams.add(participant.teamId());
        }
        return teams.size();
    }
}
