package eu.nordtal.season.smp.wall;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Manages packet-based wall panels, ground heights and skyward stretches for online viewers. */
final class WallDisplay {

    private final WallPackets packets = new WallPackets();
    private final Grounds grounds = new Grounds();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Clock clock;
    private final Consumer<Runnable> nextTick;

    WallDisplay(final Clock clock, final Consumer<Runnable> nextTick) {
        this.clock = clock;
        this.nextTick = nextTick;
    }

    void clearGrounds() {
        grounds.clear();
    }

    void clearGrounds(final String world) {
        grounds.clear(world);
    }

    void removeViewer(final UUID id) {
        viewers.remove(id);
    }

    void clear(final Player player) {
        final Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer != null) {
            packets.remove(player.getUniqueId(), viewer.forget(viewer.shown().keySet()));
        }
    }

    /** Forgets the grounds near a changed block beside the wall, and tells whether there were any. */
    boolean changed(final Map<String, Walls.Drawn> walls, final Block block) {
        final Walls.Drawn drawn = walls.get(block.getWorld().getName());
        if (drawn == null
                || drawn.wall()
                        .near(block.getX() + 0.5, block.getZ() + 0.5, Grounds.INVALIDATE_RADIUS)
                        .isEmpty()) {
            return false;
        }
        grounds.invalidate(block.getWorld().getName(), block.getX(), block.getZ());
        return true;
    }

    /** Draws the wall again for every player whose shown panels may stand on the ground at {@code at}. */
    void redrawNear(final Map<String, Walls.Drawn> walls, final Location at) {
        final double reach = Band.reach(Look.FIELD) + Grounds.SPREAD;
        for (final Player player : at.getWorld().getPlayers()) {
            final Location location = Objects.requireNonNull(player.getLocation());
            final double dx = location.getX() - at.getX();
            final double dz = location.getZ() - at.getZ();
            if (player.isOnline() && viewers.containsKey(player.getUniqueId()) && dx * dx + dz * dz <= reach * reach) {
                show(walls, player, location);
            }
        }
    }

    /** Sends the player what changed in their part of the wall at {@code at}. */
    void show(final Map<String, Walls.Drawn> walls, final Player player, final Location at) {
        final UUID id = player.getUniqueId();
        final String world = at.getWorld().getName();
        final Walls.Drawn drawn = walls.get(world);
        final Viewer before = viewers.get(id);
        if (before != null && !before.world().equals(world)) {
            viewers.remove(id);
        }
        if (drawn == null) {
            if (before != null && before.world().equals(world)) {
                clear(player);
            }
            return;
        }
        final Viewer viewer = viewers.computeIfAbsent(id, ignored -> new Viewer(world));
        final Look look = drawn.look();
        final int eye = (int) Math.floor(at.getY() + player.getEyeHeight());
        final long now = clock.millis();
        final Map<Panel, Band.Seen> seen = Band.seen(
                drawn.wall(),
                look,
                at.getX(),
                at.getZ(),
                panel -> grounds.at(at.getWorld(), drawn.wall(), panel, eye, now));
        final Band.Change change = Band.change(viewer.seen(), seen);
        packets.remove(id, viewer.forget(change.removed()));
        change.remodelled().forEach((panel, after) -> packets.remodel(id, viewer.remodel(panel, after), after));
        if (look == Look.FIELD) {
            showField(id, viewer, change, at.getWorld());
        } else {
            showPixel(id, viewer, change, Band.base(at.getY()));
        }
    }

    /** Stretches the field's panels up to the build limit once the player flies, and back once they are down. */
    void stretch(final Map<String, Walls.Drawn> walls, final Player player) {
        final Viewer viewer = viewers.get(player.getUniqueId());
        final Walls.Drawn drawn = walls.get(player.getWorld().getName());
        if (viewer == null || drawn == null || drawn.look() != Look.FIELD) {
            return;
        }
        final World world = player.getWorld();
        final Entity moving = Push.root(player);
        final BoundingBox body = moving.getBoundingBox();
        final boolean skyward = Band.skyward(
                viewer.skyward(),
                player.isFlying() || player.isGliding(),
                moving.isInWater() || moving.isInLava() || player.isClimbing(),
                free -> !world.hasCollisionsIn(new BoundingBox(
                        body.getMinX(),
                        body.getMinY() - free,
                        body.getMinZ(),
                        body.getMaxX(),
                        body.getMinY(),
                        body.getMaxZ())));
        if (skyward == viewer.skyward()) {
            return;
        }
        viewer.turn(skyward, clock.millis());
        settle(
                player.getUniqueId(),
                viewer,
                world,
                viewer.shown().values().stream().map(Viewer.Shown::id).toList());
    }

    /** Spawns the field's new panels as far stretched as the player's others are, and lets them finish with those. */
    private void showField(final UUID id, final Viewer viewer, final Band.Change change, final World world) {
        if (change.added().isEmpty()) {
            return;
        }
        final double y = Band.anchor(world.getMaxHeight());
        final float viewRange = Band.viewRange(world.getMinHeight(), world.getMaxHeight());
        final long now = clock.millis();
        final Band.Range range = Band.range(world.getMaxHeight(), viewer.stretched(now));
        final List<Integer> spawned = new ArrayList<>();
        change.added().forEach((panel, seen) -> {
            final int entity = packets.newId();
            viewer.add(panel, entity, y, seen);
            packets.spawnField(id, entity, panel, y, viewRange, range, seen);
            spawned.add(entity);
        });
        if (viewer.left(now) > 0) {
            settle(id, viewer, world, spawned);
        }
    }

    /** Sends the panels the stretch's target a tick later, as the client stretches only a panel it has ticked. */
    private void settle(final UUID id, final Viewer viewer, final World world, final Collection<Integer> panels) {
        nextTick.accept(() -> {
            if (viewers.get(id) == viewer) {
                final double y = Band.anchor(world.getMaxHeight());
                final Band.Range target = Band.range(world.getMaxHeight(), viewer.skyward() ? 1 : 0);
                final int ticks = viewer.left(clock.millis());
                panels.forEach(entity -> packets.stretch(id, entity, y, target, ticks));
            }
        });
    }

    /** Moves the pixel look's panels to the player's height, and spawns the new ones. */
    private void showPixel(final UUID id, final Viewer viewer, final Band.Change change, final double base) {
        if (viewer.base() != base) {
            viewer.lift(base);
            viewer.shown().forEach((panel, shown) -> packets.move(id, shown.id(), panel, base));
        }
        change.added().forEach((panel, seen) -> {
            final int entity = packets.newId();
            viewer.add(panel, entity, base, seen);
            packets.spawnPixel(id, entity, panel, base, seen);
        });
    }
}
