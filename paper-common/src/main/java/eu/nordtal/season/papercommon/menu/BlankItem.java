package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Tooltip;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        return described(blank(), name, lore);
    }

    public static ItemStack of(final GameDocument.Drawn drawn) {
        return of(drawn.name(), drawn.lore());
    }

    /** Returns a blank item with Nordtal's tooltip: the drawn name and lore, broken at {@link Tooltip#WRAP}. */
    public static ItemStack styled(final GameDocument.Drawn drawn) {
        return styled(blank(), drawn.name(), drawn.lore());
    }

    /** Returns a blank item that shows no tooltip, for a plate whose tooltip would only repeat its label. */
    public static ItemStack silent() {
        final ItemStack stack = blank();
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    public static ItemStack described(final ItemStack stack, final GameDocument.Drawn drawn) {
        return described(stack, drawn.name(), drawn.lore());
    }

    /** Sets a tooltip on {@code stack}. The first line of {@code name} heads it, and later lines open the lore. */
    public static ItemStack described(final ItemStack stack, final Component name, final List<Component> lore) {
        return tooltip(stack, Lines.tooltip(name, lore));
    }

    /**
     * Gives {@code stack} Nordtal's tooltip, which only a display item takes, with {@code name} and {@code lore} alone.
     * Its name's rows are in the title colour, its lore's in tooltip text, broken at {@link Tooltip#WRAP}. The lines
     * the item's own components would add, such as its attributes or enchantments, are hidden.
     */
    public static ItemStack styled(final ItemStack stack, final Component name, final List<Component> lore) {
        stack.setData(DataComponentTypes.TOOLTIP_STYLE, Tooltip.STYLE);
        final Set<DataComponentType> hidden = new HashSet<>(stack.getDataTypes());
        hidden.add(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        hidden.removeAll(Set.of(DataComponentTypes.CUSTOM_NAME, DataComponentTypes.LORE));
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hiddenComponents(hidden).build());
        return tooltip(stack, rows(name, lore));
    }

    /** Gives {@code stack} Nordtal's tooltip with {@code name} and one rendered line of lore. */
    public static ItemStack styled(final ItemStack stack, final Component name, final String lore) {
        return styled(stack, name, List.of(Component.text(lore)));
    }

    /** Returns the rows of Nordtal's tooltip for {@code name} and {@code lore}, each line broken and coloured. */
    static List<Component> rows(final Component name, final List<Component> lore) {
        final List<Component> rows = new ArrayList<>();
        Lines.of(name).forEach(line -> rows.addAll(wrapped(line, Colours.TOOLTIP_TITLE)));
        lore.forEach(text -> Lines.of(text).forEach(line -> rows.addAll(wrapped(line, Colours.TOOLTIP_TEXT))));
        return List.copyOf(rows);
    }

    /** Breaks a line of plain text at the wrap; a translated line stays whole, as only the client knows its width. */
    private static List<Component> wrapped(final Component line, final TextColor colour) {
        if (!plain(line)) {
            return List.of(row(bare(line), colour));
        }
        return Letters.wrap(PlainTextComponentSerializer.plainText().serialize(line), Tooltip.WRAP).stream()
                .map(text -> row(Component.text(text), colour))
                .toList();
    }

    private static boolean plain(final Component component) {
        return component instanceof TextComponent
                && component.children().stream().allMatch(BlankItem::plain);
    }

    /** Drops the style of {@code component} and its children, so the row's colour holds throughout. */
    private static Component bare(final Component component) {
        return component
                .style(Style.empty())
                .children(component.children().stream().map(BlankItem::bare).toList());
    }

    private static Component row(final Component text, final TextColor colour) {
        return text.color(colour).shadowColor(ShadowColor.none());
    }

    private static ItemStack tooltip(final ItemStack stack, final List<Component> lines) {
        stack.setData(DataComponentTypes.CUSTOM_NAME, upright(lines.getFirst()));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(lines.subList(1, lines.size()).stream()
                        .map(BlankItem::upright)
                        .toList()));
        return stack;
    }

    private static ItemStack blank() {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        return stack;
    }

    private static Component upright(final Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
