package eu.nordtal.season.hungergames.loot;

import static eu.nordtal.season.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.hungergames.border.BorderController;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
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
    private final MessageRenderer renderer;
    private final Identities identities;
    private final HungerGamesSounds sounds;

    private final List<Scheduler.Task> scheduled = new ArrayList<>();

    /** Set by {@link #scheduleAll}, cleared by {@link #cancelAll}; null while no game is running. */
    private volatile @Nullable Instant releasedAt;

    private final Clock clock;

    public LootRefill(
            final Plugin plugin,
            final World world,
            final HungerGamesSpec config,
            final BorderController border,
            final MessageRenderer renderer,
            final Identities identities,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.border = border;
        this.renderer = renderer;
        this.identities = identities;
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
            // A tier already due by now refills at once.
            final Duration remaining =
                    Duration.ofMinutes(tier.delayMinutes()).minus(Duration.between(releasedAt, clock.instant()));
            scheduled.add(PaperScheduler.of(plugin).onMainAfter(remaining, () -> refill(tier)));
        }
    }

    public void cancelAll() {
        releasedAt = null;
        for (final Scheduler.Task task : scheduled) {
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
                final Material material = GameKeys.material(materialName).orElse(null);
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
            player.sendMessage(renderer.format(
                    identities.languageOf(player.getUniqueId()),
                    MESSAGES.hg().loot().refill()));
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }
}
