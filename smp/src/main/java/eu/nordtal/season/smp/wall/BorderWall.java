package eu.nordtal.season.smp.wall;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.smp.config.CustomBorderSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The custom border: shows each player the wall near them, by packet, and pushes back whoever walks through it.
 * Runs on the main thread.
 */
public final class BorderWall implements Listener {

    public static final DocumentSpec<DocumentSpec.None> LIMIT = DocumentSpec.game(
                    "smp", "border-limit", "Border", DocumentSpec.Shape.ACTION_BAR)
            .titled("World border");
    private final Predicate<Player> admin;
    private final Map<UUID, Location> lastInside = new HashMap<>();
    private final Map<UUID, Location> mounts = new HashMap<>();
    private final Map<UUID, Long> outside = new HashMap<>();

    /** Since when each player whom the last save left outside drifts back in. */
    private final Map<UUID, Long> drifting = new HashMap<>();

    private final Map<UUID, Long> bounced = new HashMap<>();
    private final Set<EnderPearl> pearls = new HashSet<>();

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

    /** The world whose regions open with milestones. */
    private final String overworld;

    /** How far a front travels, in blocks per second. */
    private final DoubleSupplier speed;

    private @Nullable CustomBorderSpec spec;

    /** The overworld's regions that are fully playable. */
    private Set<String> open = Set.of();

    /** The overworld's openings whose front still spreads, in their order. */
    private List<Front.Stage> stages = List.of();

    /** Whether the initial open regions have been loaded. */
    private boolean loaded;

    /** When the walls were last read, in milliseconds. */
    private long readAt;

    public BorderWall(
            final Clock clock,
            final Consumer<Runnable> nextTick,
            final Consumer<Player> pushSound,
            final Predicate<Player> admin,
            final String overworld,
            final DoubleSupplier speed) {
        this.admin = admin;
        this.clock = clock;
        this.nextTick = nextTick;
        this.pushSound = pushSound;
        this.overworld = overworld;
        this.speed = speed;
    }

    /**
     * Takes the settings and draws the wall again for every player.
     * A player the new wall leaves outside drifts slowly back in instead of being teleported.
     */
    public void apply(final CustomBorderSpec spec, final Collection<? extends Player> online) {
        this.spec = spec;
        redraw(online);
    }

    /**
     * Synchronises the overworld's open regions with the track.
     * Sets at once on startup or reset, and opens newly added regions through a moving front after startup.
     */
    public void regions(final Set<String> regions, final Collection<? extends Player> online) {
        final Set<String> known = new HashSet<>(open);
        stages.forEach(stage -> known.addAll(stage.regions()));
        if (!loaded || !regions.containsAll(known)) {
            loaded = true;
            if (regions.equals(open) && stages.isEmpty()) {
                return;
            }
            open = Set.copyOf(regions);
            stages = List.of();
            redraw(online);
            return;
        }
        final Set<String> fresh = new HashSet<>(regions);
        fresh.removeAll(known);
        if (!fresh.isEmpty()) {
            open(fresh, online);
        }
    }

    /** Opens {@code regions} of the overworld, which a front from the area playable now reaches at the set speed. */
    public void open(final Collection<String> regions, final Collection<? extends Player> online) {
        loaded = true;
        final Set<String> fresh = new HashSet<>(regions);
        fresh.removeAll(open);
        stages.forEach(stage -> fresh.removeAll(stage.regions()));
        if (fresh.isEmpty()) {
            return;
        }
        final List<Front.Stage> more = new ArrayList<>(stages);
        more.add(new Front.Stage(fresh, clock.millis()));
        stages = List.copyOf(more);
        redraw(online);
    }

    @Nullable
    Wall wall(final String world) {
        final Walls.Drawn drawn = walls.get(world);
        return drawn == null ? null : drawn.wall();
    }

    boolean spreading() {
        return !stages.isEmpty();
    }

    /**
     * Reads the walls from the settings, the open regions and the fronts.
     * The regions of a stage the front fully reached open in full once every stage before it has too, so a later
     * stage never changes where an earlier one spreads from.
     */
    private void read() {
        readAt = clock.millis();
        if (spec == null) {
            walls = Map.of();
            return;
        }
        applyRead(Walls.read(spec, overworld, open, stages, readAt, speed.getAsDouble()));
    }

    private void readOverworld() {
        readAt = clock.millis();
        if (spec == null) {
            walls = Map.of();
            return;
        }
        applyRead(Walls.readOverworld(spec, overworld, walls, open, stages, readAt, speed.getAsDouble()));
    }

    private void applyRead(final Walls.Read read) {
        walls = read.walls();
        if (!read.overworldEvaluated()) {
            return;
        }
        final Set<String> settled = new HashSet<>(open);
        final List<Front.Stage> left = new ArrayList<>();
        for (final Front.Stage stage : stages) {
            if (left.isEmpty() && !read.spreading().contains(stage)) {
                settled.addAll(stage.regions());
            } else {
                left.add(stage);
            }
        }
        open = Set.copyOf(settled);
        stages = List.copyOf(left);
    }

    /** Moves each front on and sends every player the wall that changed, once a front has travelled a block. */
    private void spread(final Collection<? extends Player> online) {
        // shortcut: rebuilds the wall on the main thread; move off-thread once a step shows in tick time.
        final long step = Math.max(250, Math.round(1000 / Math.max(0.0001, speed.getAsDouble())));
        if (stages.isEmpty() || clock.millis() - readAt < step) {
            return;
        }
        readOverworld();
        grounds.clear(overworld);
        for (final Player player : online) {
            final Location at = Objects.requireNonNull(player.getLocation());
            if (at.getWorld().getName().equals(overworld)) {
                show(player, at);
            }
        }
    }

    private void redraw(final Collection<? extends Player> online) {
        read();
        pearls.clear();
        for (final String name : walls.keySet()) {
            final World world = Bukkit.getServer() == null ? null : Bukkit.getWorld(name);
            if (world != null) {
                pearls.addAll(world.getEntitiesByClass(EnderPearl.class));
            }
        }
        grounds.clear();
        mounts.clear();
        lastInside.clear();
        outside.clear();
        drifting.clear();
        for (final Player player : online) {
            clear(player);
            final Entity moving = Push.root(player);
            final Location at = Objects.requireNonNull(moving.getLocation());
            final Walls.Drawn drawn = walls.get(at.getWorld().getName());
            if (drawn != null && !bypass(player) && !drawn.wall().inside(at.getX(), at.getZ())) {
                drifting.put(player.getUniqueId(), clock.millis());
            }
            show(player, Objects.requireNonNull(player.getLocation()));
        }
    }

    /** Takes every panel away from every player. */
    public void stop(final Collection<? extends Player> online) {
        for (final Player player : online) {
            clear(player);
        }
        walls = Map.of();
    }

    /** Returns whether {@code block} lies outside the custom border for {@code player}. */
    public boolean blocksEdit(final Player player, final Block block) {
        final Walls.Drawn drawn = walls.get(block.getWorld().getName());
        return drawn != null && !bypass(player) && !drawn.wall().inside(block.getX() + 0.5, block.getZ() + 0.5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockPlaced(final BlockPlaceEvent event) {
        final Block block = event.getBlock();
        if (changed(block)) {
            redrawNear(block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockBroken(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (changed(block)) {
            // The broken block is still in the world while its event runs.
            final Location at = block.getLocation();
            nextTick.accept(() -> redrawNear(at));
        }
    }

    /** Draws the wall again for every player whose shown panels may stand on the ground at {@code at}. */
    private void redrawNear(final Location at) {
        final double reach = Band.DEPTH + Band.LEAD;
        for (final Player player : at.getWorld().getPlayers()) {
            final Location location = Objects.requireNonNull(player.getLocation());
            final double dx = location.getX() - at.getX();
            final double dz = location.getZ() - at.getZ();
            if (player.isOnline() && viewers.containsKey(player.getUniqueId()) && dx * dx + dz * dz <= reach * reach) {
                show(player, location);
            }
        }
    }

    /** Forgets the grounds near a changed block beside the wall, and tells whether there were any. */
    private boolean changed(final Block block) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void moved(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        if (!event.hasChangedPosition()) {
            return;
        }
        final Player player = event.getPlayer();
        if (!to.getWorld().equals(event.getFrom().getWorld())) {
            // The world change event draws the wall of the new world.
            return;
        }
        if (event.hasChangedBlock()) {
            show(player, to);
        }
    }

    private boolean bypass(final Player player) {
        return Push.bypass(player.getGameMode(), admin.test(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void crossing(final PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final Location from = event.getFrom();
        final Location to = event.getTo();
        final Walls.Drawn drawn = walls.get(to.getWorld().getName());
        if (drawn == null
                || !event.hasChangedPosition()
                || !from.getWorld().equals(to.getWorld())
                || bypass(player)
                || player.isInsideVehicle()) {
            return;
        }
        final Wall wall = drawn.wall();
        if (!wall.inside(from.getX(), from.getZ())) {
            return;
        }
        lastInside.put(player.getUniqueId(), from.clone());
        final double dx = to.getX() - from.getX();
        final double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return;
        }
        final boolean crossed = Double.isFinite(wall.exit(from.getX(), from.getZ(), to.getX(), to.getZ()));
        if (player.isGliding()) {
            final double[] bounce = Push.glide(wall, from.getX(), from.getZ(), dx, dz);
            if (crossed) {
                event.setCancelled(true);
            }
            if (bounce != null) {
                player.setVelocity(new Vector(bounce[0], player.getVelocity().getY(), bounce[1]));
                bounced.put(player.getUniqueId(), clock.millis());
                feedback(player);
            }
        } else if (crossed) {
            if (wall.inside(to.getX(), to.getZ())) {
                event.setCancelled(true);
            }
            final double speed = Math.hypot(dx, dz);
            player.setVelocity(new Vector(-dx / speed * Push.HORIZONTAL, Push.UP, -dz / speed * Push.HORIZONTAL));
            feedback(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void collision(final EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL
                && event.getEntity() instanceof Player player
                && clock.millis() - bounced.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2) < 250) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(final PlayerTeleportEvent event) {
        final Location to = event.getTo();
        final Walls.Drawn drawn = walls.get(to.getWorld().getName());
        if (drawn != null && rejectsTeleport(drawn.wall(), to.getX(), to.getZ(), bypass(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    static boolean rejectsTeleport(final Wall wall, final double x, final double z, final boolean bypass) {
        return !bypass && !wall.inside(x, z);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(final PlayerPortalEvent event) {
        target(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launched(final ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl
                && walls.containsKey(pearl.getWorld().getName())) {
            pearls.add(pearl);
            bounce(pearl);
        }
    }

    @EventHandler
    public void loaded(final EntitiesLoadEvent event) {
        if (active(event.getWorld().getName())) {
            for (final var entity : event.getEntities()) {
                if (entity instanceof EnderPearl pearl) {
                    pearls.add(pearl);
                }
            }
        }
    }

    private void bounce(final EnderPearl pearl) {
        final Location at = pearl.getLocation();
        final Walls.Drawn drawn = walls.get(pearl.getWorld().getName());
        if (drawn == null || (pearl.getShooter() instanceof Player player && bypass(player))) {
            return;
        }
        final Vector v = pearl.getVelocity();
        final double[] reflected = drawn.wall().reflect(at.getX(), at.getZ(), v.getX(), v.getZ());
        if (reflected != null) {
            pearl.setVelocity(new Vector(reflected[0], v.getY(), reflected[1]));
        }
    }

    public boolean active(final String world) {
        return walls.containsKey(world);
    }

    public void tick(final Collection<? extends Player> players) {
        spread(players);
        if (walls.isEmpty()) {
            return;
        }
        pearls.removeIf(pearl -> !pearl.isValid());
        pearls.forEach(this::bounce);
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            final Entity moving = Push.root(player);
            final Location at = Objects.requireNonNull(moving.getLocation());
            final Walls.Drawn drawn = walls.get(at.getWorld().getName());
            final UUID id = player.getUniqueId();
            if (drawn == null || bypass(player)) {
                lastInside.remove(id);
                outside.remove(id);
                drifting.remove(id);
                continue;
            }
            final boolean inside = drawn.wall().inside(at.getX(), at.getZ());
            if (inside) {
                drifting.remove(id);
            } else {
                final Long saved = drifting.get(id);
                if (saved != null && Push.drifts(clock.millis() - saved)) {
                    if (moving.equals(player) || seen.add(moving.getUniqueId())) {
                        drift(moving, moving.equals(player) && player.isGliding(), drawn.wall(), at);
                    }
                    continue;
                }
                drifting.remove(id);
            }
            if (!moving.equals(player)) {
                if (seen.add(moving.getUniqueId())) {
                    mounted(player, moving, at, drawn.wall());
                }
                continue;
            }
            if (inside) {
                outside.remove(id);
                continue;
            }
            final Location safe = lastInside.get(id);
            final long since = outside.computeIfAbsent(id, ignored -> clock.millis());
            if (safe == null
                    || !safe.getWorld().equals(at.getWorld())
                    || Push.fallback(drawn.wall().distance(at.getX(), at.getZ()), clock.millis() - since)) {
                player.teleport(
                        safe != null && safe.getWorld().equals(at.getWorld())
                                ? safe
                                : inside(at, drawn.wall().returnPoint(at.getX(), at.getZ())));
            } else {
                push(player, at);
            }
        }
        mounts.keySet().retainAll(seen);
    }

    private void mounted(final Player player, final Entity vehicle, final Location at, final Wall wall) {
        final UUID id = vehicle.getUniqueId();
        final Location last = mounts.get(id);
        final Location previous = last != null && last.getWorld().equals(at.getWorld()) ? last : null;
        final boolean onRail = vehicle instanceof Minecart
                && (Tag.RAILS.isTagged(at.getBlock().getType())
                        || Tag.RAILS.isTagged(
                                at.getBlock().getRelative(0, -1, 0).getType()));
        final Push.Mount response = Push.mount(
                wall,
                at.getX(),
                at.getZ(),
                previous == null ? 0 : at.getX() - previous.getX(),
                previous == null ? 0 : at.getZ() - previous.getZ(),
                vehicle.getWidth() / 2,
                vehicle.getVelocity().getY(),
                onRail);
        if (response != null && response.restore()) {
            final Location target = previous != null ? previous : inside(at, wall.returnPoint(at.getX(), at.getZ()));
            if (vehicle.teleport(target)) mounts.put(id, target);
        } else {
            mounts.put(id, at);
        }
        if (response != null) {
            vehicle.setVelocity(new Vector(response.x(), response.y(), response.z()));
            feedback(player);
        }
    }

    private static Location inside(final Location at, final Area.Point point) {
        final Location target = at.clone();
        target.setX(point.x());
        target.setZ(point.z());
        final BoundingBox body = new BoundingBox(
                point.x() - 0.3, target.getY(), point.z() - 0.3, point.x() + 0.3, target.getY() + 1.8, point.z() + 0.3);
        while (target.getY() < at.getWorld().getMaxHeight() && at.getWorld().hasCollisionsIn(body)) {
            target.add(0, 1, 0);
            body.shift(0, 1, 0);
        }
        return target;
    }

    /** A teleport has its own event, and the player stands at the target only after it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        lastInside.remove(player.getUniqueId());
        outside.remove(player.getUniqueId());
        drifting.remove(player.getUniqueId());
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void joined(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        lastInside.remove(player.getUniqueId());
        outside.remove(player.getUniqueId());
        drifting.remove(player.getUniqueId());
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void changedWorld(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        clear(player);
        lastInside.remove(player.getUniqueId());
        outside.remove(player.getUniqueId());
        drifting.remove(player.getUniqueId());
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        // A respawn in the same world keeps the client's entities, so the old panels are removed.
        clear(player);
        lastInside.remove(player.getUniqueId());
        outside.remove(player.getUniqueId());
        drifting.remove(player.getUniqueId());
        nextTick.accept(() -> refresh(player));
    }

    @EventHandler
    public void quit(final PlayerQuitEvent event) {
        lastInside.remove(event.getPlayer().getUniqueId());
        outside.remove(event.getPlayer().getUniqueId());
        drifting.remove(event.getPlayer().getUniqueId());
        bounced.remove(event.getPlayer().getUniqueId());
        viewers.remove(event.getPlayer().getUniqueId());
        pushed.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(final Player player) {
        if (player.isOnline()) {
            final Location at = Objects.requireNonNull(player.getLocation());
            final Walls.Drawn drawn = walls.get(at.getWorld().getName());
            if (drawn != null && !bypass(player) && !drawn.wall().inside(at.getX(), at.getZ())) {
                player.teleport(inside(at, drawn.wall().returnPoint(at.getX(), at.getZ())));
            }
            show(player, Objects.requireNonNull(player.getLocation()));
        }
    }

    private void drift(final Entity moving, final boolean gliding, final Wall wall, final Location at) {
        final Vector current = moving.getVelocity();
        final double[] velocity = gliding
                ? Push.driftGlide(wall, at.getX(), at.getZ(), current.getX(), current.getZ())
                : Push.drift(wall, at.getX(), at.getZ());
        if (velocity != null) {
            moving.setVelocity(new Vector(velocity[0], current.getY(), velocity[1]));
        }
    }

    private void push(final Player player, final Location to) {
        final Walls.Drawn drawn = walls.get(to.getWorld().getName());
        if (drawn == null) {
            return;
        }
        final double[] velocity = Push.velocity(drawn.wall(), to.getX(), to.getZ());
        if (velocity == null) {
            return;
        }
        player.setVelocity(new Vector(velocity[0], velocity[1], velocity[2]));
        feedback(player);
    }

    private void feedback(final Player player) {
        final long now = clock.millis();
        if (!Push.due(pushed.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2), now)) {
            return;
        }
        pushed.put(player.getUniqueId(), now);
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
                drawn.wall(),
                look,
                at.getX(),
                at.getZ(),
                eye,
                panel -> grounds.at(at.getWorld(), panel, (int) Math.floor(eye), now));
        final Band.Change change = Band.change(viewer.seen(), seen);
        packets.remove(id, viewer.forget(change.removed()));
        change.remodelled().forEach((panel, after) -> packets.remodel(id, viewer.remodel(panel, after), after));
        if (look == Look.FIELD) {
            showField(id, viewer, change, Band.need(seen.values(), eye, look), at.getWorld());
        } else {
            showPixel(id, viewer, change, Band.base(at.getY()));
        }
    }

    /** Stretches the field's panels over the heights they must cover, and spawns the new ones. */
    private void showField(
            final UUID id, final Viewer viewer, final Band.Change change, final Band.Range need, final World world) {
        final Band.Range before = viewer.range();
        final Band.Range range = Band.fit(before == null ? need : before, need);
        if (!range.equals(before)) {
            viewer.reshape(range);
            viewer.shown().forEach((panel, shown) -> packets.reshape(id, shown.id(), shown.y(), range));
        }
        final double y = Band.anchor(world.getMaxHeight());
        final float viewRange = Band.viewRange(world.getMinHeight(), world.getMaxHeight());
        change.added().forEach((panel, seen) -> {
            final int entity = packets.newId();
            viewer.add(panel, entity, y, seen);
            packets.spawnField(id, entity, panel, y, viewRange, range, seen);
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
