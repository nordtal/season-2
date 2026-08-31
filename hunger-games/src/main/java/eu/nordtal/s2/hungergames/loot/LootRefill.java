package eu.nordtal.s2.hungergames.loot;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Schedules the loot refills configured under {@code refill-tiers}: at each tier's delay, every
 * loot point still inside the border is restocked - docs/hunger-games.md#loot.
 * <p>
 * "Restocking the same chest positions" (docs/hunger-games.md#loot) is implemented with
 * {@code Inventory#clear()} followed by populating it, never by replacing the block - the chest
 * itself is part of the hand-built world and is never touched.
 * </p>
 */
public final class LootRefill {

    private static final Logger LOGGER = LoggerFactory.getLogger(LootRefill.class);

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final BorderController border;
    private final Messages messages;
    private final PlayerLocales locales;

    private final List<BukkitTask> scheduled = new ArrayList<>();

    public LootRefill(final Plugin plugin, final World world, final HungerGamesSpec config,
                      final BorderController border, final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.border = border;
        this.messages = messages;
        this.locales = locales;
    }

    /** Schedules every configured tier's refill, relative to the moment the game was released. */
    public void scheduleAll(final Instant releasedAt) {
        for (final HungerGamesSpec.RefillTierSpec tier : config.refillTiers()) {
            final long delayTicks = tier.delayMinutes() * 60L * 20L;
            final long elapsedTicks = java.time.Duration.between(releasedAt, Instant.now()).toSeconds() * 20L;
            final long remainingTicks = Math.max(0, delayTicks - elapsedTicks);

            final BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> refill(tier), remainingTicks);
            scheduled.add(task);
        }
    }

    public void cancelAll() {
        for (final BukkitTask task : scheduled) {
            task.cancel();
        }
        scheduled.clear();
    }

    private void refill(final HungerGamesSpec.RefillTierSpec tier) {
        int restocked = 0;
        for (final HungerGamesSpec.LootPointSpec point : config.lootPoints()) {
            final Location location = new Location(world, point.x(), point.y(), point.z());
            if (!border.isInside(location)) {
                // "A point that has been cut off by the border is simply gone" - docs/hunger-games.md#loot.
                continue;
            }

            final Block block = location.getBlock();
            if (!(block.getState() instanceof Chest chest)) {
                LOGGER.warn("Loot point '{}' at {} is not a chest (found {}) - skipping refill",
                        point.label(), location, block.getType());
                continue;
            }

            final Inventory inventory = chest.getBlockInventory();
            inventory.clear();
            for (final String materialName : tier.items()) {
                final Material material = Material.matchMaterial(materialName);
                if (material == null) {
                    LOGGER.warn("Refill tier at {} minutes has unknown material '{}' - already "
                            + "validated at load, this should be unreachable", tier.delayMinutes(), materialName);
                    continue;
                }
                inventory.addItem(new ItemStack(material));
            }
            restocked++;
        }

        if (restocked > 0) {
            announce();
        }
    }

    private void announce() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(Component.text(messages.get(locales.of(player.getUniqueId()), "hg.loot.refill")));
        }
    }
}
