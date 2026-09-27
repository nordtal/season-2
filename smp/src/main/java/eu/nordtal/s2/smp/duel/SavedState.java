package eu.nordtal.s2.smp.duel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;

/**
 * Everything a duel borrows from a player, so that all of it can be given back.
 *
 * Not persisted, and not a record: a record would hand out the saved arrays themselves.
 */
public final class SavedState {

    private final Location location;
    private final ItemStack[] inventory;
    private final ItemStack[] armour;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final int level;
    private final float experience;
    private final GameMode gameMode;
    private final Collection<PotionEffect> effects;

    private SavedState(
            final Location location,
            final ItemStack[] inventory,
            final ItemStack[] armour,
            final double health,
            final int foodLevel,
            final float saturation,
            final int level,
            final float experience,
            final GameMode gameMode,
            final Collection<PotionEffect> effects) {
        this.location = location;
        this.inventory = inventory.clone();
        this.armour = armour.clone();
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.level = level;
        this.experience = experience;
        this.gameMode = gameMode;
        this.effects = effects;
    }

    public static SavedState of(final Player player) {
        return new SavedState(
                Objects.requireNonNull(player.getLocation()),
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

    /**
     * Puts a player back as they were, except at {@code where} instead of where they stood.
     *
     * @param player the fighter
     * @param where where to put them
     */
    public void restore(final Player player, final @Nullable Location where) {
        restoreWithout(player);
        player.teleport(where == null ? location : where);
    }

    /** Puts a player back exactly as they were. */
    public void restore(final Player player) {
        restoreWithout(player);
        player.teleport(location);
    }

    private void restoreWithout(final Player player) {
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
