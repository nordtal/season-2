package eu.nordtal.season.smp.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;

class WorldsTest {
    @Test
    void onlyAnActiveCustomBorderSetsTheMapLimit() {
        final List<String> changes = new ArrayList<>();
        final WorldBorder border = (WorldBorder) Proxy.newProxyInstance(
                WorldBorder.class.getClassLoader(), new Class<?>[] {WorldBorder.class}, (proxy, method, args) -> {
                    changes.add(method.getName() + List.of(args));
                    return null;
                });
        assertFalse(Worlds.applyOuterLimit(border, false));
        assertTrue(changes.isEmpty());
        assertTrue(Worlds.applyOuterLimit(border, true));
        assertEquals(List.of("setCenter[5120.0, 5120.0]", "setSize[10240.0]"), changes);
    }
}
