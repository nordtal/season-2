package eu.nordtal.season.displaytags.nametag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

class PlayerListenerTest {

    private final World overworld = world("world");
    private final World nether = world("world_nether");

    @Test
    void aRiderCarriedByTheirVehicleKeepsTheTagOnThem() {
        assertTrue(PlayerListener.followsVehicle(teleport(true, overworld, overworld)));
    }

    @Test
    void aPlayerOnFootTakesTheTagAlong() {
        assertFalse(PlayerListener.followsVehicle(teleport(false, overworld, overworld)));
    }

    @Test
    void aRiderTakenToAnotherWorldTakesTheTagAlong() {
        assertFalse(PlayerListener.followsVehicle(teleport(true, overworld, nether)));
    }

    private static PlayerTeleportEvent teleport(final boolean riding, final World from, final World to) {
        final Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (_, method, _) -> switch (method.getName()) {
                    case "isInsideVehicle" -> riding;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new PlayerTeleportEvent(player, new Location(from, 0, 64, 0), new Location(to, 3, 64, 0));
    }

    private static World world(final String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (_, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" ->
                        args[0] instanceof World other && other.getName().equals(name);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
