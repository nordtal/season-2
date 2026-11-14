package eu.nordtal.season.underground.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionTest {

    @Test
    void aChunkComesBackFromARegionFileAsItWasWritten(@TempDir final Path directory) throws IOException {
        final NbtList empty = new NbtList(Nbt.LONG);
        final NbtList mixed = new NbtList(Nbt.COMPOUND);
        mixed.add(new LinkedHashMap<>(Map.of("", "minecraft:stone")));
        final Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("byte", (byte) -3);
        chunk.put("short", (short) 700);
        chunk.put("int", 5023);
        chunk.put("long", 1L << 40);
        chunk.put("float", 1.5f);
        chunk.put("double", -2.25);
        chunk.put("text", "Nordtal");
        chunk.put("empty", empty);
        chunk.put("mixed", mixed);
        chunk.put("ints", new int[] {1, -2});
        final Path file = Region.file(directory, 3, -2);

        Region.write(file, new TreeMap<>(Map.of(40, chunk)));
        final Map<String, Object> read = Region.read(file).get(40);

        assertArrayEquals(Nbt.write(chunk), Nbt.write(read));
        assertEquals(Nbt.LONG, Nbt.list(read, "empty").elementType());
        assertEquals("minecraft:stone", Nbt.unwrap(Nbt.list(read, "mixed").getFirst()));
    }

    @Test
    void aChunkTooLargeForTheRegionFileIsStoredBesideIt(@TempDir final Path directory) throws IOException {
        final long[] noise = new long[200_000];
        final Random random = new Random(1);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = random.nextLong();
        }
        final Path file = Region.file(directory, 0, 0);

        Region.write(file, new TreeMap<>(Map.of(33, new LinkedHashMap<>(Map.of("noise", noise)))));

        assertTrue(Files.exists(directory.resolve("c.1.1.mcc")));
        assertArrayEquals(noise, (long[]) Region.read(file).get(33).get("noise"));
    }

    @Test
    void onlyTheWantedChunksAreRead(@TempDir final Path directory) throws IOException {
        final Path file = Region.file(directory, 0, 0);
        Region.write(
                file,
                new TreeMap<>(Map.of(1, new LinkedHashMap<>(Map.of("a", 1)), 2, new LinkedHashMap<>(Map.of("a", 2)))));

        assertEquals(java.util.Set.of(2), Region.read(file, index -> index == 2).keySet());
    }

    @Test
    void indicesComeBackFromTheirPackedForm() {
        final int[] indices = new int[Section.BLOCKS];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i % 37;
        }
        final Map<String, Object> container = new LinkedHashMap<>();
        final NbtList palette = new NbtList(Nbt.STRING);
        for (int i = 0; i < 37; i++) {
            palette.add("minecraft:block_" + i);
        }
        container.put("palette", palette);
        container.put("data", Section.pack(indices, 37, 4));

        assertArrayEquals(indices, Section.blockIndices(container));
    }
}
