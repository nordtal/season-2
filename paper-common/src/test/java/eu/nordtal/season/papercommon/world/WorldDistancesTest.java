package eu.nordtal.season.papercommon.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** How the wanted distances reach a world, which is all Paper's {@code World} is asked here. */
class WorldDistancesTest {

    @Test
    void nothingSetLeavesTheWorldAsTheServerRunsIt() {
        final FakeWorld world = new FakeWorld(12, 8);

        distances(Distances.NONE).apply(world.world());

        assertEquals(List.of(), world.calls, "a world with nothing set must not be touched at all");
        assertEquals(List.of(12, 8), world.now());
    }

    @Test
    void aSetDistanceIsAppliedAndUnsettingItGivesTheServersOwnBack() {
        final FakeWorld world = new FakeWorld(10, 10);
        final WorldDistances distances = distances(new Distances(32, 10));

        distances.apply(world.world());
        assertEquals(List.of(32, 10), world.now());
        assertEquals(List.of("view 32"), world.calls, "a distance the world already has is not set again");

        distances.want(Distances.NONE);
        distances.apply(world.world());
        assertEquals(List.of(10, 10), world.now(), "an unset distance returns to the one the world had");
    }

    private static WorldDistances distances(final Distances wanted) {
        return new WorldDistances(wanted, LoggerFactory.getLogger(WorldDistancesTest.class));
    }

    /** A world that holds its two distances and records every change asked of it. */
    private static final class FakeWorld {

        private final List<String> calls = new ArrayList<>();
        private int view;
        private int simulation;

        FakeWorld(final int view, final int simulation) {
            this.view = view;
            this.simulation = simulation;
        }

        List<Integer> now() {
            return List.of(view, simulation);
        }

        World world() {
            return (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(),
                    new Class<?>[] {World.class},
                    (proxy, method, args) -> answer(method, args));
        }

        private Object answer(final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "getName" -> "nordtal";
                case "getViewDistance" -> view;
                case "getSimulationDistance" -> simulation;
                case "setViewDistance" -> {
                    view = (int) args[0];
                    calls.add("view " + view);
                    yield null;
                }
                case "setSimulationDistance" -> {
                    simulation = (int) args[0];
                    calls.add("simulation " + simulation);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
