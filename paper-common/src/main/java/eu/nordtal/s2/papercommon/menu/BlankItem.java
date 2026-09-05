package eu.nordtal.s2.papercommon.menu;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * An item that draws nothing and carries a tooltip - what sits under a card painted into a menu's
 * title.
 *
 * <p>A menu whose surface is a glyph in the inventory title ({@code docs/presentation.md}
 * section 2) still needs an item in every slot a player can hover or click: the tooltip is the
 * item's name and lore, and a click on an empty slot is a click on nothing. A vanilla item would
 * draw its icon over the art, so this one selects the pack's {@code nordtal:blank} model - an
 * {@code item_model} component pointing at a model of type {@code minecraft:empty}, which renders
 * no pixels at all. The material underneath is paper and does not matter; nothing here is ever
 * picked up, because the menus that use it cancel every click.</p>
 *
 * <p>The name and every lore line are set non-italic explicitly: a custom name renders in italics
 * unless told otherwise, and on a card that is not a label but a caption.</p>
 */
public final class BlankItem {

    /** The pack's empty item model - {@code resource-pack/src/assets/nordtal/items/blank.json}. */
    public static final Key MODEL = Key.key("nordtal", "blank");

    private BlankItem() {
    }

    /**
     * @param name what the tooltip is headed with
     * @param lore the lines under it, already translated and coloured; may be empty
     * @return a fresh stack - callers that fill several slots may share one instance
     */
    public static ItemStack of(final Component name, final List<Component> lore) {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        stack.setData(DataComponentTypes.CUSTOM_NAME, upright(name));
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore.stream().map(BlankItem::upright).toList()));
        return stack;
    }

    private static Component upright(final Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
