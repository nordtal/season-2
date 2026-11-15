package eu.nordtal.season.smp.eye;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameScanTest {

    @TempDir
    Path directory;

    private Path region(final String name, final int... frameIndices) throws IOException {
        final Path file = directory.resolve("region").resolve(name);
        TestRegions.write(file, Map.of(0, TestRegions.deflated(TestRegions.chunk(0, frameIndices))));
        return file;
    }

    private static int[] ring() {
        final List<Frame> frames = PortalTest.ring(0, 5, 0);
        return frames.stream()
                .mapToInt(frame -> TestRegions.index(frame.x(), frame.y(), frame.z()))
                .toArray();
    }

    @Test
    void aScanFindsThePortal() throws IOException {
        region("r.0.0.mca", ring());
        region("r.1.0.mca");
        final FrameIndex index = new FrameIndex();

        assertEquals(
                new FrameScan.Outcome(2, 0, 0, false), FrameScan.world(index, "world", directory.resolve("region")));

        assertEquals(List.of(new Portal(2.5, 5, 2.5)), index.portals("world"));
    }

    @Test
    void theNextStartReadsOnlyTheRegionFilesThatChanged() throws IOException {
        region("r.0.0.mca", ring());
        final Path changed = region("r.1.0.mca");
        final Path cache = directory.resolve("cache.tsv");
        final FrameIndex first = new FrameIndex();
        FrameScan.world(first, "world", directory.resolve("region"));
        first.save(cache);

        region("r.1.0.mca", TestRegions.index(4, 9, 4));
        Files.setLastModifiedTime(
                changed, FileTime.fromMillis(Files.getLastModifiedTime(changed).toMillis() + 5000));
        final FrameIndex next = new FrameIndex();
        next.load(cache);

        assertEquals(List.of(new Portal(2.5, 5, 2.5)), next.portals("world"));
        assertEquals(
                new FrameScan.Outcome(1, 1, 0, false), FrameScan.world(next, "world", directory.resolve("region")));
        assertEquals(List.of(new Portal(2.5, 5, 2.5), new Portal(516.5, 9, 4.5)), next.portals("world"));
    }

    @Test
    void aRegionFileThatIsGoneTakesItsPortalAlong() throws IOException {
        final Path gone = region("r.0.0.mca", ring());
        final FrameIndex index = new FrameIndex();
        FrameScan.world(index, "world", directory.resolve("region"));
        Files.delete(gone);

        FrameScan.world(index, "world", directory.resolve("region"));

        assertEquals(List.of(), index.portals("world"));
    }

    @Test
    void framesPlacedAndBrokenSinceTheScanCount() throws IOException {
        region("r.0.0.mca", ring());
        final FrameIndex index = new FrameIndex();
        FrameScan.world(index, "world", directory.resolve("region"));

        PortalTest.ring(0, 5, 0).forEach(frame -> index.broken("world", frame));
        PortalTest.ring(100, 40, 100).forEach(frame -> index.placed("world", frame));

        assertEquals(List.of(new Portal(102.5, 40, 102.5)), index.portals("world"));
    }

    @Test
    void aWorldWithoutRegionFilesHoldsNoPortal() throws IOException {
        final FrameIndex index = new FrameIndex();

        FrameScan.world(index, "world", directory.resolve("missing"));

        assertEquals(List.of(), index.portals("world"));
    }

    @Test
    void aStoppedScanEndsAndLeavesTheThreadFreeToSave() throws IOException {
        region("r.0.0.mca", ring());
        final FrameIndex index = new FrameIndex();

        Thread.currentThread().interrupt();
        final FrameScan.Outcome outcome = FrameScan.world(index, "world", directory.resolve("region"));

        assertTrue(outcome.stopped());
        assertFalse(Thread.currentThread().isInterrupted());
        index.save(directory.resolve("cache.tsv"));
        assertEquals(List.of(), index.portals("world"));
    }

    @Test
    void aCacheLineThatCannotBeReadIsScannedAgain() throws IOException {
        region("r.0.0.mca", ring());
        final Path cache = directory.resolve("cache.tsv");
        Files.writeString(cache, "world\tr.0.0.mca\t1\t2\t1,2\nworld\tr.9.9.mca\tx\t2\t\n");
        final FrameIndex index = new FrameIndex();
        index.load(cache);

        assertNull(index.region("world", "r.0.0.mca"));
        assertEquals(
                new FrameScan.Outcome(1, 0, 0, false), FrameScan.world(index, "world", directory.resolve("region")));
        assertEquals(List.of(new Portal(2.5, 5, 2.5)), index.portals("world"));
    }
}
