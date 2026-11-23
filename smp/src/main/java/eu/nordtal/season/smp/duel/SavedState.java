package eu.nordtal.season.smp.duel;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.key.InvalidKeyException;
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
    private final List<FighterStateStore.StoredEffect> effects;

    SavedState(
            final @Nullable Location location,
            final @Nullable ItemStack[] inventory,
            final @Nullable ItemStack[] armour,
            final double health,
            final int foodLevel,
            final float saturation,
            final int level,
            final float experience,
            final GameMode gameMode,
            final List<FighterStateStore.StoredEffect> effects) {
        this.location = location;
        this.inventory = inventory.clone();
        this.armour = armour.clone();
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.level = level;
        this.experience = experience;
        this.gameMode = gameMode;
        this.effects = List.copyOf(effects);
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
                player.getActivePotionEffects().stream()
                        .map(SavedState::encodeEffect)
                        .toList());
    }

    FighterStateStore.StoredState toStored() {
        return toStored(SavedState::encodeItem);
    }

    FighterStateStore.StoredState toStored(final Function<ItemStack, @Nullable String> encodeItem) {
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
                encodeItems(inventory, encodeItem),
                encodeItems(armour, encodeItem),
                health,
                foodLevel,
                saturation,
                level,
                experience,
                gameMode.name(),
                effects);
    }

    static SavedState fromStored(final FighterStateStore.StoredState stored, final Logger logger) {
        return fromStored(
                stored,
                Bukkit::getWorld,
                data -> ItemStack.deserializeBytes(Base64.getDecoder().decode(data)),
                logger);
    }

    static SavedState fromStored(
            final FighterStateStore.StoredState stored,
            final Function<String, @Nullable World> worldLookup,
            final Function<String, @Nullable ItemStack> decodeItem,
            final Logger logger) {
        final FighterStateStore.@Nullable StoredLocation saved = stored.location();
        final @Nullable World world = saved == null ? null : worldLookup.apply(saved.world());
        final @Nullable Location restoredLocation = world == null || saved == null
                ? null
                : new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        final List<FighterStateStore.StoredEffect> validEffects = new ArrayList<>();
        for (final FighterStateStore.StoredEffect effect : stored.effects()) {
            try {
                Key.key(effect.type());
                validEffects.add(effect);
            } catch (final InvalidKeyException | IllegalArgumentException invalid) {
                logger.warning("duel saved effect key invalid, skipped: type=" + effect.type());
            }
        }
        return new SavedState(
                restoredLocation,
                decodeItems(stored.inventory(), decodeItem, logger),
                decodeItems(stored.armour(), decodeItem, logger),
                stored.health(),
                stored.foodLevel(),
                stored.saturation(),
                stored.level(),
                stored.experience(),
                parseGameMode(stored.gameMode(), logger),
                validEffects);
    }

    /** A null {@code where} teleports to the saved location. */
    public void restore(final Player player, final @Nullable Location where) {
        restoreWithout(player);
        final @Nullable Location target = targetLocation(where);
        if (target != null) {
            player.teleport(target);
        }
    }

    @Nullable
    Location targetLocation(final @Nullable Location where) {
        return where != null ? where : location;
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
        for (final FighterStateStore.StoredEffect effect : effects) {
            final @Nullable PotionEffect decoded = decodeEffect(effect);
            if (decoded != null) {
                player.addPotionEffect(decoded);
            }
        }
    }

    private static @Nullable String encodeItem(final ItemStack item) {
        return item.isEmpty() ? null : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static List<@Nullable String> encodeItems(
            final @Nullable ItemStack[] items, final Function<ItemStack, @Nullable String> encodeItem) {
        final List<@Nullable String> encoded = new ArrayList<>(items.length);
        for (final @Nullable ItemStack item : items) {
            encoded.add(item == null ? null : encodeItem.apply(item));
        }
        return encoded;
    }

    private static @Nullable ItemStack[] decodeItems(
            final List<@Nullable String> encoded,
            final Function<String, @Nullable ItemStack> decodeItem,
            final Logger logger) {
        final @Nullable ItemStack[] items = new @Nullable ItemStack[encoded.size()];
        for (int i = 0; i < encoded.size(); i++) {
            final @Nullable String data = encoded.get(i);
            if (data == null || data.isBlank()) {
                continue;
            }
            try {
                items[i] = decodeItem.apply(data);
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

    private static @Nullable PotionEffect decodeEffect(final FighterStateStore.StoredEffect stored) {
        final @Nullable PotionEffectType type = Registry.MOB_EFFECT.get(Key.key(stored.type()));
        if (type == null) {
            return null;
        }
        return new PotionEffect(
                type, stored.duration(), stored.amplifier(), stored.ambient(), stored.particles(), stored.icon());
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
