package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class RidesTest {

    private final List<String> calls = new ArrayList<>();
    private Location at = new Location(null, 10, 64, 20);
    private final RideableMinecart cart = (RideableMinecart) Proxy.newProxyInstance(
            RideableMinecart.class.getClassLoader(),
            new Class<?>[] {RideableMinecart.class},
            (_, method, args) -> switch (method.getName()) {
                case "getLocation" -> at.clone();
                case "teleport" -> {
                    at = ((Location) args[0]).clone();
                    calls.add("teleport " + at.getX() + "," + at.getY() + "," + at.getZ());
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
        Rides.moveTo(cart, new Location(null, 11.5, 65, 18));

        assertEquals(List.of("teleport 11.5,65.0,18.0", "velocity 1.5,0.0,-2.0"), calls);
    }

    @Test
    void aCartGoesIntoTheInventoryWhenThereIsRoom() {
        Rides.pocket(cart, rider(GameMode.SURVIVAL, true));

        assertEquals(List.of("into the inventory", "cart removed"), calls);
    }

    @Test
    void aCartDropsAtTheFeetOfARiderWithAFullInventory() {
        Rides.pocket(cart, rider(GameMode.SURVIVAL, false));

        assertEquals(List.of("into the inventory", "dropped at the feet", "cart removed"), calls);
    }

    @Test
    void aCartOfACreativeRiderIsOnlyRemoved() {
        Rides.pocket(cart, rider(GameMode.CREATIVE, false));

        assertEquals(List.of("cart removed"), calls);
    }

    @Test
    void aCartLeftStandingHasNoVelocityAndVanillaPhysicsBack() {
        Rides.standStill(cart);

        assertEquals(List.of("velocity 0.0,0.0,0.0", "max speed 0.4", "gravity true"), calls);
    }
}
