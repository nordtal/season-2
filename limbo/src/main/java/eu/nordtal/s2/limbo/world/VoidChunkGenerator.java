package eu.nordtal.s2.limbo.world;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A chunk generator that generates nothing at all.
 * <p>
 * Every {@code shouldGenerate*} hook is answered {@code false}, which is what turns off vanilla's
 * own generation rather than generating over the top of it: no noise, no surface, no bedrock, no
 * caves, no decorations, no structures and no mobs. The {@code generate*} methods are left as the
 * base class's empty implementations, because there is nothing to write into the chunk.
 * </p>
 * <p>
 * The result is a world that costs almost nothing to keep loaded - which matters here, because the
 * waiting room is on the path of every login and its chunks are generated for a player who is
 * looking at a black screen and will be gone in a few seconds.
 * </p>
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public List<org.bukkit.generator.BlockPopulator> getDefaultPopulators(final org.bukkit.World world) {
        // Populators run after generation and are the one thing the shouldGenerate* switches above
        // do not cover. An empty list is what keeps a vanilla populator from putting a tree in a
        // world that has no ground.
        return Collections.emptyList();
    }

    @Override
    public boolean isParallelCapable() {
        // Nothing is computed and nothing is shared, so there is no reason to make the server
        // generate these chunks one at a time.
        return true;
    }

    @Override
    public int getBaseHeight(final WorldInfo world, final Random random, final int x, final int z,
                             final org.bukkit.HeightMap heightMap) {
        return world.getMinHeight();
    }
}
