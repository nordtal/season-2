package eu.nordtal.season.limbo.world;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * A chunk generator for empty chunks. Every {@code shouldGenerate*} method returns {@code false}.
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
        return Collections.emptyList();
    }

    @Override
    public boolean isParallelCapable() {
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
