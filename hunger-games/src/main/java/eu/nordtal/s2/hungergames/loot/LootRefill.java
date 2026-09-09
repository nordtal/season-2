package eu.nordtal.s2.hungergames.loot;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;

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
 * loot point still inside the border is restocked. The chest block itself is part of the hand-built
 * world and is never replaced - only its inventory is cleared and repopulated.
 */
public final class LootRefill {

    private static final Logger LOGGER = LoggerFactory.getLogger(LootRefill.class);

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final BorderController border;
    private final Messages messages;
    private final PlayerLocales locales;
    private final HungerGamesSounds sounds;

    private final List<BukkitTask> scheduled = new ArrayList<>();

    /** Set by {@link #scheduleAll}, cleared by {@link #cancelAll}; null while no game is running. */
    private volatile Instant releasedAt;

    public LootRefill(final Plugin plugin, final World world, final HungerGamesSpec config,
                      final BorderController border, final Messages messages, final PlayerLocales locales,
                      final HungerGamesSounds sounds) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.border = border;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /**
     * When the next refill is due, or {@code null} when none is - read by the HUD's second line.
     * Derived rather than pushed, so it cannot go stale.
     */
    public Instant nextRefillAt() {
        final Instant released = releasedAt;
        if (released == null) {
            return null;
        }
        final Instant now = Instant.now();
        Instant soonest = null;
        for (final HungerGamesSpec.RefillTierSpec tier : config.refillTiers()) {
            final Instant due = released.plusSeconds(tier.delayMinutes() * 60L);
            if (due.isAfter(now) && (soonest == null || due.isBefore(soonest))) {
                soonest = due;
            }
        }
        return soonest;
    }

    /** Schedules every configured tier's refill, relative to the moment the game was released. */
    public void scheduleAll(final Instant releasedAt) {
        this.releasedAt = releasedAt;
        for (final HungerGamesSpec.RefillTierSpec tier : config.refillTiers()) {
            final long delayTicks = tier.delayMinutes() * 60L * 20L;
            final long elapsedTicks = java.time.Duration.between(releasedAt, Instant.now()).toSeconds() * 20L;
            final long remainingTicks = Math.max(0, delayTicks - elapsedTicks);

            final BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> refill(tier), remainingTicks);
            scheduled.add(task);
        }
    }

    public void cancelAll() {
        releasedAt = null;
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
                // A point the border has cut off is simply gone.
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

    /**
     * {@code NETWORK_EVENT}: it happens to the whole world at once, nobody caused it, and it is the
     * one announcement here a player is expected to act on - so it is not chat alone.
     */
    private void announce() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()), "hg.loot.refill"));
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }
}
