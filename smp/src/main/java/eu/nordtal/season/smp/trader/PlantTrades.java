package eu.nordtal.season.smp.trader;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

/** Adds two plant offers to a wandering trader's rolled offers. */
public final class PlantTrades implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof final WanderingTrader trader)) {
            return;
        }
        final List<MerchantRecipe> recipes = new ArrayList<>(trader.getRecipes());
        final Set<Material> existing =
                recipes.stream().map(recipe -> recipe.getResult().getType()).collect(Collectors.toSet());
        for (final Material plant : missingFrom(existing)) {
            final MerchantRecipe recipe = new MerchantRecipe(ItemStack.of(plant), 0, 12, false, 0, 0.05f);
            recipe.addIngredient(ItemStack.of(Material.EMERALD));
            recipes.add(recipe);
        }
        trader.setRecipes(recipes);
    }

    static List<Material> missingFrom(final Set<Material> existing) {
        return List.of(Material.TALL_GRASS, Material.RED_SHRUB).stream()
                .filter(plant -> !existing.contains(plant))
                .toList();
    }
}
