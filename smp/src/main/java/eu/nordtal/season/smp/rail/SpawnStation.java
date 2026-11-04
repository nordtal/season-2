package eu.nordtal.season.smp.rail;

import eu.nordtal.season.smp.config.StationBlockSpec;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the spawn station at the block the settings name, and moves or removes it when the setting changes.
 * It puts it back when it is gone. A step waits until its chunk and the chunk's entities are loaded, holding the
 * chunk loaded meanwhile, so the station's model goes with it.
 */
public final class SpawnStation {

    /** The block the settings put the spawn station on. */
    public record Target(String world, RailPos pos) {}

    /** The next thing to do for the spawn station. */
    sealed interface Step {

        /** Removes a spawn station that no longer stands where the settings put it. */
        record Remove(StationRow station) implements Step {}

        /** Places the spawn station at {@code at}, in place of whatever stands there. */
        record Place(Target at) implements Step {}
    }

    private record Held(World world, long chunk) {}

    private final RailNetworks networks;
    private final ChunkTickets tickets;
    private final Supplier<Optional<Target>> target;
    private @Nullable Held held;

    public SpawnStation(
            final RailNetworks networks, final ChunkTickets tickets, final Supplier<Optional<Target>> target) {
        this.networks = networks;
        this.tickets = tickets;
        this.target = target;
    }

    /**
     * Returns the next step: a spawn station away from the target goes first.
     * Then the target gets one when it has none or when {@code broken} says the one there lost its rail.
     */
    static Optional<Step> next(
            final Optional<Target> want, final Collection<StationRow> spawns, final Predicate<Target> broken) {
        for (final StationRow spawn : spawns) {
            if (want.isEmpty() || !at(spawn, want.get())) {
                return Optional.of(new Step.Remove(spawn));
            }
        }
        return want.filter(at -> spawns.isEmpty() || broken.test(at)).map(Step.Place::new);
    }

    private static boolean at(final StationRow station, final Target target) {
        return station.world().equals(target.world()) && station.pos().equals(target.pos());
    }

    /** Runs once a second on the main thread, once the networks are restored. */
    public void tick() {
        if (!networks.restored()) {
            return;
        }
        next(target.get(), networks.spawns(), this::broken).ifPresentOrElse(this::take, this::letGo);
    }

    /** Whether the rail of the spawn station at {@code at} is gone; a chunk that is not loaded is not looked at. */
    private boolean broken(final Target at) {
        final @Nullable World world = Bukkit.getWorld(at.world());
        if (world == null || !world.isChunkLoaded(at.pos().x() >> 4, at.pos().z() >> 4)) {
            return false;
        }
        final Block block =
                world.getBlockAt(at.pos().x(), at.pos().y(), at.pos().z());
        return block.getType() != Material.RAIL || !networks.isStation(block);
    }

    private void take(final Step step) {
        final RailPos pos = switch (step) {
            case Step.Remove remove -> remove.station().pos();
            case Step.Place place -> place.at().pos();
        };
        final String name = switch (step) {
            case Step.Remove remove -> remove.station().world();
            case Step.Place place -> place.at().world();
        };
        final @Nullable World world = Bukkit.getWorld(name);
        if (world == null) {
            letGo();
            return;
        }
        final int chunkX = pos.x() >> 4;
        final int chunkZ = pos.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)
                || !world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded()) {
            hold(world, chunkX, chunkZ);
            return;
        }
        final Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        switch (step) {
            case Step.Remove _ -> {
                networks.remove(block);
                if (Tag.RAILS.isTagged(block.getType())) {
                    block.setType(Material.AIR, false);
                }
            }
            case Step.Place _ -> {
                if (block.getType() != Material.RAIL) {
                    block.setType(Material.RAIL, false);
                }
                networks.place(
                        block, null, StationAxis.of(pos, at -> StationRailListener.shapeAt(block, at), Heading.NORTH));
            }
        }
        letGo();
    }

    private void hold(final World world, final int chunkX, final int chunkZ) {
        final Held wanted = new Held(world, Chunk.getChunkKey(chunkX, chunkZ));
        if (wanted.equals(held)) {
            return;
        }
        letGo();
        tickets.hold(world, chunkX, chunkZ);
        held = wanted;
    }

    /** Lets go of the chunk a step held loaded, if any. */
    public void letGo() {
        final @Nullable Held holding = held;
        if (holding != null) {
            tickets.release(holding.world(), holding.chunk());
            held = null;
        }
    }

    /** Returns the block of the settings' spawn station entry, when there is one. */
    public static Optional<Target> target(final List<StationBlockSpec> entries) {
        return entries.stream()
                .findFirst()
                .map(entry -> new Target(entry.world(), new RailPos(entry.x(), entry.y(), entry.z())));
    }
}
