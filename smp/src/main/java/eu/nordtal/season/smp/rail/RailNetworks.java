package eu.nordtal.season.smp.rail;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Tracks the rail networks of every world and keeps the store in step.
 * It walks the rails again after a tracked rail changes, a little every tick.
 * Only rails joined to a station rail are ever walked.
 */
public final class RailNetworks {

    /** The most rails one network holds; a walk stops growing a network beyond it. */
    static final int RAILS_PER_NETWORK = 50_000;

    /** The most rails a walk reads in one tick. */
    static final int RAILS_PER_TICK = 2_000;

    private record Running(String world, WorldNetworks.Walk walk, RailWalk rails, BukkitRails view) {}

    private final Plugin plugin;
    private final RailStore store;
    private final Clock clock;
    private final StationMarks marks;
    private final StationRailItem item;
    private final NamespacedKey displayKey;
    private final Map<String, WorldNetworks> worlds = new HashMap<>();
    private final Map<String, Set<RailPos>> changed = new LinkedHashMap<>();
    private final Map<UUID, StationRow> crooked = new LinkedHashMap<>();
    private @Nullable Running running;
    private boolean restored;

    public RailNetworks(final Plugin plugin, final RailStore store, final Clock clock) {
        this.plugin = plugin;
        this.store = store;
        this.clock = clock;
        this.marks = new StationMarks(plugin);
        this.item = new StationRailItem(plugin);
        this.displayKey = new NamespacedKey(plugin, "station_rail");
    }

    StationRailItem item() {
        return item;
    }

    /** Adds the recipe and reads the stored networks off the main thread. Changes wait until they are back. */
    public void start(final Executor main) {
        Bukkit.addRecipe(item.recipe());
        store.load(
                stored -> {
                    stored.forEach((world, part) -> {
                        networks(world).restore(part.stations(), part.junctions(), part.edges());
                        // The store keeps no track that ends nowhere, so each network is walked again once.
                        part.stations()
                                .forEach(station -> changed.computeIfAbsent(world, _ -> new LinkedHashSet<>())
                                        .add(station.pos()));
                    });
                    restored = true;
                    plugin.getLogger()
                            .info("restored "
                                    + worlds.values().stream()
                                            .mapToInt(w -> w.stations().size())
                                            .sum() + " station rail(s)");
                },
                main);
    }

    public void stop() {
        final Running walk = running;
        if (walk != null) {
            walk.view().release();
            running = null;
        }
        Bukkit.removeRecipe(item.recipeKey());
    }

    boolean restored() {
        return restored;
    }

    private WorldNetworks networks(final String world) {
        return worlds.computeIfAbsent(world, _ -> new WorldNetworks());
    }

    static RailPos pos(final Block block) {
        return new RailPos(block.getX(), block.getY(), block.getZ());
    }

    /** Whether {@code block} is a station rail, by its marker, which holds before the store answers. */
    boolean isStation(final Block block) {
        return marks.marked(block);
    }

    /** Returns the station at {@code block}, once the store answered. */
    Optional<StationRow> station(final Block block) {
        return networks(block.getWorld().getName()).station(pos(block));
    }

    /** Notes that the rail at {@code block} may have changed; the next tick walks the networks near it. */
    void changed(final Block block) {
        final String world = block.getWorld().getName();
        changed.computeIfAbsent(world, _ -> new LinkedHashSet<>()).add(pos(block));
        // Vanilla turns the rails next to a changed one after the event, so the stations are straightened a tick on.
        networks(world).stationsNear(pos(block)).forEach(station -> crooked.put(station.id(), station));
    }

    /** Makes {@code block}, a placed rail, a station rail of {@code placer}, straight along {@code axis}. */
    void place(final Block block, final UUID placer, final RailShape axis) {
        straighten(block, axis);
        final StationRow station = new StationRow(
                UUID.randomUUID(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                axis.name(),
                placer,
                null,
                null);
        marks.mark(block);
        networks(station.world()).addStation(station);
        draw(block, axis);
        store.placed(station);
        changed(block);
    }

    /** Removes the station rail at {@code block}: its marker, its model and its row. */
    void remove(final Block block) {
        marks.unmark(block);
        erase(block);
        networks(block.getWorld().getName()).removeStation(pos(block)).ifPresent(row -> store.removed(row.id()));
        changed(block);
    }

    private static void straighten(final Block block, final RailShape axis) {
        if (block.getBlockData() instanceof Rail rail && !rail.getShape().name().equals(axis.name())) {
            rail.setShape(Rail.Shape.valueOf(axis.name()));
            block.setBlockData(rail, false);
        }
    }

    private void draw(final Block block, final RailShape axis) {
        final Location at = block.getLocation().add(0.5, 0.5, 0.5);
        at.setYaw(axis == RailShape.EAST_WEST ? 90f : 0f);
        block.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(item.create());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
        });
    }

    private void erase(final Block block) {
        block
                .getWorld()
                .getNearbyEntitiesByType(ItemDisplay.class, block.getLocation().add(0.5, 0.5, 0.5), 0.4)
                .stream()
                .filter(display -> display.getPersistentDataContainer().has(displayKey, PersistentDataType.BOOLEAN))
                .forEach(ItemDisplay::remove);
    }

    /** Runs every tick: starts the next walk, or reads on in the running one. */
    public void tick() {
        straightenCrooked();
        if (!restored) {
            return;
        }
        if (running == null) {
            begin();
        }
        final Running walk = running;
        if (walk == null) {
            return;
        }
        walk.view().load(walk.rails().takeWanted());
        if (walk.rails().step(RAILS_PER_TICK)) {
            finish(walk);
        }
    }

    private void straightenCrooked() {
        for (final StationRow station : crooked.values()) {
            final @Nullable World world = Bukkit.getWorld(station.world());
            if (world != null
                    && world.isChunkLoaded(station.x() >> 4, station.z() >> 4)
                    && isStation(world.getBlockAt(station.x(), station.y(), station.z()))) {
                straighten(world.getBlockAt(station.x(), station.y(), station.z()), RailShape.valueOf(station.axis()));
            }
        }
        crooked.clear();
    }

    private void begin() {
        final Iterator<Map.Entry<String, Set<RailPos>>> pending =
                changed.entrySet().iterator();
        while (pending.hasNext()) {
            final Map.Entry<String, Set<RailPos>> next = pending.next();
            pending.remove();
            final World world = Bukkit.getWorld(next.getKey());
            if (world == null) {
                continue;
            }
            final WorldNetworks networks = networks(next.getKey());
            final WorldNetworks.Walk walk = networks.walkFor(next.getValue());
            if (walk.starts().isEmpty() && walk.replaced().isEmpty()) {
                continue;
            }
            final BukkitRails view = new BukkitRails(plugin, world);
            running = new Running(next.getKey(), walk, new RailWalk(view, walk.starts(), RAILS_PER_NETWORK), view);
            return;
        }
    }

    private void finish(final Running walk) {
        running = null;
        walk.view().release();
        final WorldNetworks networks = networks(walk.world());
        final WorldNetworks.Replacement replacement =
                networks.replace(walk.walk(), walk.rails().found(), walk.rails().missing(), clock.instant());
        final World world = Bukkit.getWorld(walk.world());
        if (world != null) {
            for (final StationRow lost : replacement.lost()) {
                final Block block = world.getBlockAt(lost.x(), lost.y(), lost.z());
                marks.unmark(block);
                erase(block);
            }
        }
        store.replaced(walk.world(), replacement);
        if (walk.rails().found().stream().anyMatch(RailWalk.Found::bounded)) {
            plugin.getLogger()
                    .warning("a rail network in " + walk.world() + " holds more than " + RAILS_PER_NETWORK
                            + " rails and was walked only that far");
        }
    }
}
