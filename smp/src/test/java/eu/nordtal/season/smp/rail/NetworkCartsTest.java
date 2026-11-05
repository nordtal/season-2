package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class NetworkCartsTest {

    private static final NetworkCarts.Click VANILLA = NetworkCarts.Click.VANILLA;

    @Test
    void aRightClickOnANetworkRailSeatsAPlayerWithAMinecart() {
        assertEquals(NetworkCarts.Click.SEAT, NetworkCarts.click(true, false, false, true, true));
    }

    @Test
    void aPlayerWithNoMinecartIsToldSo() {
        assertEquals(NetworkCarts.Click.NO_MINECART, NetworkCarts.click(true, false, false, true, false));
    }

    @Test
    void sneakingTheOffHandASeatedPlayerAndOtherRailsKeepTheVanillaClick() {
        assertEquals(VANILLA, NetworkCarts.click(true, false, true, true, true), "sneaking");
        assertEquals(VANILLA, NetworkCarts.click(true, false, false, false, true), "the off hand");
        assertEquals(VANILLA, NetworkCarts.click(true, true, false, true, true), "already seated");
        assertEquals(VANILLA, NetworkCarts.click(false, false, false, true, true), "a rail of no network");
        assertEquals(VANILLA, NetworkCarts.click(false, false, false, true, false), "no minecart off a network");
    }

    @Test
    void theOffHandsClickAfterTheMainHandSeatedThePlayerDoesNothing() {
        assertEquals(NetworkCarts.Click.NOTHING, NetworkCarts.click(true, true, false, false, true));
        assertEquals(VANILLA, NetworkCarts.click(false, true, false, false, true), "a rail of no network");
    }

    @Test
    void aStationRailSeatsOnlyAPlayerWithAMinecartInTheMainHandWhoNeitherSneaksNorSits() {
        assertTrue(NetworkCarts.seatsOnStation(false, false, true), "a minecart in the hand");
        assertFalse(NetworkCarts.seatsOnStation(false, false, false), "none in the hand: station menu");
        assertFalse(NetworkCarts.seatsOnStation(true, false, true), "sneaking: station menu");
        assertFalse(NetworkCarts.seatsOnStation(false, true, true), "seated");
    }

    @Test
    void noMinecartIsPlacedOnANetworkRailNorBySomeoneSeated() {
        assertFalse(NetworkCarts.placeable(true, false), "on a network rail");
        assertFalse(NetworkCarts.placeable(false, true), "while seated");
        assertFalse(NetworkCarts.placeable(true, true), "both");
        assertTrue(NetworkCarts.placeable(false, false), "elsewhere, standing");
    }

    private final List<String> calls = new ArrayList<>();

    private NetworkCarts carts(final boolean onNetwork, final boolean driven) {
        return new NetworkCarts(
                new NetworkCarts.Track() {
                    @Override
                    public boolean networkRail(final Block rail) {
                        return onNetwork;
                    }

                    @Override
                    public boolean onNetwork(final Entity cart) {
                        return onNetwork;
                    }

                    @Override
                    public boolean driven(final Entity cart) {
                        return driven;
                    }

                    @Override
                    public void openRide(final Player rider) {
                        calls.add("ride menu");
                    }
                },
                new NetworkCarts.Tell() {
                    @Override
                    public void noMinecart(final Player player) {
                        calls.add("no minecart");
                    }

                    @Override
                    public void refused(final Player player) {
                        calls.add("refused");
                    }
                },
                work -> {
                    calls.add("next tick");
                    work.run();
                });
    }

    private RideableMinecart cart(final boolean gravity) {
        return (RideableMinecart) Proxy.newProxyInstance(
                RideableMinecart.class.getClassLoader(),
                new Class<?>[] {RideableMinecart.class},
                (_, method, args) -> switch (method.getName()) {
                    case "hasGravity" -> gravity;
                    case "setVelocity" -> calls.add("still");
                    case "setMaxSpeed" -> calls.add("vanilla speed");
                    case "setGravity" -> calls.add("gravity " + args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player player(final @Nullable Entity vehicle) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (_, method, _) -> switch (method.getName()) {
                    case "isOnline" -> true;
                    case "getVehicle" -> vehicle;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aPlayerWhoLogsInSeatedOnANetworkGetsTheRideMenu() {
        carts(true, false).onJoin(new PlayerJoinEvent(player(cart(true)), Component.empty()));

        assertEquals(List.of("next tick", "ride menu"), calls);
    }

    @Test
    void aCartACrashLeftFloatingFallsAgainOnJoin() {
        carts(true, false).onJoin(new PlayerJoinEvent(player(cart(false)), Component.empty()));

        assertEquals(List.of("next tick", "still", "vanilla speed", "gravity true", "ride menu"), calls);
    }

    @Test
    void aPlayerWhoLogsInElsewhereOrOnFootGetsNoMenu() {
        carts(false, false).onJoin(new PlayerJoinEvent(player(cart(true)), Component.empty()));
        carts(true, false).onJoin(new PlayerJoinEvent(player(null), Component.empty()));

        assertEquals(List.of("next tick", "next tick"), calls);
    }

    @Test
    void aDrivenCartKeepsItsRideOnJoin() {
        carts(true, true).onJoin(new PlayerJoinEvent(player(cart(false)), Component.empty()));

        assertEquals(List.of("next tick"), calls);
    }

    @Test
    void aFloatingCartFallsAgainWhenItsChunkLoadsAndADrivenOneIsLeftAlone() {
        final Chunk chunk = (Chunk)
                Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class}, (_, method, _) -> {
                    if (method.getName().equals("getWorld")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        carts(true, false).onEntitiesLoad(new EntitiesLoadEvent(chunk, List.of(cart(false), cart(true))));
        carts(true, true).onEntitiesLoad(new EntitiesLoadEvent(chunk, List.of(cart(false))));

        assertEquals(List.of("still", "vanilla speed", "gravity true"), calls);
    }
}
