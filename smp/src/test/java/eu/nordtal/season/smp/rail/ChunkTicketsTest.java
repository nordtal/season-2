package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ChunkTicketsTest {

    private final List<String> calls = new ArrayList<>();
    private final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[] {World.class}, (_, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "addPluginChunkTicket" -> calls.add("add " + args[0] + "," + args[1]);
                case "removePluginChunkTicket" -> calls.add("remove " + args[0] + "," + args[1]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final Plugin plugin = (Plugin)
            Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, (_, method, _) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void aChunkHeldByAWalkAndARideKeepsItsTicketUntilBothLetGo() {
        final ChunkTickets tickets = new ChunkTickets(plugin);

        tickets.hold(world, 3, -2);
        tickets.hold(world, 3, -2);
        tickets.release(world, Chunk.getChunkKey(3, -2));

        assertEquals(List.of("add 3,-2"), calls, "the walk let go, the ride still holds it");

        tickets.release(world, Chunk.getChunkKey(3, -2));
        tickets.release(world, Chunk.getChunkKey(3, -2));

        assertEquals(List.of("add 3,-2", "remove 3,-2"), calls);
    }
}
