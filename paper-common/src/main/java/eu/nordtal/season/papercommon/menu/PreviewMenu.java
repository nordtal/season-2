package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.Part;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A drafted menu for an admin to look at, every click refused.
 * Its window title, then each item and label in the document's order, as paper with its tooltip. The menu's own code
 * places its slots, so a slot here stands where the document lists it, not where the menu draws it.
 */
public final class PreviewMenu extends Menu {

    private static final int MOST = MenuTitle.MAX_ROWS * SlotGeometry.COLUMNS;

    public PreviewMenu(final GameDocument document, final List<Part> parts) {
        // shortcut: a menu of more than 54 items and labels shows its first 54, none of today's has that many.
        final List<Part> placed = parts.stream()
                .filter(part ->
                        part.slot() != null && (part.type() == Part.Type.ITEM || part.type() == Part.Type.LABEL))
                .limit(MOST)
                .toList();
        final int rows = Math.max(1, Math.ceilDiv(placed.size(), SlotGeometry.COLUMNS));
        final Inventory window = frame(rows, MenuTitle.of(rows, document.title()));
        for (int index = 0; index < placed.size(); index++) {
            final Part part = placed.get(index);
            final String slot = Objects.requireNonNull(part.slot(), "slot");
            final GameDocument.Drawn drawn = part.type() == Part.Type.ITEM
                    ? document.exampleItem(slot)
                    : new GameDocument.Drawn(Component.text(document.exampleLabel(slot)), List.of());
            window.setItem(index, BlankItem.described(ItemStack.of(Material.PAPER), drawn));
        }
    }
}
