package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.minecart.RideableMinecart;
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
                default -> throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void aMovedCartRidesAtTheStepItMade() {
        Rides.moveTo(cart, new Location(null, 11.5, 65, 18));

        assertEquals(List.of("teleport 11.5,65.0,18.0", "velocity 1.5,0.0,-2.0"), calls);
    }

    @Test
    void aCartLeftStandingHasNoVelocityAndVanillaPhysicsBack() {
        Rides.standStill(cart, 0.4);

        assertEquals(List.of("velocity 0.0,0.0,0.0", "max speed 0.4", "gravity true"), calls);
    }
}
