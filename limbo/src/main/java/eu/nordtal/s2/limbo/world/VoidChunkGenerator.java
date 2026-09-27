package eu.nordtal.s2.limbo.world;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * A chunk generator that generates nothing at all.
 *
 * Every {@code shouldGenerate*} hook answers {@code false}, switching vanilla generation off.
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
        // The one thing shouldGenerate* does not cover; empty keeps a populator from planting a tree on no ground.
        return Collections.emptyList();
    }

    @Override
    public boolean isParallelCapable() {
        // Nothing is computed and nothing is shared, so chunks need not be generated one at a time.
        return true;
    }

    @Override
    public int getBaseHeight(
            final WorldInfo world,
            final Random random,
            final int x,
            final int z,
            final org.bukkit.HeightMap heightMap) {
        return world.getMinHeight();
    }
}
