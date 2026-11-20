package eu.nordtal.season.smp.wall;

import eu.nordtal.season.smp.config.CustomBorderSpec;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * The custom border: shows each player the wall near them, by packet, and pushes back whoever walks through it.
 * Runs on the main thread. The vanilla border stays as it is.
 */
public final class BorderWall implements Listener {

    private final WallPackets packets = new WallPackets();
    private final Grounds grounds = new Grounds();
    private final Clock clock;

    /** Runs a task one tick later, after the client has cleared its entities on a respawn or a world change. */
    private final Consumer<Runnable> nextTick;

    /** Plays the push back's sound to the player it pushed. */
    private final Consumer<Player> pushSound;

    private Map<String, Walls.Drawn> walls = Map.of();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, Long> pushed = new HashMap<>();

    public BorderWall(final Clock clock, final Consumer<Runnable> nextTick, final Consumer<Player> pushSound) {
        this.clock = clock;
        this.nextTick = nextTick;
        this.pushSound = pushSound;
    }

    /** Takes the settings and draws the wall again for every player. */
    public void apply(final CustomBorderSpec spec, final Collection<? extends Player> online) {
        walls = Walls.read(spec);
        grounds.clear();
        for (final Player player : online) {
            clear(player);
            refresh(player);
        }
    }

    /** Takes every panel away from every player. */
    public void stop(final Collection<? extends Player> online) {
        for (final Player player : online) {
            clear(player);
        }
        walls = Map.of();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void moved(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        if (!event.hasChangedBlock()) {
            return;
        }
        final Player player = event.getPlayer();
        if (!to.getWorld().equals(event.getFrom().getWorld())) {
            // The world change event draws the wall of the new world.
            return;
        }
        push(player, to);
        show(player, to);
    }

    /** A teleport has its own event, and the player stands at the target only after it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void joined(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void changedWorld(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        clear(player);
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        // A respawn in the same world keeps the client's entities, so the old panels are removed.
        clear(player);
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void quit(final PlayerQuitEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
        pushed.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(final Player player) {
        if (player.isOnline()) {
            show(player, Objects.requireNonNull(player.getLocation()));
        }
    }

    private void push(final Player player, final Location to) {
        final Walls.Drawn drawn = walls.get(to.getWorld().getName());
        if (drawn == null) {
            return;
        }
        final double[] velocity = Push.velocity(drawn.wall(), to.getX(), to.getZ());
        final long now = clock.millis();
        if (velocity == null || !Push.due(pushed.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2), now)) {
            return;
        }
        pushed.put(player.getUniqueId(), now);
        player.setVelocity(new Vector(velocity[0], velocity[1], velocity[2]));
        pushSound.accept(player);
    }

    /** Sends the player what changed in their part of the wall at {@code at}. */
    private void show(final Player player, final Location at) {
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
        final double eye = at.getY() + player.getEyeHeight();
        final long now = clock.millis();
        final Map<Panel, Band.Seen> seen = Band.seen(
                drawn.wall(), look, at.getX(), at.getZ(), eye, panel -> grounds.at(at.getWorld(), panel, now));
        final Band.Change change = Band.change(viewer.seen(), seen);
        packets.remove(id, viewer.forget(change.removed()));
        change.remodelled().forEach((panel, after) -> packets.remodel(id, viewer.remodel(panel, after), after));
        if (look == Look.FIELD) {
            showField(id, viewer, change, Band.need(seen.values(), eye, look), at.getY());
        } else {
            showPixel(id, viewer, change, Band.base(at.getY()));
        }
    }

    /** Stretches the field's panels over the heights they must cover, and spawns the new ones. */
    private void showField(
            final UUID id, final Viewer viewer, final Band.Change change, final Band.Range need, final double feetY) {
        final Band.Range before = viewer.range();
        final Band.Range range = Band.fit(before == null ? need : before, need);
        if (!range.equals(before)) {
            viewer.reshape(range);
            viewer.shown().forEach((panel, shown) -> packets.reshape(id, shown.id(), shown.y(), range));
        }
        change.added().forEach((panel, seen) -> {
            final int entity = packets.newId();
            // The entity stands on the ground, so the client counts its distance from there.
            final double y = seen.ground() == Band.UNKNOWN ? Math.floor(feetY) : seen.ground();
            viewer.add(panel, entity, y, seen);
            packets.spawnField(id, entity, panel, y, range, seen);
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

    private void clear(final Player player) {
        final Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer != null) {
            packets.remove(player.getUniqueId(), viewer.forget(viewer.shown().keySet()));
        }
    }
}
