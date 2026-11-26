package eu.nordtal.season.papercommon.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Guards inbound and outbound packets while a player is in an NPC dialogue.
 * Confirms empty-hand air clicks, masks outbound inventory syncs to prevent item flashes and buffers chat until exit.
 */
final class DialoguePacketGuard extends PacketListenerAbstract {

    static final int MAX_QUEUED_CHAT = 100;

    /** How an outbound inventory slot is rewritten while a dialogue is active. */
    enum SlotMask {
        KEEP,
        EMPTY,
        HIDDEN_HAND,
        VIGNETTE
    }

    /** Bounded FIFO queue for chat packets suppressed while a dialogue runs. */
    static final class ChatBuffer<T> {
        private record Entry<T>(T item, boolean protectedEntry) {}

        private final int limit;
        private final Deque<Entry<T>> items = new ArrayDeque<>();
        private boolean closed;

        ChatBuffer(final int limit) {
            this.limit = limit;
        }

        synchronized boolean add(final T item, final boolean protectFromEviction) {
            if (closed) {
                return false;
            }
            if (!protectFromEviction) {
                while (items.size() >= limit && evictOldestUnprotected()) {
                    // Drop the oldest non-protected entry until within limit.
                }
            }
            items.addLast(new Entry<>(item, protectFromEviction));
            return true;
        }

        synchronized List<T> closeAndDrain(final Consumer<T> onItem) {
            closed = true;
            final List<T> drained = items.stream().map(Entry::item).toList();
            items.clear();
            for (final T item : drained) {
                onItem.accept(item);
            }
            return drained;
        }

        private boolean evictOldestUnprotected() {
            final Iterator<Entry<T>> it = items.iterator();
            while (it.hasNext()) {
                if (!it.next().protectedEntry()) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    static final class State {
        final ViewPackets.@Nullable MaskItems masks;
        final ChatBuffer<PacketWrapper<?>> queuedChat = new ChatBuffer<>(MAX_QUEUED_CHAT);
        volatile boolean inventoryMasked = true;
        volatile int heldSlot;
        volatile int vignetteStage;

        State(final int heldSlot, final ViewPackets.@Nullable MaskItems masks) {
            this.heldSlot = heldSlot;
            this.masks = masks;
        }
    }

    private final Map<UUID, State> active = new ConcurrentHashMap<>();
    private final Consumer<UUID> onBlocked;
    private final Consumer<UUID> onConfirm;

    DialoguePacketGuard(final Consumer<UUID> onBlocked, final Consumer<UUID> onConfirm) {
        super(PacketListenerPriority.NORMAL);
        this.onBlocked = onBlocked;
        this.onConfirm = onConfirm;
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

    /** Starts guarding packets for {@code player} with initial {@code heldSlot} and prebuilt {@code masks}. */
    void activate(final UUID player, final int heldSlot, final ViewPackets.@Nullable MaskItems masks) {
        active.put(player, new State(heldSlot, masks));
    }

    /** Updates the masked hotbar slot and vignette stage for an active dialogue player. */
    void updateVisuals(final UUID player, final int heldSlot, final int vignetteStage) {
        final State state = active.get(player);
        if (state != null) {
            state.heldSlot = heldSlot;
            state.vignetteStage = vignetteStage;
        }
    }

    /** Unmasks outbound inventory packets so real inventory resyncs reach the client while chat stays ordered. */
    @Nullable
    State releaseInventory(final UUID player) {
        final State state = active.get(player);
        if (state != null) {
            state.inventoryMasked = false;
        }
        return state;
    }

    /** Flushes buffered chat in order under the queue lock, then removes {@code state} if still current. */
    void deactivateAndFlush(final UUID player, final @Nullable State state, final Consumer<PacketWrapper<?>> sender) {
        if (state == null) {
            return;
        }
        try {
            state.queuedChat.closeAndDrain(sender);
        } finally {
            active.remove(player, state);
        }
    }

    /** Returns whether {@code player} currently has dialogue packet guards active. */
    boolean isActive(final UUID player) {
        return active.containsKey(player);
    }

    /** Returns whether outbound inventory packets are currently masked for {@code player}. */
    boolean isInventoryMasked(final UUID player) {
        final State state = active.get(player);
        return state != null && state.inventoryMasked;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player == null) {
            return;
        }
        final State state = active.get(player);
        if (state == null || !state.inventoryMasked) {
            return;
        }
        if (!(event.getPacketType() instanceof final PacketType.Play.Client type)) {
            return;
        }
        if (type == PacketType.Play.Client.USE_ITEM) {
            final WrapperPlayClientUseItem use = new WrapperPlayClientUseItem(event);
            event.setCancelled(true);
            if (isConfirmUseItem(type, use.getHand())) {
                onConfirm.accept(player);
            }
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
    public void onPacketSend(final PacketSendEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player == null) {
            return;
        }
        final State state = active.get(player);
        if (state == null || !(event.getPacketType() instanceof final PacketType.Play.Server type)) {
            return;
        }
        switch (type) {
            case SET_SLOT -> maskSetSlot(event, state);
            case SET_PLAYER_INVENTORY -> maskPlayerInventory(event, state);
            case WINDOW_ITEMS -> maskWindowItems(event, state);
            case SYSTEM_CHAT_MESSAGE -> suppressSystemChat(event, state);
            case DISGUISED_CHAT -> suppressDisguisedChat(event, state);
            case CHAT_MESSAGE -> suppressPlayerChat(event, state);
            default -> {}
        }
    }

    @Override
    public void onUserDisconnect(final UserDisconnectEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player != null) {
            active.remove(player);
        }
    }

    private static void maskSetSlot(final PacketSendEvent event, final State state) {
        if (!state.inventoryMasked) {
            return;
        }
        final WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        if (packet.getWindowId() != 0) {
            return;
        }
        final int heldSlot = state.heldSlot;
        final int vignetteStage = state.vignetteStage;
        final @Nullable ItemStack masked =
                applyMask(windowSlotMask(packet.getSlot(), heldSlot, vignetteStage), vignetteStage, state.masks);
        if (masked != null) {
            packet.setItem(masked);
            event.markForReEncode(true);
        }
    }

    private static void maskPlayerInventory(final PacketSendEvent event, final State state) {
        if (!state.inventoryMasked) {
            return;
        }
        final WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
        final int heldSlot = state.heldSlot;
        final int vignetteStage = state.vignetteStage;
        final @Nullable ItemStack masked = applyMask(
                playerInventorySlotMask(packet.getSlot(), heldSlot, vignetteStage), vignetteStage, state.masks);
        if (masked != null) {
            packet.setStack(masked);
            event.markForReEncode(true);
        }
    }

    private static void maskWindowItems(final PacketSendEvent event, final State state) {
        if (!state.inventoryMasked) {
            return;
        }
        final WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        if (packet.getWindowId() != 0) {
            return;
        }
        final int heldSlot = state.heldSlot;
        final int vignetteStage = state.vignetteStage;
        final List<ItemStack> items = packet.getItems();
        boolean changed = false;
        for (int slot = 0; slot < items.size(); slot++) {
            final @Nullable ItemStack masked =
                    applyMask(windowSlotMask(slot, heldSlot, vignetteStage), vignetteStage, state.masks);
            if (masked != null) {
                items.set(slot, masked);
                changed = true;
            }
        }
        if (changed) {
            event.markForReEncode(true);
        }
    }

    private static @Nullable ItemStack applyMask(
            final SlotMask mask, final int vignetteStage, final ViewPackets.@Nullable MaskItems masks) {
        return switch (mask) {
            case KEEP -> null;
            case EMPTY -> ItemStack.EMPTY;
            case HIDDEN_HAND -> masks == null ? null : masks.hiddenHand().copy();
            case VIGNETTE -> masks == null ? null : masks.overlay(vignetteStage);
        };
    }

    private static void suppressSystemChat(final PacketSendEvent event, final State state) {
        final WrapperPlayServerSystemChatMessage packet = new WrapperPlayServerSystemChatMessage(event);
        if (shouldSuppressSystemChat(packet.isOverlay())
                && state.queuedChat.add(new WrapperPlayServerSystemChatMessage(false, packet.getMessage()), false)) {
            event.setCancelled(true);
        }
    }

    private static void suppressDisguisedChat(final PacketSendEvent event, final State state) {
        final WrapperPlayServerDisguisedChat packet = new WrapperPlayServerDisguisedChat(event);
        if (state.queuedChat.add(
                new WrapperPlayServerDisguisedChat(packet.getMessage(), packet.getChatFormatting()), false)) {
            event.setCancelled(true);
        }
    }

    private static void suppressPlayerChat(final PacketSendEvent event, final State state) {
        final WrapperPlayServerChatMessage packet = new WrapperPlayServerChatMessage(event);
        if (state.queuedChat.add(new WrapperPlayServerChatMessage(packet.getMessage()), true)) {
            event.setCancelled(true);
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

    /** Returns whether an inbound {@code USE_ITEM} packet on {@code hand} confirms the active dialogue step. */
    static boolean isConfirmUseItem(final PacketType.Play.Client type, final @Nullable InteractionHand hand) {
        return type == PacketType.Play.Client.USE_ITEM && hand == InteractionHand.MAIN_HAND;
    }

    /** Returns whether a system chat packet must be suppressed; action-bar overlays always pass through. */
    static boolean shouldSuppressSystemChat(final boolean overlay) {
        return !overlay;
    }

    /** Returns how window 0 {@code slot} is masked for {@code heldSlot} and {@code vignetteStage}. */
    static SlotMask windowSlotMask(final int slot, final int heldSlot, final int vignetteStage) {
        if (slot == ViewPackets.HELMET_SLOT) {
            return vignetteStage > 0 ? SlotMask.VIGNETTE : SlotMask.KEEP;
        }
        if (slot >= ViewPackets.HOTBAR_SLOT && slot < ViewPackets.HOTBAR_SLOT + ViewPackets.HOTBAR_SIZE) {
            return slot - ViewPackets.HOTBAR_SLOT == heldSlot ? SlotMask.HIDDEN_HAND : SlotMask.EMPTY;
        }
        if (slot == ViewPackets.OFF_HAND_SLOT) {
            return SlotMask.EMPTY;
        }
        return SlotMask.KEEP;
    }

    /** Returns how player-inventory {@code slot} is masked for {@code heldSlot} and {@code vignetteStage}. */
    static SlotMask playerInventorySlotMask(final int slot, final int heldSlot, final int vignetteStage) {
        if (slot == ViewPackets.PLAYER_INV_HELMET) {
            return vignetteStage > 0 ? SlotMask.VIGNETTE : SlotMask.KEEP;
        }
        if (slot >= 0 && slot < ViewPackets.HOTBAR_SIZE) {
            return slot == heldSlot ? SlotMask.HIDDEN_HAND : SlotMask.EMPTY;
        }
        if (slot == ViewPackets.PLAYER_INV_OFF_HAND) {
            return SlotMask.EMPTY;
        }
        return SlotMask.KEEP;
    }
}
