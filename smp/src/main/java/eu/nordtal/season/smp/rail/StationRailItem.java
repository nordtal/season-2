package eu.nordtal.season.smp.rail;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The station rail as an item: a rail with the pack's station model, a name from the pack and a marker.
 * It is crafted from six rails around a compass.
 */
final class StationRailItem {

    /** The pack's model of the station rail, drawn on the item and on the placed rail. */
    static final Key MODEL = Key.key("nordtal", "station_rail");

    /** The key of the item's name in the pack's language files. */
    static final String NAME = "item.nordtal.station_rail";

    private final NamespacedKey marker;
    private final NamespacedKey recipe;

    StationRailItem(final Plugin plugin) {
        this.marker = new NamespacedKey(plugin, "station_rail");
        this.recipe = new NamespacedKey(plugin, "station_rail");
    }

    ItemStack create() {
        final ItemStack stack = ItemStack.of(Material.RAIL);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        stack.setData(DataComponentTypes.ITEM_NAME, Component.translatable(NAME, "Station Rail"));
        stack.editPersistentDataContainer(data -> data.set(marker, PersistentDataType.BOOLEAN, true));
        return stack;
    }

    boolean is(final ItemStack stack) {
        return stack.getType() == Material.RAIL
                && stack.getPersistentDataContainer().has(marker, PersistentDataType.BOOLEAN);
    }

    NamespacedKey recipeKey() {
        return recipe;
    }

    ShapedRecipe recipe() {
        return new ShapedRecipe(recipe, create())
                .shape("R R", "RCR", "R R")
                .setIngredient('R', Material.RAIL)
                .setIngredient('C', Material.COMPASS);
    }
}
