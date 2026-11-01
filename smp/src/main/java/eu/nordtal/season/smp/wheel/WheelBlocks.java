package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelBlockSpec;
import java.util.List;

/** Whether a clicked block is one of the wheel's blocks. */
final class WheelBlocks {

    private WheelBlocks() {}

    /** Returns whether {@code blocks} names the block at {@code x}, {@code y}, {@code z} in {@code world}. */
    static boolean holds(final List<WheelBlockSpec> blocks, final String world, final int x, final int y, final int z) {
        for (final WheelBlockSpec block : blocks) {
            if (block.world().equals(world) && block.x() == x && block.y() == y && block.z() == z) {
                return true;
            }
        }
        return false;
    }
}
