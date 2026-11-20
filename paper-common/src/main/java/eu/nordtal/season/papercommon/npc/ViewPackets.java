package eu.nordtal.season.papercommon.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import eu.nordtal.season.papercommon.menu.BlankItem;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * What a dialogue sends one player's client alone: a turn of the view, the vignette and empty hands.
 * The vignette stands in place of the helmet.
 */
final class ViewPackets {

    /** The helmet's slot in the player's own inventory window. */
    static final int HELMET_SLOT = 5;

    /** The first hotbar slot in the player's own inventory window. */
    static final int HOTBAR_SLOT = 36;

    /** The off hand's slot in the player's own inventory window. */
    static final int OFF_HAND_SLOT = 45;

    private ViewPackets() {}

    /** Turns the view by the angles, on top of what the mouse does. */
    static void turn(final Player player, final CameraEase.Turn turn) {
        PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(
                        player,
                        new WrapperPlayServerPlayerRotation((float) turn.yaw(), true, (float) turn.pitch(), true));
    }

    /** Shows vignette {@code stage} from 1 to 4 over the screen; 0 shows the helmet the player really wears. */
    static void vignette(final Player player, final int stage) {
        final ItemStack shown = stage == 0 ? helmet(player) : overlay(stage);
        PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(
                        player,
                        new WrapperPlayServerSetSlot(
                                0, 0, HELMET_SLOT, SpigotConversionUtil.fromBukkitItemStack(shown)));
    }

    /**
     * Shows hotbar slot {@code held} and the off hand empty, so the first-person view shows no item in either hand.
     * The main hand holds an item that draws nothing, since an empty one shows the arm.
     */
    static void hideHands(final Player player, final int held) {
        slot(player, HOTBAR_SLOT + held, BlankItem.silent());
        slot(player, OFF_HAND_SLOT, ItemStack.empty());
    }

    /** Shows hotbar slot {@code held} and the off hand with what the player really holds there. */
    static void showHands(final Player player, final int held) {
        final ItemStack main = player.getInventory().getItem(held);
        slot(player, HOTBAR_SLOT + held, main == null ? ItemStack.empty() : main);
        slot(player, OFF_HAND_SLOT, player.getInventory().getItemInOffHand());
    }

    private static void slot(final Player player, final int slot, final ItemStack shown) {
        PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(
                        player,
                        new WrapperPlayServerSetSlot(0, 0, slot, SpigotConversionUtil.fromBukkitItemStack(shown)));
    }

    private static ItemStack helmet(final Player player) {
        final ItemStack worn = player.getInventory().getHelmet();
        return worn == null ? ItemStack.empty() : worn;
    }

    private static ItemStack overlay(final int stage) {
        final ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(
                DataComponentTypes.EQUIPPABLE,
                Equippable.equippable(EquipmentSlot.HEAD)
                        .cameraOverlay(Key.key("nordtal", "npc/vignette_" + stage))
                        .build());
        return item;
    }
}
