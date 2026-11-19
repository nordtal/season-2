package eu.nordtal.season.smp.rail;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the teleport of a driven cart from its rider, and a repeated mount of a player from that player.
 * Without the teleports the rider's client follows the cart through its moves, as a bystander's does. The server sends
 * every mount twice, and the client starts its riding sound loops on each, so one cart would sound like several.
 */
public final class RiderCartPackets extends PacketListenerAbstract implements DrivenCarts {

    /** The rider of each driven cart, by the cart's entity id. Packets are sent from the network threads. */
    private final Map<Integer, UUID> riders = new ConcurrentHashMap<>();

    /** What each client last heard about the passengers of each vehicle. A client's packets come on one thread. */
    private final Map<User, PassengerEchoes> echoes = new ConcurrentHashMap<>();

    RiderCartPackets() {
        super(PacketListenerPriority.NORMAL);
    }

    /** Starts to hold back the teleports of driven carts from their riders, and repeated mounts from every player. */
    public static RiderCartPackets register() {
        final RiderCartPackets packets = new RiderCartPackets();
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        return packets;
    }

    /** Lets every teleport and mount through again. */
    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        riders.clear();
        echoes.clear();
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
        if (!(event.getPacketType() instanceof final PacketType.Play.Server type)) {
            return;
        }
        switch (type) {
            case ENTITY_TELEPORT -> {
                if (!riders.isEmpty()
                        && hides(
                                new WrapperPlayServerEntityTeleport(event).getEntityId(),
                                event.getUser().getUUID())) {
                    event.setCancelled(true);
                }
            }
            case SET_PASSENGERS -> {
                final WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
                final User user = event.getUser();
                if (echoesOf(user).repeats(packet.getEntityId(), packet.getPassengers(), user.getEntityId())) {
                    event.setCancelled(true);
                }
            }
            case SPAWN_ENTITY ->
                echoesOf(event.getUser()).forget(new WrapperPlayServerSpawnEntity(event).getEntityId());
            case DESTROY_ENTITIES -> {
                final PassengerEchoes heard = echoesOf(event.getUser());
                for (final int entity : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                    heard.forget(entity);
                }
            }
            case RESPAWN, JOIN_GAME -> echoesOf(event.getUser()).forgetAll();
            default -> {}
        }
    }

    @Override
    public void onUserDisconnect(final UserDisconnectEvent event) {
        echoes.remove(event.getUser());
    }

    private PassengerEchoes echoesOf(final User user) {
        return echoes.computeIfAbsent(user, _ -> new PassengerEchoes());
    }
}
