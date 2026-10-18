package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messagerendering.Lines;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * An item that draws nothing and has a tooltip, for slots under a card in a menu's title.
 * Name and lore are not italic.
 */
public final class BlankItem {

    /** The pack's empty item model. */
    public static final Key MODEL = Key.key("nordtal", "blank");

    private BlankItem() {}

    public static ItemStack of(final Component name, final List<Component> lore) {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        return described(stack, name, lore);
    }

    /** Sets a tooltip on {@code stack}. The first line of {@code name} heads it, and later lines open the lore. */
    public static ItemStack described(final ItemStack stack, final Component name, final List<Component> lore) {
        final List<Component> tooltip = Lines.tooltip(name, lore);
        stack.setData(DataComponentTypes.CUSTOM_NAME, upright(tooltip.getFirst()));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(tooltip.subList(1, tooltip.size()).stream()
                        .map(BlankItem::upright)
                        .toList()));
        return stack;
    }

    private static Component upright(final Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
