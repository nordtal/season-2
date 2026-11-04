package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.config.StationBlockSpec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpawnStationTest {

    private static final SpawnStation.Target HERE = new SpawnStation.Target("nordtal", at(0, 64, 0));
    private static final SpawnStation.Target THERE = new SpawnStation.Target("nordtal", at(50, 70, -20));

    private static StationRow spawnAt(final SpawnStation.Target target) {
        return new StationRow(
                new UUID(0, 1),
                target.world(),
                target.pos().x(),
                target.pos().y(),
                target.pos().z(),
                "NORTH_SOUTH",
                null,
                null,
                null);
    }

    @Test
    void aSetBlockWithoutASpawnStationGetsOnePlaced() {
        assertEquals(
                Optional.of(new SpawnStation.Step.Place(HERE)),
                SpawnStation.next(Optional.of(HERE), List.of(), _ -> false));
    }

    @Test
    void aSpawnStationThatLostItsRailIsPlacedAgain() {
        assertEquals(
                Optional.of(new SpawnStation.Step.Place(HERE)),
                SpawnStation.next(Optional.of(HERE), List.of(spawnAt(HERE)), HERE::equals));
    }

    @Test
    void aSpawnStationInPlaceIsLeftAlone() {
        assertEquals(Optional.empty(), SpawnStation.next(Optional.of(HERE), List.of(spawnAt(HERE)), _ -> false));
    }

    @Test
    void aMovedSettingRemovesTheOldSpawnStationFirstAndThenPlacesTheNewOne() {
        final StationRow old = spawnAt(HERE);
        assertEquals(
                Optional.of(new SpawnStation.Step.Remove(old)),
                SpawnStation.next(Optional.of(THERE), List.of(old), _ -> false));
        assertEquals(
                Optional.of(new SpawnStation.Step.Place(THERE)),
                SpawnStation.next(Optional.of(THERE), List.of(), _ -> false));
    }

    @Test
    void anEmptySettingRemovesTheSpawnStationAndPlacesNone() {
        final StationRow old = spawnAt(HERE);
        assertEquals(
                Optional.of(new SpawnStation.Step.Remove(old)),
                SpawnStation.next(Optional.empty(), List.of(old), _ -> true));
        assertEquals(Optional.empty(), SpawnStation.next(Optional.empty(), List.of(), _ -> true));
    }

    @Test
    void theSettingsEntryIsTheTarget() {
        final StationBlockSpec entry = new StationBlockSpec() {
            @Override
            public int x() {
                return 50;
            }

            @Override
            public int y() {
                return 70;
            }

            @Override
            public int z() {
                return -20;
            }
        };
        assertEquals(Optional.of(THERE), SpawnStation.target(List.of(entry)));
        assertEquals(Optional.empty(), SpawnStation.target(List.of()));
    }
}
