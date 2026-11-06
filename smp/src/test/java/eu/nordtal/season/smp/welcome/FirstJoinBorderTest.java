package eu.nordtal.season.smp.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;

class FirstJoinBorderTest {

    /** A border 20 blocks wide around 3979 2188, as the season's waiting milestone has it. */
    private static final WorldBorder WAITING = (WorldBorder) Proxy.newProxyInstance(
            WorldBorder.class.getClassLoader(), new Class<?>[] {WorldBorder.class}, (proxy, method, args) -> {
                if (method.getName().equals("isInside")) {
                    final Location at = (Location) args[0];
                    return Math.abs(at.getX() - 3979) <= 10 && Math.abs(at.getZ() - 2188) <= 10;
                }
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void aLandingOutsideTheBorderIsRefused() {
        assertTrue(
                SeasonWelcome.insideBorder(WAITING, new Location(null, 106.5, 68, 88.5))
                        .isEmpty(),
                "a first join outside the border kills the player in the same tick");
    }

    @Test
    void aLandingInsideTheBorderIsKept() {
        final Location landing = new Location(null, 3975.5, 84, 2192.5);
        assertEquals(Optional.of(landing), SeasonWelcome.insideBorder(WAITING, landing));
    }
}
