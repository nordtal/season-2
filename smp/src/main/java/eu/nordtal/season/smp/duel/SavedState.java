package eu.nordtal.season.smp.duel;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/** A fighter's pre-duel state, kept in memory and persisted by {@link FighterStateStore} until restored. */
public final class SavedState {

    private final @Nullable Location location;
    private final @Nullable ItemStack[] inventory;
    private final @Nullable ItemStack[] armour;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final int level;
    private final float experience;
    private final GameMode gameMode;
    private final Collection<PotionEffect> effects;

    private SavedState(
            final @Nullable Location location,
            final @Nullable ItemStack[] inventory,
            final @Nullable ItemStack[] armour,
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

    /** Closes the player's screen first, so the cursor and the crafting grid go back into the saved inventory. */
    public static SavedState of(final Player player) {
        player.closeInventory();
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

    FighterStateStore.StoredState toStored() {
        final FighterStateStore.@Nullable StoredLocation storedLocation =
                location == null || location.getWorld() == null
                        ? null
                        : new FighterStateStore.StoredLocation(
                                location.getWorld().getName(),
                                location.getX(),
                                location.getY(),
                                location.getZ(),
                                location.getYaw(),
                                location.getPitch());
        return new FighterStateStore.StoredState(
                storedLocation,
                encodeItems(inventory),
                encodeItems(armour),
                health,
                foodLevel,
                saturation,
                level,
                experience,
                gameMode.name(),
                effects.stream().map(SavedState::encodeEffect).toList());
    }

    static SavedState fromStored(final FighterStateStore.StoredState stored, final Logger logger) {
        final FighterStateStore.@Nullable StoredLocation saved = stored.location();
        final @Nullable World world = saved == null ? null : Bukkit.getWorld(saved.world());
        final @Nullable Location restoredLocation = world == null || saved == null
                ? null
                : new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        final List<PotionEffect> restoredEffects = new ArrayList<>();
        for (final FighterStateStore.StoredEffect effect : stored.effects()) {
            final @Nullable PotionEffect decoded = decodeEffect(effect, logger);
            if (decoded != null) {
                restoredEffects.add(decoded);
            }
        }
        return new SavedState(
                restoredLocation,
                decodeItems(stored.inventory(), logger),
                decodeItems(stored.armour(), logger),
                stored.health(),
                stored.foodLevel(),
                stored.saturation(),
                stored.level(),
                stored.experience(),
                parseGameMode(stored.gameMode(), logger),
                restoredEffects);
    }

    /** A null {@code where} teleports to the saved location. */
    public void restore(final Player player, final @Nullable Location where) {
        restoreWithout(player);
        final @Nullable Location target = where != null ? where : location;
        if (target != null) {
            player.teleport(target);
        }
    }

    public void restore(final Player player) {
        restore(player, null);
    }

    private void restoreWithout(final Player player) {
        emptyScreen(player);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.getInventory().clear();
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

    private static List<@Nullable String> encodeItems(final @Nullable ItemStack[] items) {
        final List<@Nullable String> encoded = new ArrayList<>(items.length);
        for (final @Nullable ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                encoded.add(null);
            } else {
                encoded.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
        }
        return encoded;
    }

    private static @Nullable ItemStack[] decodeItems(final List<@Nullable String> encoded, final Logger logger) {
        final @Nullable ItemStack[] items = new @Nullable ItemStack[encoded.size()];
        for (int i = 0; i < encoded.size(); i++) {
            final @Nullable String data = encoded.get(i);
            if (data == null || data.isBlank()) {
                continue;
            }
            try {
                items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
            } catch (final RuntimeException unreadable) {
                logger.warning("duel saved item unreadable, slot left empty: slot=" + i);
            }
        }
        return items;
    }

    private static FighterStateStore.StoredEffect encodeEffect(final PotionEffect effect) {
        return new FighterStateStore.StoredEffect(
                effect.getType().getKey().toString(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon());
    }

    private static @Nullable PotionEffect decodeEffect(
            final FighterStateStore.StoredEffect stored, final Logger logger) {
        try {
            final @Nullable PotionEffectType type = Registry.MOB_EFFECT.get(Key.key(stored.type()));
            if (type == null) {
                logger.warning("duel saved effect unknown, skipped: type=" + stored.type());
                return null;
            }
            return new PotionEffect(
                    type, stored.duration(), stored.amplifier(), stored.ambient(), stored.particles(), stored.icon());
        } catch (final IllegalArgumentException invalid) {
            logger.warning("duel saved effect key invalid, skipped: type=" + stored.type());
            return null;
        }
    }

    private static GameMode parseGameMode(final @Nullable String name, final Logger logger) {
        if (name == null) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(name);
        } catch (final IllegalArgumentException invalid) {
            logger.warning("duel saved game mode unknown, using survival: mode=" + name);
            return GameMode.SURVIVAL;
        }
    }

    /** Deletes the cursor and any crafting grid, so no kit item outlasts the duel, then closes the screen. */
    private static void emptyScreen(final Player player) {
        if (player.getOpenInventory().getTopInventory() instanceof final CraftingInventory grid) {
            grid.clear();
        }
        player.setItemOnCursor(null);
        player.closeInventory();
    }

    public static void clear(final Player player) {
        emptyScreen(player);
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
