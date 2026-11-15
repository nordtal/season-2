package eu.nordtal.season.smp.rail;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the teleport of a driven cart, which the server sends its rider every tick, from reaching that rider.
 * On that packet the client snaps the rider's camera and ears to the cart once a tick, the likely cause of the tone a
 * rider hears; without it the rider's client follows the cart through its moves, as a bystander's does.
 */
public final class RiderCartPackets extends PacketListenerAbstract implements DrivenCarts {

    /** The rider of each driven cart, by the cart's entity id. Packets are sent from the network threads. */
    private final Map<Integer, UUID> riders = new ConcurrentHashMap<>();

    RiderCartPackets() {
        super(PacketListenerPriority.NORMAL);
    }

    /** Starts to hold back the teleports of driven carts from their riders. */
    public static RiderCartPackets register() {
        final RiderCartPackets packets = new RiderCartPackets();
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        return packets;
    }

    /** Lets every teleport through again. */
    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        riders.clear();
    }

    @Override
    public void started(final int cart, final UUID rider) {
        riders.put(cart, rider);
    }

    @Override
    public void ended(final int cart) {
        riders.remove(cart);
    }

    /** Whether a teleport of the entity {@code entity} is held back from the player {@code user}. */
    boolean hides(final int entity, final @Nullable UUID user) {
        return user != null && user.equals(riders.get(entity));
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_TELEPORT || riders.isEmpty()) {
            return;
        }
        if (hides(
                new WrapperPlayServerEntityTeleport(event).getEntityId(),
                event.getUser().getUUID())) {
            event.setCancelled(true);
        }
    }
}
