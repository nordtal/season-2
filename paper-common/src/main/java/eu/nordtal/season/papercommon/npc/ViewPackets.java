package eu.nordtal.season.papercommon.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import eu.nordtal.season.packrendering.npc.DialogueScreen;
import eu.nordtal.season.papercommon.menu.BlankItem;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a dialogue sends one player's client alone: a turn of the view, the vignette and empty hands.
 * The vignette stands in place of the helmet without mutating the player's server-side inventory.
 */
final class ViewPackets {

    /** The helmet's slot in the player's own inventory window. */
    static final int HELMET_SLOT = 5;

    /** The first hotbar slot in the player's own inventory window. */
    static final int HOTBAR_SLOT = 36;

    /** The number of hotbar slots in the player's inventory. */
    static final int HOTBAR_SIZE = 9;

    /** The off hand's slot in the player's own inventory window. */
    static final int OFF_HAND_SLOT = 45;

    /** The helmet's slot in a player-inventory packet. */
    static final int PLAYER_INV_HELMET = 39;

    /** The off hand's slot in a player-inventory packet. */
    static final int PLAYER_INV_OFF_HAND = 40;

    /** Prebuilt protocol item stacks used on the Netty thread to mask outbound inventory packets. */
    record MaskItems(
            com.github.retrooper.packetevents.protocol.item.ItemStack hiddenHand,
            List<com.github.retrooper.packetevents.protocol.item.ItemStack> overlays) {

        com.github.retrooper.packetevents.protocol.item.@Nullable ItemStack overlay(final int stage) {
            if (stage < 1 || stage > overlays.size()) {
                return null;
            }
            return overlays.get(stage - 1).copy();
        }
    }

    private ViewPackets() {}

    /** Builds protocol-level hidden hand and vignette stacks on the main thread for packet masking. */
    static MaskItems maskItems() {
        final com.github.retrooper.packetevents.protocol.item.ItemStack hand =
                SpigotConversionUtil.fromBukkitItemStack(hiddenHand());
        final List<com.github.retrooper.packetevents.protocol.item.ItemStack> stages = IntStream.rangeClosed(
                        1, DialogueScreen.STAGES)
                .mapToObj(stage -> SpigotConversionUtil.fromBukkitItemStack(overlay(stage)))
                .toList();
        return new MaskItems(hand, stages);
    }

    /** Runs {@code task} inline on {@code player}'s Netty event loop when available, so chat replay stays ordered. */
    static void runOnNetty(final Player player, final Runnable task) {
        final Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        if (channel instanceof final io.netty.channel.Channel netty
                && !netty.eventLoop().inEventLoop()) {
            netty.eventLoop().execute(task);
        } else {
            task.run();
        }
    }

    /** Sends {@code packet} to {@code player} without triggering outbound PacketEvents listeners. */
    static void sendSilently(final Player player, final PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
    }

    /** Turns the view by the angles, on top of what the mouse does. */
    static void turn(final Player player, final CameraEase.Turn turn) {
        sendSilently(player, new WrapperPlayServerPlayerRotation((float) turn.yaw(), true, (float) turn.pitch(), true));
    }

    /** Shows vignette {@code stage} from 1 to 4 over the screen; 0 shows the helmet the player really wears. */
    static void vignette(final Player player, final int stage) {
        final ItemStack shown = stage == 0 ? helmet(player) : overlay(stage);
        slot(player, HELMET_SLOT, shown);
    }

    /**
     * Hides the entire hotbar and off hand via packets alone so neither hand shows an item or arm.
     * The active slot holds {@link BlankItem#silent()}, the other eight hotbar slots and off hand hold air.
     */
    static void hideHands(final Player player, final int held) {
        for (int index = 0; index < HOTBAR_SIZE; index++) {
            slot(player, HOTBAR_SLOT + index, index == held ? hiddenHand() : ItemStack.empty());
        }
        slot(player, OFF_HAND_SLOT, ItemStack.empty());
    }

    /** Returns an invisible hand item with an empty custom name so the hotbar name banner never flashes "Paper". */
    static ItemStack hiddenHand() {
        final ItemStack item = BlankItem.silent();
        item.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return item;
    }

    /** Clears any client-predicted item on the cursor without touching server inventory. */
    static void clearCursor(final Player player) {
        sendSilently(
                player,
                new WrapperPlayServerSetSlot(-1, 0, -1, SpigotConversionUtil.fromBukkitItemStack(ItemStack.empty())));
    }

    /** Resyncs the player's real hotbar, off hand, helmet and full inventory back to the client. */
    static void resyncInventory(final Player player) {
        for (int index = 0; index < HOTBAR_SIZE; index++) {
            final ItemStack real = player.getInventory().getItem(index);
            slot(player, HOTBAR_SLOT + index, real == null ? ItemStack.empty() : real);
        }
        slot(player, OFF_HAND_SLOT, player.getInventory().getItemInOffHand());
        slot(player, HELMET_SLOT, helmet(player));
        player.updateInventory();
    }

    private static void slot(final Player player, final int slot, final ItemStack shown) {
        sendSilently(player, new WrapperPlayServerSetSlot(0, 0, slot, SpigotConversionUtil.fromBukkitItemStack(shown)));
    }

    private static ItemStack helmet(final Player player) {
        final ItemStack worn = player.getInventory().getHelmet();
        return worn == null ? ItemStack.empty() : worn;
    }

    /**
     * Builds the invisible vignette helmet item for {@code stage}.
     * Uses {@link BlankItem#MODEL} so it renders no paper texture while keeping its {@code cameraOverlay} intact.
     */
    static ItemStack overlay(final int stage) {
        final ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, BlankItem.MODEL);
        item.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        item.setData(
                DataComponentTypes.EQUIPPABLE,
                Equippable.equippable(EquipmentSlot.HEAD)
                        .cameraOverlay(Key.key("nordtal", "npc/vignette_" + stage))
                        .build());
        return item;
    }
}
