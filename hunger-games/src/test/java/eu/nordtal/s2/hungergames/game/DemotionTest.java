package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.hungergames.db.MemberState;
import eu.nordtal.s2.hungergames.db.RosterEntry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemotionTest {

    private static final UUID TEAM_A = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID TEAM_B = UUID.fromString("22222222-0000-0000-0000-000000000000");
    private static final UUID TEAM_C = UUID.fromString("33333333-0000-0000-0000-000000000000");

    private static RosterEntry entry(final UUID teamId, final String teamName, final String discordId,
                                      final UUID mcUuid) {
        return new RosterEntry(UUID.randomUUID(), teamId, teamName, null, null, discordId,
                MemberState.ACCEPTED, false, mcUuid);
    }

    @Test
    void aSoloTeamIsNeverDemoted() {
        final RosterEntry solo = entry(TEAM_A, "Foxes", "1", UUID.randomUUID());
        final List<Participant> resolved = Demotion.resolve(List.of(solo));

        assertEquals(1, resolved.size());
        assertFalse(resolved.get(0).demotedToSolo());
    }

    @Test
    void aFullDuoWithBothLinkedIsNeverDemoted() {
        final RosterEntry owner = entry(TEAM_B, "Wolves", "1", UUID.randomUUID());
        final RosterEntry partner = entry(TEAM_B, "Wolves", "2", UUID.randomUUID());
        final List<Participant> resolved = Demotion.resolve(List.of(owner, partner));

        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().noneMatch(Participant::demotedToSolo));
    }

    @Test
    void aDuoWhosePartnerNeverLinkedIsDemotedToSolo() {
        final RosterEntry owner = entry(TEAM_C, "Bears", "1", UUID.randomUUID());
        final RosterEntry unlinkedPartner = entry(TEAM_C, "Bears", "2", null);
        final List<Participant> resolved = Demotion.resolve(List.of(owner, unlinkedPartner));

        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).demotedToSolo());
        assertEquals("1", resolved.get(0).discordId());
    }

    @Test
    void effectiveTeamCountCountsDistinctTeamsNotPlayers() {
        final RosterEntry owner = entry(TEAM_B, "Wolves", "1", UUID.randomUUID());
        final RosterEntry partner = entry(TEAM_B, "Wolves", "2", UUID.randomUUID());
        final RosterEntry solo = entry(TEAM_A, "Foxes", "3", UUID.randomUUID());

        final List<Participant> resolved = Demotion.resolve(List.of(owner, partner, solo));
        assertEquals(2, Demotion.effectiveTeamCount(resolved));
        assertEquals(3, resolved.size());
    }

    @Test
    void aMemberWhoNeverLinkedAtAllProducesNoParticipant() {
        final RosterEntry neverLinked = entry(TEAM_A, "Foxes", "1", null);
        assertTrue(Demotion.resolve(List.of(neverLinked)).isEmpty());
    }
}
