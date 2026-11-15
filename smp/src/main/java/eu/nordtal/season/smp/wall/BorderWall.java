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
import org.bukkit.Sound;
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
    private final Clock clock;

    /** Runs a task one tick later, after the client has cleared its entities on a respawn or a world change. */
    private final Consumer<Runnable> nextTick;

    private Map<String, Walls.Drawn> walls = Map.of();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, Long> pushed = new HashMap<>();

    public BorderWall(final Clock clock, final Consumer<Runnable> nextTick) {
        this.clock = clock;
        this.nextTick = nextTick;
    }

    /** Takes the settings and draws the wall again for every player. */
    public void apply(final CustomBorderSpec spec, final Collection<? extends Player> online) {
        walls = Walls.read(spec);
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
        viewers.remove(player.getUniqueId());
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        viewers.remove(player.getUniqueId());
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
        player.playSound(player, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 1f);
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
        final double base = Band.base(at.getY());
        final Map<Panel, String> seen = Band.seen(drawn.wall(), drawn.look(), at.getX(), at.getZ());
        final Band.Change change = Band.change(viewer.models(), seen);
        packets.remove(id, viewer.forget(change.removed()));
        change.remodelled().forEach((panel, model) -> packets.remodel(id, viewer.remodel(panel, model), model));
        if (viewer.base() != base) {
            viewer.lift(base);
            viewer.ids().forEach((panel, entity) -> packets.move(id, entity, panel, base));
        }
        change.added().forEach((panel, model) -> {
            final int entity = packets.newId();
            viewer.add(panel, entity, model);
            packets.spawn(id, entity, panel, base, model);
        });
    }

    private void clear(final Player player) {
        final Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer != null) {
            packets.remove(player.getUniqueId(), viewer.forget(viewer.ids().keySet()));
        }
    }
}
