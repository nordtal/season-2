package eu.nordtal.season.smp.eye;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFramesTest {

    @TempDir
    Path directory;

    private RegionFrames.Scanned scan(final Path file) throws IOException {
        final RegionFrames reader = new RegionFrames();
        try {
            return reader.scan(file);
        } finally {
            reader.close();
        }
    }

    @Test
    void theFramesOfAChunkAreFoundAtTheirBlockPositions() throws IOException {
        // Region 10.7 holds chunk 327.235 at index 7 + 11 * 32; section 1 spans y 16 to 31.
        final Path file = directory.resolve("r.10.7.mca");
        final Map<Integer, byte[]> chunks = new LinkedHashMap<>();
        chunks.put(0, TestRegions.deflated(TestRegions.chunk(1)));
        chunks.put(
                7 + 11 * 32,
                TestRegions.deflated(TestRegions.chunk(1, TestRegions.index(0, 17, 1), TestRegions.index(15, 17, 15))));
        TestRegions.write(file, chunks);

        final RegionFrames.Scanned scanned = scan(file);

        assertTrue(scanned.complete());
        assertEquals(List.of(new Frame(5232, 17, 3761), new Frame(5247, 17, 3775)), scanned.frames());
    }

    @Test
    void aSectionBelowZeroCountsDown() throws IOException {
        final Path file = directory.resolve("r.-1.-1.mca");
        TestRegions.write(
                file, Map.of(31 + 31 * 32, TestRegions.deflated(TestRegions.chunk(-4, TestRegions.index(3, 5, 4)))));

        assertEquals(List.of(new Frame(-13, -59, -12)), scan(file).frames());
    }

    @Test
    void aTornChunkMarksTheScanIncompleteAndTheOthersAreStillRead() throws IOException {
        final Path file = directory.resolve("r.0.0.mca");
        final byte[] whole = TestRegions.deflated(TestRegions.chunk(0, TestRegions.index(1, 2, 3)));
        final byte[] torn = new byte[] {0x78, (byte) 0x9c, 1, 2, 3};
        TestRegions.write(file, Map.of(0, whole, 1, torn));

        final RegionFrames.Scanned scanned = scan(file);

        assertFalse(scanned.complete());
        assertEquals(List.of(new Frame(1, 2, 3)), scanned.frames());
    }

    @Test
    void anEmptyRegionFileHoldsNoFrames() throws IOException {
        final Path file = directory.resolve("r.0.0.mca");
        Files.write(file, new byte[0]);

        assertEquals(new RegionFrames.Scanned(List.of(), true), scan(file));
    }

    @Test
    void aSectionMadeOfFramesAloneHoldsAFrameInEveryBlock() {
        final Map<String, Object> states = Map.of("palette", List.of(RegionFrames.FRAME));
        final Map<String, Object> chunk = Map.of("sections", List.of(Map.of("Y", (byte) 0, "block_states", states)));

        assertEquals(4096, RegionFrames.frames(chunk, 0, 0).size());
    }

    @Test
    void aPaletteEntryIsReadInEveryFormTheGameWrites() {
        assertEquals(RegionFrames.FRAME, RegionFrames.name(RegionFrames.FRAME));
        assertEquals(RegionFrames.FRAME, RegionFrames.name(Map.of("", RegionFrames.FRAME)));
        assertEquals(RegionFrames.FRAME, RegionFrames.name(Map.of("id", RegionFrames.FRAME, "properties", Map.of())));
        assertEquals(RegionFrames.FRAME, RegionFrames.name(Map.of("Name", RegionFrames.FRAME)));
    }
}
