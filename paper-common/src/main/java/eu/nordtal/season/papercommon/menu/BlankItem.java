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
 * An item that draws nothing and carries a tooltip, for slots under a card painted into a menu's title.
 *
 * The name and lore are set non-italic explicitly, since a custom name renders italic otherwise.
 */
public final class BlankItem {

    /** The pack's empty item model. */
    public static final Key MODEL = Key.key("nordtal", "blank");

    private BlankItem() {}

    /**
     * @param name what the tooltip is headed with
     * @param lore the lines under it, already translated and coloured; may be empty
     * @return a fresh stack
     */
    public static ItemStack of(final Component name, final List<Component> lore) {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        return described(stack, name, lore);
    }

    /**
     * Gives {@code stack} a tooltip with one line per line of its texts.
     * The name's first line heads it and its later lines open the lore, since a name and a lore line hold one line.
     *
     * @return {@code stack}
     */
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
