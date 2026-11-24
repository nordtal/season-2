package eu.nordtal.season.papercommon.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Blocks inbound inventory mutation packets while a player is in an NPC dialogue.
 * Stops Survival and Creative clients from unequipping, copying, dropping or swapping the packet-only vignette or
 * hidden hotbar slots.
 */
final class DialoguePacketGuard extends PacketListenerAbstract {

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Consumer<UUID> onBlocked;

    DialoguePacketGuard(final Consumer<UUID> onBlocked) {
        super(PacketListenerPriority.NORMAL);
        this.onBlocked = onBlocked;
    }

    /** Registers this listener on PacketEvents. */
    void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /** Unregisters this listener and clears all tracked dialogue players. */
    void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        active.clear();
    }

    /** Starts blocking inventory mutation packets from {@code player}. */
    void activate(final UUID player) {
        active.add(player);
    }

    /** Stops blocking inventory mutation packets from {@code player}. */
    void deactivate(final UUID player) {
        active.remove(player);
    }

    /** Returns whether {@code player} currently has inbound inventory packets blocked. */
    boolean isActive(final UUID player) {
        return active.contains(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player == null || !active.contains(player)) {
            return;
        }
        if (!(event.getPacketType() instanceof final PacketType.Play.Client type)) {
            return;
        }
        final @Nullable DiggingAction action = type == PacketType.Play.Client.PLAYER_DIGGING
                ? new WrapperPlayClientPlayerDigging(event).getAction()
                : null;
        if (shouldBlock(type, action)) {
            event.setCancelled(true);
            onBlocked.accept(player);
        }
    }

    @Override
    public void onUserDisconnect(final UserDisconnectEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player != null) {
            active.remove(player);
        }
    }

    /** Returns whether an inbound client packet of {@code type} and optional digging {@code action} must be blocked. */
    static boolean shouldBlock(final PacketType.Play.Client type, final @Nullable DiggingAction action) {
        return switch (type) {
            case CREATIVE_INVENTORY_ACTION, CLICK_WINDOW -> true;
            case PLAYER_DIGGING ->
                action == DiggingAction.DROP_ITEM
                        || action == DiggingAction.DROP_ITEM_STACK
                        || action == DiggingAction.SWAP_ITEM_WITH_OFFHAND;
            default -> false;
        };
    }
}
