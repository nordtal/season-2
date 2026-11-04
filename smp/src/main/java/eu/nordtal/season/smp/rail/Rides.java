package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.RailsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Drives minecarts with a player in them to the station they picked, moving each cart itself every tick.
 * It stops before a junction set the wrong way and switches it, holds the chunks ahead loaded, plans again when
 * the networks change and lets driven carts pass through everything. At the station it sets the rider down.
 */
public final class Rides implements Listener {

    /** How far before a gap or a chunk still loading a cart stops, in blocks. */
    private static final double MARGIN = 0.5;

    /** How many ticks a cart stopped before a gap waits for the networks to be walked again. */
    private static final int PATIENCE = 100;

    /** How many ticks a cart waits for a chunk ahead that does not load. */
    private static final int LOADING_PATIENCE = 600;

    /** How far beyond its stopping distance a cart reads the rails ahead and holds their chunks, in blocks. */
    private static final double LOOKOUT = 48;

    /** The vanilla top speed of a minecart, in blocks a tick, which a cart gets back when its ride ends. */
    private static final double VANILLA_MAX_SPEED = 0.4;

    private final PaperScheduler scheduler;
    private final RailNetworks networks;
    private final ChunkTickets tickets;
    private final Supplier<RailsSpec> settings;
    private final SmpSounds sounds;
    private final Map<UUID, Ride> rides = new HashMap<>();
    /** The cart that holds each junction it is about to pass or switch, until it has ridden over it. */
    private final Map<Claim, UUID> claims = new HashMap<>();

    private Consumer<Player> stranded = _ -> {};

    /** One cart on its way: the line it follows, how far along it is and how fast it goes. */
    private static final class Ride {

        private final RideableMinecart cart;
        private final Player rider;
        private final World world;
        private final Set<Long> held = new HashSet<>();
        private final Set<Long> loading = new HashSet<>();
        private final JunctionStops.Junctions junctions;
        private JunctionStops stops = new JunctionStops();
        private UUID destination;
        private @Nullable RidePath path;
        private Map<Integer, RailShape> switches = Map.of();
        private double along;
        private double speed;
        private double end;
        private int version;
        private boolean standingPlanDue;
        private int waited;

        Ride(
                final RideableMinecart cart,
                final Player rider,
                final UUID destination,
                final JunctionStops.Junctions junctions) {
            this.cart = cart;
            this.rider = rider;
            this.world = cart.getWorld();
            this.destination = destination;
            this.junctions = junctions;
        }
    }

    private record Claim(UUID world, RailPos rail) {}

    public Rides(
            final PaperScheduler scheduler,
            final RailNetworks networks,
            final ChunkTickets tickets,
            final Supplier<RailsSpec> settings,
            final SmpSounds sounds) {
        this.scheduler = scheduler;
        this.networks = networks;
        this.tickets = tickets;
        this.settings = settings;
        this.sounds = sounds;
    }

    private RidePath.Motion motion() {
        final RailsSpec now = settings.get();
        return RidePath.Motion.of(now.acceleration(), now.topSpeed(), now.curveSpeed());
    }

    /** Runs {@code told} for a rider whose cart stopped for good before reaching the station. */
    void whenStranded(final Consumer<Player> told) {
        this.stranded = told;
    }

    /** Drives {@code cart} with {@code rider} in it to the station {@code station}; false when no way leads there. */
    boolean drive(final Player rider, final RideableMinecart cart, final UUID station) {
        final @Nullable Ride running = rides.get(cart.getUniqueId());
        if (running != null) {
            final UUID before = running.destination;
            running.destination = station;
            if (plan(running, running.speed == 0)) {
                return true;
            }
            if (running.speed > 0
                    && Route.between(graphUnder(running), railUnder(cart), null, stationPos(running))
                            .isPresent()) {
                // The way there turns back, so the cart stops first and sets off again from where it stands.
                running.end = Math.min(running.end, running.along + motion().stopping(running.speed));
                running.standingPlanDue = true;
                return true;
            }
            running.destination = before;
            return false;
        }
        final Ride ride = new Ride(cart, rider, station, junctions(cart.getWorld(), cart.getUniqueId()));
        if (!plan(ride, true)) {
            return false;
        }
        rides.put(cart.getUniqueId(), ride);
        // The plugin moves the cart every tick, so vanilla gravity would only pull it off the line in between.
        cart.setGravity(false);
        // The cart keeps the velocity it rides at, for the client's riding sound; vanilla must not move it by that too.
        cart.setMaxSpeed(0);
        return true;
    }

    /** Whether {@code entity} is a cart that is being driven now. */
    boolean driven(final Entity entity) {
        return rides.containsKey(entity.getUniqueId());
    }

    private RailGraph graphUnder(final Ride ride) {
        return networks.graph(ride.world.getName(), railUnder(ride.cart))
                .orElseGet(() -> new RailGraph(List.of(), List.of(), List.of()));
    }

    private RailPos stationPos(final Ride ride) {
        return networks.station(ride.world.getName(), ride.destination)
                .map(StationRow::pos)
                .orElse(railUnder(ride.cart));
    }

    private static RailPos railUnder(final Entity cart) {
        final Block at = cart.getLocation().getBlock();
        return RailNetworks.pos(Tag.RAILS.isTagged(at.getType()) ? at : at.getRelative(BlockFace.DOWN));
    }

    /**
     * Plans the ride from where the cart is to its station, and returns whether a way leads there.
     *
     * @param standing whether the cart may set off either way; a moving cart keeps its direction
     */
    private boolean plan(final Ride ride, final boolean standing) {
        ride.version = networks.version(ride.world.getName());
        final @Nullable RidePath current = ride.path;
        final RailPos at;
        final @Nullable RailPos ahead;
        if (standing || current == null) {
            at = railUnder(ride.cart);
            ahead = null;
        } else {
            final int rail = current.railAt(ride.along);
            at = current.rails().get(rail);
            ahead = rail + 1 < current.rails().size() ? current.rails().get(rail + 1) : null;
        }
        final Optional<StationRow> station = networks.station(ride.world.getName(), ride.destination);
        final Optional<RailGraph> graph = networks.graph(ride.world.getName(), at);
        if (station.isEmpty() || graph.isEmpty()) {
            return false;
        }
        if (at.equals(station.get().pos()) && current != null && !standing) {
            return true;
        }
        final Optional<Route> route =
                Route.between(graph.get(), at, ahead, station.get().pos());
        if (route.isEmpty()) {
            return false;
        }
        final Location location = ride.cart.getLocation();
        ride.path = RidePath.of(
                route.get().rails(), new RidePath.Point(location.getX(), location.getY(), location.getZ()), motion());
        ride.switches = route.get().switches(graph.get().junctions());
        unclaim(ride);
        ride.stops = new JunctionStops();
        ride.along = 0;
        ride.end = ride.path.length();
        ride.standingPlanDue = false;
        ride.waited = 0;
        return true;
    }

    /** Runs every tick: moves every driven cart one tick along its line. */
    public void tick() {
        final RidePath.Motion now = motion();
        // An arrival or a rider getting out ends a ride while the others still move, so this walks a copy.
        for (final Ride ride : List.copyOf(rides.values())) {
            final UUID cart = ride.cart.getUniqueId();
            if (rides.get(cart) != ride) {
                continue;
            }
            final boolean riding =
                    ride.cart.isValid() && ride.rider.isOnline() && ride.cart.equals(ride.rider.getVehicle());
            if (!riding || !advance(ride, now)) {
                rides.remove(cart, ride);
                release(ride);
            }
        }
    }

    /** Moves {@code ride} one tick on, and returns false once it ended. */
    private boolean advance(final Ride ride, final RidePath.Motion now) {
        if (ride.version != networks.version(ride.world.getName()) && !plan(ride, ride.speed == 0)) {
            if (ride.speed == 0) {
                strand(ride);
                return false;
            }
            ride.end = Math.min(ride.end, ride.along + now.stopping(ride.speed));
            ride.standingPlanDue = true;
        }
        final RidePath path = ride.path;
        if (path == null) {
            return false;
        }
        final Lookout lookout = look(ride, path, now);
        final double reach = Math.min(lookout.clear(), ride.along + now.stopping(now.top()) + LOOKOUT);
        final double stop = ride.stops.limit(path, ride.switches, ride.along, ride.speed, reach, now, ride.junctions);
        final double limit = Math.max(ride.along, Math.min(stop, Math.min(ride.end, lookout.clear())));
        ride.speed = Math.min(path.next(ride.along, ride.speed, limit, now), limit - ride.along);
        ride.along += ride.speed;
        move(ride, path);
        if (ride.speed > 0 || ride.along < limit) {
            ride.waited = 0;
            return true;
        }
        if (ride.standingPlanDue && plan(ride, true)) {
            return true;
        }
        if (!ride.standingPlanDue && stop < Math.min(ride.end, lookout.clear())) {
            // The cart stands before a junction set the wrong way; one held too long strands it.
            if (++ride.waited > PATIENCE) {
                strand(ride);
                return false;
            }
            return true;
        }
        if (ride.end >= path.length() && lookout.clear() >= path.length()) {
            arrive(ride, path);
            return false;
        }
        if (ride.standingPlanDue) {
            strand(ride);
            return false;
        }
        if (lookout.clear() < ride.end && !lookout.gap()) {
            // A chunk ahead is still loading; one that never does strands the cart after a while.
            if (++ride.waited > LOADING_PATIENCE) {
                strand(ride);
                return false;
            }
            return true;
        }
        if (++ride.waited > PATIENCE) {
            strand(ride);
            return false;
        }
        return true;
    }

    /**
     * What the cart sees ahead.
     *
     * @param clear the distance up to which the rails ahead are there and loaded
     * @param gap   whether a rail is missing at that distance, rather than a chunk still loading
     */
    private record Lookout(double clear, boolean gap) {}

    /** Reads the rails ahead of the cart, holds their chunks and finds a gap. */
    private Lookout look(final Ride ride, final RidePath path, final RidePath.Motion now) {
        final double reach = ride.along + now.stopping(now.top()) + LOOKOUT;
        final Set<Long> wanted = new HashSet<>();
        double clear = path.length();
        boolean gap = false;
        final List<RailPos> rails = path.rails();
        final int on = path.railAt(ride.along);
        for (int index = on; index < rails.size() && path.start(index) <= reach; index++) {
            final RailPos rail = rails.get(index);
            final int chunkX = rail.x() >> 4;
            final int chunkZ = rail.z() >> 4;
            final long key = Chunk.getChunkKey(chunkX, chunkZ);
            if (!ride.world.isChunkLoaded(chunkX, chunkZ)) {
                load(ride, chunkX, chunkZ);
                clear = path.start(index) - MARGIN;
                break;
            }
            wanted.add(key);
            final Block block = ride.world.getBlockAt(rail.x(), rail.y(), rail.z());
            if (!(block.getBlockData() instanceof Rail)) {
                if (index == on) {
                    // The rail under the cart went just now; the cart rides off it to the next one.
                    continue;
                }
                ride.end = Math.min(ride.end, path.start(index) - MARGIN);
                clear = ride.end;
                gap = true;
                break;
            }
        }
        for (final long key : wanted) {
            if (ride.held.add(key)) {
                tickets.hold(ride.world, (int) key, (int) (key >> 32));
            }
        }
        for (final Iterator<Long> held = ride.held.iterator(); held.hasNext(); ) {
            final long key = held.next();
            if (!wanted.contains(key)) {
                held.remove();
                tickets.release(ride.world, key);
            }
        }
        return new Lookout(clear, gap);
    }

    /**
     * Returns the junctions of {@code world} as the cart {@code cart} reads and holds them.
     * One in a chunk not loaded, or one that cannot take the shape, counts as set right.
     */
    private JunctionStops.Junctions junctions(final World world, final UUID cart) {
        return new JunctionStops.Junctions() {
            @Override
            public boolean claim(final RailPos rail) {
                return claims.putIfAbsent(new Claim(world.getUID(), rail), cart) == null
                        || cart.equals(claims.get(new Claim(world.getUID(), rail)));
            }

            @Override
            public void release(final RailPos rail) {
                claims.remove(new Claim(world.getUID(), rail), cart);
            }

            @Override
            public boolean wrong(final RailPos rail, final RailShape wanted) {
                final Rail.Shape target = Rail.Shape.valueOf(wanted.name());
                return world.isChunkLoaded(rail.x() >> 4, rail.z() >> 4)
                        && world.getBlockAt(rail.x(), rail.y(), rail.z()).getBlockData() instanceof Rail shape
                        && shape.getShape() != target
                        && shape.getShapes().contains(target);
            }

            @Override
            public void set(final RailPos rail, final RailShape wanted) {
                final Block block = world.getBlockAt(rail.x(), rail.y(), rail.z());
                if (block.getBlockData() instanceof Rail shape) {
                    shape.setShape(Rail.Shape.valueOf(wanted.name()));
                    block.setBlockData(shape, false);
                    sounds.playAt(block.getLocation().toCenterLocation(), Feedback.SWITCH);
                }
            }
        };
    }

    private void load(final Ride ride, final int chunkX, final int chunkZ) {
        final long key = Chunk.getChunkKey(chunkX, chunkZ);
        if (ride.loading.add(key)) {
            final CompletableFuture<Chunk> _ =
                    ride.world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((_, _) -> ride.loading.remove(key));
        }
    }

    private static void move(final Ride ride, final RidePath path) {
        final RidePath.Point point = path.at(ride.along);
        final double[] direction = path.direction(ride.along);
        moveTo(
                ride.cart,
                new Location(
                        ride.world,
                        point.x(),
                        point.y(),
                        point.z(),
                        (float) Math.toDegrees(Math.atan2(-direction[1], -direction[0])),
                        0f));
    }

    /**
     * Teleports {@code cart} to {@code to} and gives it the step it made as its velocity.
     * A teleport leaves a cart without one, and without one the client plays no riding sound.
     */
    static void moveTo(final RideableMinecart cart, final Location to) {
        final Location from = cart.getLocation();
        cart.teleport(to);
        cart.setVelocity(new Vector(to.getX() - from.getX(), 0, to.getZ() - from.getZ()));
    }

    /** Leaves {@code cart} standing, with vanilla physics moving it again. */
    static void standStill(final RideableMinecart cart) {
        cart.setVelocity(new Vector());
        cart.setMaxSpeed(VANILLA_MAX_SPEED);
        cart.setGravity(true);
    }

    /** Sets the rider down on the station rail, facing the way they rode, and puts the cart away. */
    private void arrive(final Ride ride, final RidePath path) {
        final RailPos station = path.rails().getLast();
        final double[] direction = path.direction(path.length());
        final Location down = new Location(
                ride.world,
                station.x() + 0.5,
                station.y(),
                station.z() + 0.5,
                (float) Math.toDegrees(Math.atan2(-direction[0], direction[1])),
                ride.rider.getPitch());
        rides.remove(ride.cart.getUniqueId());
        release(ride);
        ride.cart.removePassenger(ride.rider);
        ride.rider.teleport(down);
        pocket(ride.cart, ride.rider);
    }

    private void strand(final Ride ride) {
        release(ride);
        final Player rider = ride.rider;
        // The menu opens a tick on, once the ride has ended.
        scheduler.onMainAfter(PaperScheduler.TICK, () -> {
            if (rider.isOnline()) {
                stranded.accept(rider);
            }
        });
    }

    private void unclaim(final Ride ride) {
        claims.values().removeIf(ride.cart.getUniqueId()::equals);
    }

    private void release(final Ride ride) {
        unclaim(ride);
        standStill(ride.cart);
        ride.held.forEach(key -> tickets.release(ride.world, key));
        ride.held.clear();
    }

    /** Puts the cart into the rider's inventory when there is room, or removes it in creative mode. */
    private static void pocket(final RideableMinecart cart, final Player rider) {
        if (rider.getGameMode() == GameMode.CREATIVE) {
            cart.remove();
            return;
        }
        final ItemStack item = cart.getPickItemStack();
        if (rider.getInventory().addItem(item).isEmpty()) {
            cart.remove();
        }
    }

    /** A rider who gets out on the way stays on the track, and the cart goes into their inventory. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExit(final VehicleExitEvent event) {
        final @Nullable Ride ride = rides.remove(event.getVehicle().getUniqueId());
        if (ride == null) {
            return;
        }
        release(ride);
        if (!(event.getExited() instanceof Player rider)) {
            return;
        }
        // The rider leaves the cart only after the event.
        scheduler.onMainAfter(PaperScheduler.TICK, () -> {
            if (!rider.isOnline() || rider.isDead() || rider.isInsideVehicle() || !ride.cart.isValid()) {
                return;
            }
            final Location track = ride.cart.getLocation();
            track.setYaw(rider.getYaw());
            track.setPitch(rider.getPitch());
            rider.teleport(track);
            pocket(ride.cart, rider);
        });
    }

    /** A driven cart passes through other carts, mobs and players, and neither pushes nor is pushed. */
    @EventHandler(ignoreCancelled = true)
    public void onCollision(final VehicleEntityCollisionEvent event) {
        if (driven(event.getVehicle()) || driven(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final VehicleDamageEvent event) {
        if (driven(event.getVehicle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(final VehicleDestroyEvent event) {
        if (driven(event.getVehicle())) {
            event.setCancelled(true);
        }
    }

    /** Ends every ride where its cart is and lets go of every chunk the rides held. */
    public void stop() {
        final List<Ride> running = new ArrayList<>(rides.values());
        rides.clear();
        running.forEach(this::release);
    }
}
