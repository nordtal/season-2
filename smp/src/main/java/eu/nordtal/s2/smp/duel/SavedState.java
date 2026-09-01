package eu.nordtal.s2.smp.duel;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Everything a duel borrows from a player, so that all of it can be given back.
 *
 * <p>The arena's inventory, health, effects and experience are the duel's own; the player's real
 * state is untouched (docs/smp.md#duels). "Untouched" is a promise that has to be kept in both
 * directions, which is what this record is: taken in one place, restored in one place, with nothing
 * left to remember.
 *
 * <p>It is deliberately not persisted. A duel is a single short fight that nothing has to survive a
 * restart for - and a saved state that outlived a restart would be a second copy of somebody's
 * inventory sitting in a file, which is a far worse failure than a duel that was interrupted.
 */
public record SavedState(Location location, ItemStack[] inventory, ItemStack[] armour,
                         double health, int foodLevel, float saturation, int level, float experience,
                         GameMode gameMode, Collection<PotionEffect> effects) {

    public static SavedState of(final Player player) {
        return new SavedState(
                player.getLocation(),
                player.getInventory().getContents().clone(),
                player.getInventory().getArmorContents().clone(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getExp(),
                player.getGameMode(),
                new ArrayList<>(player.getActivePotionEffects()));
    }

    /** Puts a player back exactly as they were. */
    public void restore(final Player player) {
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.getInventory().setContents(inventory);
        player.getInventory().setArmorContents(armour);

        final var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        final double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.min(Math.max(health, 0.5), cap));

        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setLevel(level);
        player.setExp(experience);
        player.setGameMode(gameMode);
        player.setFireTicks(0);
        effects.forEach(player::addPotionEffect);
        player.teleport(location);
    }

    /** Empties a player out for the arena, leaving them ready for a loadout. */
    public static void clear(final Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        final var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setLevel(0);
        player.setExp(0f);
        player.setFireTicks(0);
        player.setGameMode(GameMode.SURVIVAL);
    }
}
