package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.rail.StationNames.Named;
import eu.nordtal.season.smp.rail.StationNames.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StationNamesTest {

    private static final Instant FIRST = Instant.parse("2026-11-01T10:00:00Z");

    private static Named named(final int station, final String name, final int minutesLater) {
        return new Named(new UUID(0, station), name, FIRST.plusSeconds(60L * minutesLater));
    }

    @Test
    void aNameHasAtMostThirtyTwoCharacters() {
        assertEquals(Verdict.ACCEPTED, StationNames.judge("a".repeat(32), List.of()));
        assertEquals(Verdict.TOO_LONG, StationNames.judge("a".repeat(33), List.of()));
    }

    @Test
    void aBlankNameIsEmpty() {
        assertEquals(Verdict.EMPTY, StationNames.judge("", List.of()));
        assertEquals(Verdict.EMPTY, StationNames.judge("   ", List.of()));
    }

    @Test
    void aNameIsUniqueInItsNetworkRegardlessOfCase() {
        assertEquals(Verdict.TAKEN, StationNames.judge("spawn", List.of("Spawn")));
        assertEquals(Verdict.ACCEPTED, StationNames.judge("Spawn Nord", List.of("Spawn")));
    }

    private static StationRow row(final int station, final String name) {
        return new StationRow(
                new UUID(0, station), "nordtal", station, 64, 0, "EAST_WEST", new UUID(1, station), name, FIRST);
    }

    @Test
    void aTypedNameIsStrippedBeforeTheRulesJudgeIt() {
        assertEquals(
                Verdict.ACCEPTED, StationNames.judgeTyped("  " + "a".repeat(32) + "  ", new UUID(0, 1), List.of()));
        assertEquals(Verdict.EMPTY, StationNames.judgeTyped("   ", new UUID(0, 1), List.of()));
    }

    @Test
    void aStationMayTakeItsOwnNameInAnotherCaseButNotAnotherStationsName() {
        final List<StationRow> network = List.of(row(1, "Spawn"), row(2, "Mine"));

        assertEquals(Verdict.ACCEPTED, StationNames.judgeTyped("SPAWN", new UUID(0, 1), network));
        assertEquals(Verdict.TAKEN, StationNames.judgeTyped(" mine ", new UUID(0, 1), network));
    }

    @Test
    void theStationNamedLaterGetsASuffixOnAMerge() {
        final Map<UUID, String> renamed =
                StationNames.settleClashes(List.of(named(2, "Spawn", 5), named(1, "Spawn", 0), named(3, "Hafen", 1)));

        assertEquals(Map.of(new UUID(0, 2), "Spawn 2"), renamed);
    }

    @Test
    void aSuffixSkipsTheNumbersAlreadyTaken() {
        final Map<UUID, String> renamed = StationNames.settleClashes(
                List.of(named(1, "Spawn", 0), named(2, "Spawn 2", 1), named(3, "spawn", 2), named(4, "Spawn", 3)));

        assertEquals(Map.of(new UUID(0, 3), "spawn 3", new UUID(0, 4), "Spawn 4"), renamed);
    }

    @Test
    void aSuffixedNameStillFitsThirtyTwoCharacters() {
        final String longName = "b".repeat(32);

        final Map<UUID, String> renamed =
                StationNames.settleClashes(List.of(named(1, longName, 0), named(2, longName, 1)));

        assertEquals("b".repeat(30) + " 2", renamed.get(new UUID(0, 2)));
    }
}
