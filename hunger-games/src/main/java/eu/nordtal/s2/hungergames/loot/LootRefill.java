package eu.nordtal.s2.hungergames.loot;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules the loot refills configured under {@code refill-tiers}.
 *
 * Each refill clears and restocks the chest of every loot point still inside the border.
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
    private volatile @Nullable Instant releasedAt;

    private final Clock clock;

    public LootRefill(
            final Plugin plugin,
            final World world,
            final HungerGamesSpec config,
            final BorderController border,
            final Messages messages,
            final PlayerLocales locales,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.border = border;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /** When the next refill is due, or {@code null} when none is; derived for the HUD, so it cannot go stale. */
    public @Nullable Instant nextRefillAt() {
        final Instant released = releasedAt;
        if (released == null) {
            return null;
        }
        final Instant now = clock.instant();
        @Nullable Instant soonest = null;
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
            final long elapsedTicks =
                    java.time.Duration.between(releasedAt, clock.instant()).toSeconds() * 20L;
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
                continue;
            }

            final Block block = location.getBlock();
            if (!(block.getState() instanceof Chest chest)) {
                LOGGER.warn(
                        "Loot point '{}' at {} is not a chest (found {}) - skipping refill",
                        point.label(),
                        location,
                        block.getType());
                continue;
            }

            final Inventory inventory = chest.getBlockInventory();
            inventory.clear();
            for (final String materialName : tier.items()) {
                final Material material = Material.matchMaterial(materialName);
                if (material == null) {
                    LOGGER.warn(
                            "Refill tier at {} minutes has unknown material '{}' - already "
                                    + "validated at load, this should be unreachable",
                            tier.delayMinutes(),
                            materialName);
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

    /** {@code NETWORK_EVENT}, and not chat alone: the one announcement here a player is expected to act on. */
    private void announce() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages)
                    .format(
                            locales.of(player.getUniqueId()),
                            MESSAGES.hg().loot().refill()));
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }
}
