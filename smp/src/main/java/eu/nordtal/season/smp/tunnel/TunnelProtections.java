package eu.nordtal.season.smp.tunnel;

import java.util.Collection;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Checks whether a player may tunnel through a set of blocks and plays the existing refusal feedback on hit.
 */
public interface TunnelProtections {

    /** Checks every block in {@code footprint}, playing refusal feedback on hit when {@code notifyPlayer} is true. */
    boolean check(Player player, World world, Collection<BlockPos> footprint, boolean notifyPlayer);
}
