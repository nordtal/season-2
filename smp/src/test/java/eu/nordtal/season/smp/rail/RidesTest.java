package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.RailsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rides on a world of rails in memory, tick by tick, with the networks as the block changes leave them. */
class RidesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String WORLD = "world";

    private final Track track = new Track();
    private final WorldNetworks held = new WorldNetworks();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Player> stranded = new ArrayList<>();
    private final Map<Long, Integer> tickets = new HashMap<>();
    private IntPredicate chunkZLoaded = _ -> true;
    private final Plugin plugin = fake(Plugin.class, (_, method, _) -> switch (method.getName()) {
        case "getName", "namespace" -> "smp";
        default -> throw new UnsupportedOperationException("Plugin." + method.getName());
    });
    private final World world = fake(World.class, (_, method, args) -> switch (method.getName()) {
        case "getName" -> WORLD;
        case "getUID" -> new UUID(9, 9);
        case "isChunkLoaded" -> chunkZLoaded.test((int) args[1]);
        case "getBlockAt" ->
            args.length == 1 ? block((Location) args[0]) : block((int) args[0], (int) args[1], (int) args[2]);
        case "getChunkAtAsync" -> new CompletableFuture<>();
        case "addPluginChunkTicket" -> {
            tickets.merge(chunk((int) args[0], (int) args[1]), 1, Integer::sum);
            yield true;
        }
        case "removePluginChunkTicket" -> {
            tickets.merge(chunk((int) args[0], (int) args[1]), -1, Integer::sum);
            yield true;
        }
        default -> throw new UnsupportedOperationException("World." + method.getName());
    });
    private @Nullable Object server;
    private RailNetworks networks;
    private Rides rides;

    /** A cart with its rider, as the fakes keep them. */
    private final class Rider {
        private final UUID id = UUID.randomUUID();
        private Location at;
        private @Nullable RideableMinecart vehicle;
        private boolean valid = true;
        private boolean gravity;
        private final List<Location> setDown = new ArrayList<>();
        private final RideableMinecart cart =
                fake(RideableMinecart.class, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getLocation" -> at.clone();
                    case "teleport" -> {
                        at = ((Location) args[0]).clone();
                        yield true;
                    }
                    case "isValid" -> valid;
                    case "isEmpty" -> vehicle == null;
                    case "setVelocity", "setMaxSpeed" -> null;
                    case "setGravity" -> {
                        gravity = (boolean) args[0];
                        yield null;
                    }
                    case "removePassenger" -> {
                        vehicle = null;
                        yield true;
                    }
                    case "remove" -> {
                        valid = false;
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException("Cart." + method.getName());
                });
        private final Player player = fake(Player.class, (_, method, args) -> switch (method.getName()) {
            case "isOnline" -> true;
            case "isDead" -> false;
            case "getVehicle" -> vehicle;
            case "isInsideVehicle" -> vehicle != null;
            case "getPitch", "getYaw" -> 0f;
            case "getGameMode" -> GameMode.CREATIVE;
            case "teleport" -> setDown.add(((Location) args[0]).clone());
            default -> throw new UnsupportedOperationException("Player." + method.getName());
        });

        Rider(final RailPos on) {
            at = new Location(world, on.x() + 0.5, on.y(), on.z() + 0.5);
            vehicle = cart;
        }

        double x() {
            return at.getX();
        }
    }

    @BeforeEach
    void serverRunsTasksOnTheNextTick() throws ReflectiveOperationException {
        final BukkitTask task = fake(BukkitTask.class, (_, _, _) -> null);
        final BukkitScheduler scheduler = fake(BukkitScheduler.class, (_, method, args) -> switch (method.getName()) {
            case "runTaskLater" -> {
                later.add((Runnable) args[1]);
                yield task;
            }
            default -> throw new UnsupportedOperationException("Scheduler." + method.getName());
        });
        final Server fakeServer = fake(Server.class, (_, method, _) -> switch (method.getName()) {
            case "getScheduler" -> scheduler;
            default -> throw new UnsupportedOperationException("Server." + method.getName());
        });
        server = bukkitServer().get(null);
        bukkitServer().set(null, fakeServer);
        networks = new RailNetworks(
                plugin,
                new ChunkTickets(plugin),
                new RailStore(
                        Jdbi.create("jdbc:postgresql://localhost:1/none"), Runnable::run, Logger.getAnonymousLogger()),
                CLOCK);
        rides = new Rides(
                PaperScheduler.of(plugin),
                networks,
                new ChunkTickets(plugin),
                () -> spec,
                new SmpSounds(FeedbackSounds.silent(), _ -> {}));
        rides.whenStranded(stranded::add);
    }

    @AfterEach
    void serverBack() throws ReflectiveOperationException {
        bukkitServer().set(null, server);
    }

    private static Field bukkitServer() throws NoSuchFieldException {
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("ReferenceEquality") // a fake is equal to itself only
    private static <T> T fake(final Class<T> type, final InvocationHandler answers) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> answers.invoke(proxy, method, args);
                }));
    }

    private static long chunk(final int chunkX, final int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    private Block block(final Location location) {
        return block(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private Block block(final int x, final int y, final int z) {
        return fake(Block.class, (_, method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, x, y, z);
            case "getRelative" -> {
                final BlockFace face = (BlockFace) args[0];
                yield block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
            }
            case "getBlockData" -> data(track.rails().get(at(x, y, z)));
            case "setBlockData" -> {
                track.rail(
                        x, y, z, RailShape.valueOf(((Rail) args[0]).getShape().name()));
                yield null;
            }
            default -> throw new UnsupportedOperationException("Block." + method.getName());
        });
    }

    private static BlockData data(final @Nullable RailShape shape) {
        if (shape == null) {
            return fake(BlockData.class, (_, method, _) -> {
                throw new UnsupportedOperationException("BlockData." + method.getName());
            });
        }
        final Rail.Shape[] now = {Rail.Shape.valueOf(shape.name())};
        return fake(Rail.class, (_, method, args) -> switch (method.getName()) {
            case "getShape" -> now[0];
            case "setShape" -> {
                now[0] = (Rail.Shape) args[0];
                yield null;
            }
            case "getShapes" -> EnumSet.allOf(Rail.Shape.class);
            default -> throw new UnsupportedOperationException("Rail." + method.getName());
        });
    }

    /** Adds the stations at {@code positions}, numbered from 1, and reads the networks as the world holds them. */
    private void stations(final RailPos... positions) {
        for (int index = 0; index < positions.length; index++) {
            final RailPos pos = positions[index];
            held.addStation(new StationRow(
                    station(index + 1),
                    WORLD,
                    pos.x(),
                    pos.y(),
                    pos.z(),
                    track.rails().get(pos).name(),
                    new UUID(1, index),
                    null,
                    null));
        }
        changed(positions);
        networks.install(WORLD, held);
    }

    private static UUID station(final int number) {
        return new UUID(0, number);
    }

    /** Re-checks the networks around {@code positions}, as a block change there does. */
    private void changed(final RailPos... positions) {
        final @Nullable RailRecheck job = held.recheck(List.of(positions), track, CLOCK);
        assertNotNull(job);
        while (!job.step(1_000, Long.MAX_VALUE)) {
            // runs until the re-check is done
        }
    }

    private RailGraph graphAt(final RailPos pos) {
        return networks.graph(WORLD, pos).orElseThrow();
    }

    /** Runs the rides one tick, and then what they scheduled for the next one. */
    private void tick() {
        rides.tick();
        final List<Runnable> due = List.copyOf(later);
        later.clear();
        due.forEach(Runnable::run);
    }

    private void tick(final int ticks) {
        for (int count = 0; count < ticks; count++) {
            tick();
        }
    }

    private void tickUntil(final BooleanSupplier done) {
        for (int count = 0; count < 5_000; count++) {
            if (done.getAsBoolean()) {
                return;
            }
            tick();
        }
        throw new AssertionError("the rides did not end");
    }

    private boolean ended(final Rider... riders) {
        for (final Rider rider : riders) {
            if (rides.driven(rider.cart)) {
                return false;
            }
        }
        return true;
    }

    private Rider drive(final RailPos from, final int to) {
        final Rider rider = new Rider(from);
        assertTrue(rides.drive(rider.player, rider.cart, station(to)), "a way leads there");
        return rider;
    }

    private void assertSetDownOn(final RailPos station, final Rider rider) {
        assertFalse(rider.setDown.isEmpty(), "the rider was set down");
        final Location down = rider.setDown.getLast();
        assertEquals(
                List.of(station.x() + 0.5, (double) station.y(), station.z() + 0.5),
                List.of(down.getX(), down.getY(), down.getZ()));
        assertFalse(rider.valid, "the cart went into the inventory");
    }

    private void assertEveryChunkLetGo() {
        tickets.forEach((key, count) -> assertEquals(0, count, "the tickets of chunk " + key));
        assertFalse(tickets.isEmpty(), "the ride held the chunks ahead");
    }

    @Test
    void aCartArrivesAndSetsItsRiderDownOnTheStation() {
        track.eastWest(0, 40, 64, 0);
        stations(at(0, 64, 0), at(40, 64, 0));

        final Rider rider = drive(at(0, 64, 0), 2);
        tickUntil(() -> ended(rider));

        assertSetDownOn(at(40, 64, 0), rider);
        assertEquals(List.of(), stranded);
        assertTrue(rider.gravity, "vanilla physics are back");
        assertEveryChunkLetGo();
    }

    @Test
    void aDisplayThatFailsOnArrivalStillSetsTheRiderDown() {
        track.eastWest(0, 40, 64, 0);
        stations(at(0, 64, 0), at(40, 64, 0));
        rides.watch(new RideWatch() {
            @Override
            public void arrived(final Player rider, final UUID station, final String name) {
                throw new IllegalStateException("no document");
            }
        });

        final Rider rider = drive(at(0, 64, 0), 2);
        assertThrows(IllegalStateException.class, () -> tickUntil(() -> ended(rider)));

        assertSetDownOn(at(40, 64, 0), rider);
        assertTrue(rider.gravity, "vanilla physics are back");
    }

    @Test
    void aRiderWhoGetsOutStaysOnTheTrackAndPocketsTheCart() {
        track.eastWest(0, 40, 64, 0);
        stations(at(0, 64, 0), at(40, 64, 0));
        final Rider rider = drive(at(0, 64, 0), 2);
        tick(30);
        final double where = rider.x();

        rider.vehicle = null;
        rides.onExit(new VehicleExitEvent(rider.cart, rider.player));
        tick();

        assertFalse(rides.driven(rider.cart));
        assertEquals(where, rider.setDown.getLast().getX(), "the rider stands where the cart was");
        assertFalse(rider.valid);
        assertEquals(List.of(), stranded);
        assertEveryChunkLetGo();
    }

    @Test
    void aCartWhoseWayIsBrokenStopsAndStrandsItsRider() {
        track.eastWest(0, 40, 64, 0);
        stations(at(0, 64, 0), at(40, 64, 0));
        final Rider rider = drive(at(0, 64, 0), 2);
        tick(40);
        final RailGraph before = graphAt(at(0, 64, 0));

        track.remove(30, 64, 0);
        changed(at(30, 64, 0));
        assertFalse(before.current(), "the break changed the network the cart rides");
        tickUntil(() -> ended(rider));
        tick();

        assertEquals(List.of(rider.player), stranded);
        assertTrue(rider.x() < 30, "the cart stopped before the gap");
        assertTrue(rider.valid, "the cart stays on the track");
        assertTrue(rider.gravity);
        assertEveryChunkLetGo();
    }

    @Test
    void aCartPlansAgainWhileMovingWhenItsNetworkChanges() {
        track.eastWest(0, 60, 64, 0);
        stations(at(0, 64, 0), at(60, 64, 0));
        final Rider rider = drive(at(0, 64, 0), 2);
        tick(40);
        final RailGraph before = graphAt(at(0, 64, 0));

        // A branch ahead makes a junction, which is already set the way the cart goes.
        track.northSouth(40, 64, -5, -1);
        changed(at(40, 64, -1));
        assertFalse(before.current());
        assertNotSame(before, graphAt(at(0, 64, 0)));
        assertTrue(graphAt(at(0, 64, 0)).junctions().contains(at(40, 64, 0)));
        double last = rider.x();
        for (int count = 0; count < 20; count++) {
            tick();
            assertTrue(rider.x() > last, "the cart keeps going on tick " + count);
            last = rider.x();
        }
        tickUntil(() -> ended(rider));

        assertSetDownOn(at(60, 64, 0), rider);
        assertEquals(List.of(), stranded);
    }

    @Test
    void aCartStopsAsFarBeforeAWrongJunctionAsTheSettingsSayAndTheSwitchIsShownAtTheJunction() {
        // A line from west to east, and a branch to the north from its middle.
        track.eastWest(-30, 30, 64, 0).northSouth(0, 64, -30, -1);
        stations(at(-30, 64, 0), at(30, 64, 0), at(0, 64, -30));
        final List<Location> shown = new ArrayList<>();
        rides.whenSwitched(shown::add);
        final Rider rider = drive(at(-30, 64, 0), 3);
        tick();
        // Changed while the cart already rides, as Steward changes it.
        spec = new RailsSpec() {
            @Override
            public int junctionStop() {
                return 7;
            }
        };
        tickUntil(() -> track.rails().get(at(0, 64, 0)) != RailShape.EAST_WEST);

        assertEquals(RailShape.NORTH_WEST, track.rails().get(at(0, 64, 0)));
        assertEquals(-7, rider.x(), 0.6, "it stands seven blocks before the junction");
        assertEquals(
                List.of(List.of(0.5, 64.5, 0.5)),
                shown.stream()
                        .map(at -> List.of(at.getX(), at.getY(), at.getZ()))
                        .toList(),
                "the switch is shown once, at the junction's centre");
    }

    @Test
    void aCartWaitsForAJunctionAnotherRidingCartHolds() {
        // A line from west to east, and a branch to the north from its middle.
        track.eastWest(-30, 30, 64, 0).northSouth(0, 64, -30, -1);
        stations(at(-30, 64, 0), at(30, 64, 0), at(0, 64, -30));
        assertTrue(graphAt(at(-30, 64, 0)).junctions().contains(at(0, 64, 0)));
        // The branch's chunks are still loading, so the first cart switches the junction and stands on it.
        chunkZLoaded = chunkZ -> chunkZ >= 0;
        final Rider north = drive(at(-30, 64, 0), 3);
        tick(200);
        assertTrue(rides.driven(north.cart));
        assertEquals(
                Rail.Shape.NORTH_WEST.name(), track.rails().get(at(0, 64, 0)).name());

        final Rider west = drive(at(30, 64, 0), 1);
        tick(250);

        assertEquals(List.of(), stranded, "the second cart waits longer than its patience for a cart that rides on");
        assertTrue(rides.driven(west.cart));
        assertTrue(west.x() > 1, "it stands before the junction");

        chunkZLoaded = _ -> true;
        tickUntil(() -> ended(north, west));

        assertEquals(List.of(), stranded);
        assertSetDownOn(at(0, 64, -30), north);
        assertSetDownOn(at(-30, 64, 0), west);
    }

    private RailsSpec spec = new RailsSpec() {};
    private final List<String> calls = new ArrayList<>();
    private Location standing = new Location(null, 10, 64, 20);
    private final RideableMinecart recorded =
            fake(RideableMinecart.class, (_, method, args) -> switch (method.getName()) {
                case "getLocation" -> standing.clone();
                case "teleport" -> {
                    standing = ((Location) args[0]).clone();
                    calls.add("teleport " + standing.getX() + "," + standing.getY() + "," + standing.getZ());
                    yield true;
                }
                case "setVelocity" -> {
                    final Vector velocity = (Vector) args[0];
                    yield calls.add("velocity " + velocity.getX() + "," + velocity.getY() + "," + velocity.getZ());
                }
                case "setMaxSpeed" -> calls.add("max speed " + args[0]);
                case "setGravity" -> calls.add("gravity " + args[0]);
                case "getPickItemStack" -> null;
                case "remove" -> calls.add("cart removed");
                default -> throw new UnsupportedOperationException(method.getName());
            });

    /** Returns a rider in {@code mode} whose inventory takes the cart when {@code room}, or drops it at their feet. */
    private Player rider(final GameMode mode, final boolean room) {
        final Location feet = new Location(null, 3, 70, 4);
        final World world = proxy(World.class, (method, args) -> {
            if (method.equals("dropItem") && args[0].equals(feet)) {
                calls.add("dropped at the feet");
                return null;
            }
            throw new UnsupportedOperationException(method);
        });
        final PlayerInventory inventory = proxy(PlayerInventory.class, (method, args) -> {
            if (!method.equals("addItem")) {
                throw new UnsupportedOperationException(method);
            }
            calls.add("into the inventory");
            final HashMap<Integer, ItemStack> left = new HashMap<>();
            if (!room) {
                left.put(0, ((ItemStack[]) args[0])[0]);
            }
            return left;
        });
        return proxy(Player.class, (method, _) -> switch (method) {
            case "getGameMode" -> mode;
            case "getInventory" -> inventory;
            case "getWorld" -> world;
            case "getLocation" -> feet.clone();
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(final Class<T> type, final Answer answer) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (_, method, args) -> answer.answer(method.getName(), args == null ? new Object[0] : args)));
    }

    @Test
    void gettingOutOnTheWaySetsTheRiderOnTheTrackWithTheCartPutAway() {
        assertEquals(Rides.Leaving.ON_TRACK, Rides.leaving(true, false, true));
    }

    @Test
    void gettingOutOfAStandingCartOnANetworkPutsItAway() {
        assertEquals(Rides.Leaving.POCKET, Rides.leaving(true, false, false));
    }

    @Test
    void aRiderWhoDiedOrSitsElsewhereLeavesTheCartAsAnItemWhereItStands() {
        assertEquals(Rides.Leaving.DROP, Rides.leaving(true, true, true));
        assertEquals(Rides.Leaving.DROP, Rides.leaving(true, true, false));
    }

    @Test
    void aRiderWhoLoggedOutOrArrivedLeavesTheCartAlone() {
        assertEquals(Rides.Leaving.KEEP, Rides.leaving(false, false, true));
        assertEquals(Rides.Leaving.KEEP, Rides.leaving(false, true, false));
    }

    @Test
    void aMovedCartRidesAtTheStepItMade() {
        Rides.moveTo(recorded, new Location(null, 11.5, 65, 18));

        assertEquals(List.of("teleport 11.5,65.0,18.0", "velocity 1.5,0.0,-2.0"), calls);
    }

    @Test
    void aCartGoesIntoTheInventoryWhenThereIsRoom() {
        Rides.pocket(recorded, rider(GameMode.SURVIVAL, true));

        assertEquals(List.of("into the inventory", "cart removed"), calls);
    }

    @Test
    void aCartDropsAtTheFeetOfARiderWithAFullInventory() {
        Rides.pocket(recorded, rider(GameMode.SURVIVAL, false));

        assertEquals(List.of("into the inventory", "dropped at the feet", "cart removed"), calls);
    }

    @Test
    void aCartOfACreativeRiderIsOnlyRemoved() {
        Rides.pocket(recorded, rider(GameMode.CREATIVE, false));

        assertEquals(List.of("cart removed"), calls);
    }

    @Test
    void aCartLeftStandingHasNoVelocityAndVanillaPhysicsBack() {
        Rides.standStill(recorded);

        assertEquals(List.of("velocity 0.0,0.0,0.0", "max speed 0.4", "gravity true"), calls);
    }
}
