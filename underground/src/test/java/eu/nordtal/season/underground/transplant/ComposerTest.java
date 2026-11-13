package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.anvil.TestChunks;
import eu.nordtal.season.underground.block.Blocks;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One chunk: the map is stone to y 63 with a chest at y 20, the generated world deepslate with a cave to y 40. */
class ComposerTest {

    private final Tile tile = new Tile(0, 0, 16, 16, ChunkBlocks.HEIGHT);
    private final Tally tally = new Tally();
    private Map<String, Object> result = new LinkedHashMap<>();

    @BeforeEach
    void compose() {
        final Blocks blocks = Blocks.load();
        final Map<String, Object> map = TestChunks.chunk(
                0,
                0,
                (x, y, z) ->
                        x == 8 && y == 20 && z == 8 ? "minecraft:chest" : y < 64 ? "minecraft:stone" : "minecraft:air");
        Nbt.list(map, "block_entities").add(entity("minecraft:chest", 20));
        final Map<String, Object> parallel = TestChunks.chunk(0, 0, (x, y, z) -> {
            final boolean cave = x >= 4 && x <= 11 && z >= 4 && z <= 11 && y >= 0 && y <= 40;
            return x == 8 && y == 0 && z == 8
                    ? "minecraft:spawner"
                    : cave ? "minecraft:cave_air" : "minecraft:deepslate";
        });
        Nbt.list(parallel, "block_entities").add(entity("minecraft:mob_spawner", 0));
        parallel.put(
                "structures",
                new LinkedHashMap<>(Map.of("starts", new LinkedHashMap<>(Map.of("minecraft:mineshaft", 1)))));
        TileLoader.place(tile, tile.map, new ChunkBlocks(map), 0, 0, blocks::mapCell);
        TileLoader.place(tile, tile.parallel, new ChunkBlocks(parallel), 0, 0, blocks::parallelCell);
        for (int cell = 0; cell < tile.size(); cell++) {
            final int y = tile.layer(cell) + ChunkBlocks.MIN_Y;
            final boolean chest = tile.x(cell) == 8 && y == 20 && tile.z(cell) == 8;
            final boolean open = Blocks.open(tile.parallel[cell]);
            tile.flags[cell] = (byte) (y >= 50 || chest ? Tile.KEPT : y >= 38 && open ? Tile.CLOSED : 0);
        }
        new Composer(tile, tally).compose(map, parallel, 0, 0);
        result = map;
    }

    @Test
    void aKeptCellHoldsTheBlockOfTheMapAndAnyOtherTheBlockOfTheGeneratedWorld() {
        final ChunkBlocks blocks = new ChunkBlocks(result);
        assertEquals("minecraft:stone", Section.name(blocks.entry(8, 60, 8)));
        assertEquals("minecraft:chest", Section.name(blocks.entry(8, 20, 8)));
        assertEquals("minecraft:deepslate", Section.name(blocks.entry(1, 10, 1)));
        assertEquals("minecraft:cave_air", Section.name(blocks.entry(6, 10, 6)));
    }

    @Test
    void aClosedCellOfACaveHoldsTheRockNearIt() {
        assertEquals("minecraft:deepslate", Section.name(new ChunkBlocks(result).entry(6, 39, 6)));
        assertEquals(8 * 8 * 3, tally.closed);
    }

    @Test
    void blockEntitiesAndStructuresFollowTheirBlocks() {
        final NbtList entities = Nbt.list(result, "block_entities");
        assertEquals(2, entities.size());
        assertEquals("minecraft:chest", Nbt.text(Nbt.compound(entities.get(0)), "id"));
        assertEquals("minecraft:mob_spawner", Nbt.text(Nbt.compound(entities.get(1)), "id"));
        final Map<String, Object> structures = Nbt.compound(result.get("structures"));
        assertEquals(1, Nbt.compound(structures.get("starts")).get("minecraft:mineshaft"));
    }

    @Test
    void lightAndHeightmapsAreLeftForTheServerToWorkOut() {
        assertEquals((byte) 0, result.get("isLightOn"));
        assertEquals(Map.of(), result.get("Heightmaps"));
        assertFalse(Nbt.compound(Nbt.list(result, "sections").getFirst()).containsKey("SkyLight"));
    }

    private static Map<String, Object> entity(final String id, final int y) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", id);
        entity.put("x", 8);
        entity.put("y", y);
        entity.put("z", 8);
        return entity;
    }
}
