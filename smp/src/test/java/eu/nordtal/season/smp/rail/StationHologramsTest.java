package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StationHologramsTest {

    private static final UUID PLACER = UUID.randomUUID();
    private static final Instant NAMED = Instant.parse("2026-11-04T10:00:00Z");

    /** Records what was drawn; a hologram is the number of its drawing. */
    private static final class Recording implements StationHolograms.Drawing<Integer> {

        final List<String> calls = new ArrayList<>();
        final Set<Integer> killed = new HashSet<>();
        private int drawn;

        @Override
        public Integer draw(final StationRow station, final String name) {
            drawn++;
            calls.add("draw " + drawn + " " + name);
            return drawn;
        }

        @Override
        public void retitle(final Integer hologram, final String name) {
            calls.add("retitle " + hologram + " " + name);
        }

        @Override
        public void remove(final Integer hologram) {
            calls.add("remove " + hologram);
        }

        @Override
        public boolean drawn(final Integer hologram) {
            return !killed.contains(hologram);
        }
    }

    private final Recording drawing = new Recording();
    private final StationHolograms<Integer> holograms = new StationHolograms<>(drawing);

    private static StationRow station(final String world, final int x, final int z, final @Nullable String name) {
        return new StationRow(
                UUID.randomUUID(), world, x, 64, z, "NORTH_SOUTH", PLACER, name, name == null ? null : NAMED);
    }

    @Test
    void theSpawnStationGoesByItsTranslatedNameAndAStationByItsOwn() {
        holograms.spawnNamed(() -> "Spawn station");
        final StationRow spawn = new StationRow(UUID.randomUUID(), "world", 0, 64, 0, "NORTH_SOUTH", null, null, null);
        assertEquals("Spawn station", holograms.nameOf(spawn));
        assertEquals("Mine", holograms.nameOf(station("world", 5, 0, "Mine")));
    }

    @Test
    void aNamedStationShowsItsName() {
        final StationRow spawn = station("world", 0, 0, "Spawn");

        holograms.show(spawn);

        assertEquals(List.of("draw 1 Spawn"), drawing.calls);
        assertEquals(Set.of(spawn.id()), holograms.shown());
    }

    @Test
    void anUnnamedStationShowsNothing() {
        holograms.show(station("world", 0, 0, null));

        assertTrue(drawing.calls.isEmpty());
        assertTrue(holograms.shown().isEmpty());
    }

    @Test
    void namingAStationDrawsItsHologramOnce() {
        final StationRow unnamed = station("world", 0, 0, null);
        holograms.show(unnamed);

        holograms.show(unnamed.renamed("Spawn", NAMED));
        holograms.show(unnamed.renamed("Spawn", NAMED));

        assertEquals(List.of("draw 1 Spawn"), drawing.calls);
    }

    @Test
    void aRenameRetitlesTheSameHologram() {
        final StationRow spawn = station("world", 0, 0, "Spawn");
        holograms.show(spawn);

        holograms.show(spawn.renamed("Harbour", NAMED));

        assertEquals(List.of("draw 1 Spawn", "retitle 1 Harbour"), drawing.calls);
    }

    @Test
    void aStationThatLosesItsNameLosesItsHologram() {
        final StationRow spawn = station("world", 0, 0, "Spawn");
        holograms.show(spawn);

        holograms.show(new StationRow(spawn.id(), "world", 0, 64, 0, "NORTH_SOUTH", PLACER, null, null));

        assertEquals(List.of("draw 1 Spawn", "remove 1"), drawing.calls);
        assertTrue(holograms.shown().isEmpty());
    }

    @Test
    void aHologramRemovedByAnythingElseIsDrawnAgain() {
        final StationRow spawn = station("world", 0, 0, "Spawn");
        holograms.show(spawn);
        drawing.killed.add(1);

        holograms.show(spawn.renamed("Harbour", NAMED));

        assertEquals(List.of("draw 1 Spawn", "remove 1", "draw 2 Harbour"), drawing.calls);
    }

    @Test
    void aRemovedStationLosesItsHologram() {
        final StationRow spawn = station("world", 0, 0, "Spawn");
        holograms.show(spawn);

        holograms.hide(spawn.id());
        holograms.hide(spawn.id());

        assertEquals(List.of("draw 1 Spawn", "remove 1"), drawing.calls);
    }

    @Test
    void aChunkThatUnloadsTakesOnlyItsOwnHologramsAndGetsThemBackOnLoad() {
        final StationRow here = station("world", 3, 5, "Here");
        final StationRow nextChunk = station("world", 16, 5, "Next");
        final StationRow otherWorld = station("nether", 3, 5, "Below");
        holograms.show(here);
        holograms.show(nextChunk);
        holograms.show(otherWorld);

        holograms.unloaded("world", StationHolograms.chunkKey(0, 0));

        assertEquals(Set.of(nextChunk.id(), otherWorld.id()), holograms.shown());
        holograms.show(here);
        assertEquals(List.of("draw 1 Here", "draw 2 Next", "draw 3 Below", "remove 1", "draw 4 Here"), drawing.calls);
    }

    @Test
    void chunkKeysMatchPaperForNegativeChunks() {
        final StationRow west = station("world", -1, -17, "West");

        assertEquals(0xffffffffL | (0xfffffffeL << 32), StationHolograms.chunkKey(west));
    }

    @Test
    void reconcilingShowsTheLoadedNamedStationsAndDropsTheRest() {
        final StationRow kept = station("world", 0, 0, "Kept");
        final StationRow lost = station("world", 32, 0, "Lost");
        final StationRow far = station("world", 320, 0, "Far");
        final StationRow nether = station("nether", 0, 0, "Below");
        holograms.show(kept);
        holograms.show(lost);
        holograms.show(nether);

        final StationRow renamed = kept.renamed("Kept 2", NAMED);
        holograms.reconcile("world", List.of(renamed, far), station -> station.x() < 100);

        assertEquals(Set.of(kept.id(), nether.id()), holograms.shown());
        assertEquals(
                List.of("draw 1 Kept", "draw 2 Lost", "draw 3 Below", "remove 2", "retitle 1 Kept 2"), drawing.calls);
    }

    @Test
    void aStationPlacedAnewOnTheSameIdIsDrawnWhereItNowStands() {
        final StationRow spawn = station("world", 0, 0, "Spawn");
        holograms.show(spawn);

        holograms.show(new StationRow(spawn.id(), "world", 1, 64, 0, "NORTH_SOUTH", PLACER, "Spawn", NAMED));

        assertEquals(List.of("draw 1 Spawn", "remove 1", "draw 2 Spawn"), drawing.calls);
    }

    @Test
    void stoppingRemovesEveryHologram() {
        holograms.show(station("world", 0, 0, "Spawn"));
        holograms.show(station("nether", 0, 0, "Below"));

        holograms.clear();

        assertTrue(holograms.shown().isEmpty());
        assertEquals(Set.of("remove 1", "remove 2"), Set.copyOf(drawing.calls.subList(2, drawing.calls.size())));
    }
}
