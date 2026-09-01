package eu.nordtal.s2.smp.farm;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.pregen.PreGenerator;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The daily farm-world reset: when it happens, who is told, and what happens if tomorrow's world is
 * not ready.
 *
 * <p>The mechanics are {@link FarmWorldSwap}'s; this class owns the clock and the decisions. Two of
 * those decisions are the reason the reset is bearable at all:
 *
 * <ul>
 *   <li><b>Only players in the farm world are affected.</b> They are moved to the Nordtal spawn -
 *       not to {@code limbo} - and the rest of the server never notices anything happened.</li>
 *   <li><b>A pre-generation that is not finished postpones the reset.</b> It never swaps in a
 *       half-built world. A reset that keeps postponing itself is not a fault report: it is the
 *       configured farm world being too big for this host, and the fix is a smaller radius.</li>
 * </ul>
 *
 * <p>Everything in the farm world is destroyed by this, chests and graves and POIs included. That is
 * intended, it is announced, and it will be reported as a bug anyway.
 */
public final class FarmWorldReset {

    private final Plugin plugin;
    private final SmpSpec config;
    private final Worlds worlds;
    private final FarmWorldSwap swap;
    private final PreGenerator pregen;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpDao dao;
    private final Navigation navigation;
    private final DailySchedule schedule;

    private final List<BukkitTask> pending = new ArrayList<>();
    private volatile boolean swapping;

    public FarmWorldReset(final Plugin plugin, final SmpSpec config, final Worlds worlds,
                          final FarmWorldSwap swap, final PreGenerator pregen,
                          final Messages messages, final PlayerLocales locales,
                          final SmpDao dao, final Navigation navigation) {
        this.plugin = plugin;
        this.config = config;
        this.worlds = worlds;
        this.swap = swap;
        this.pregen = pregen;
        this.messages = messages;
        this.locales = locales;
        this.dao = dao;
        this.navigation = navigation;
        this.schedule = DailySchedule.parse(config.farmResetTime());
    }

    /**
     * Cleans up after any interrupted previous run, makes sure tomorrow's world is being built, and
     * arms the clock.
     */
    public void start() {
        swap.cleanRetired();
        ensureStaging();
        scheduleNext();
        plugin.getLogger().info("the farm world resets daily at " + schedule
                + "; warnings at " + config.farmResetWarningMinutes() + " minutes");
    }

    public void stop() {
        pending.forEach(BukkitTask::cancel);
        pending.clear();
    }

    /** Runs the reset immediately, for {@code /smp farmreset now}. */
    public void resetNow() {
        performReset();
    }

    // ------------------------------------------------------------------ the clock

    private void scheduleNext() {
        stop();
        final Duration untilReset = schedule.until(LocalTime.now());

        for (final Duration ahead : DailySchedule.warningsBefore(config.farmResetWarningMinutes())) {
            final Duration untilWarning = untilReset.minus(ahead);
            if (untilWarning.isNegative()) {
                // This warning is already in the past for today's reset - the plugin started
                // between it and the reset itself. Skipping is correct; announcing "in 30 minutes"
                // five minutes beforehand would be a lie.
                continue;
            }
            final long minutes = ahead.toMinutes();
            pending.add(Bukkit.getScheduler().runTaskLater(plugin,
                    () -> warn(minutes), ticks(untilWarning)));
        }

        pending.add(Bukkit.getScheduler().runTaskLater(plugin, this::performReset, ticks(untilReset)));
    }

    private static long ticks(final Duration duration) {
        return Math.max(1L, duration.toSeconds() * 20L);
    }

    private void warn(final long minutes) {
        forEachInFarmWorld(player -> {
            final Locale locale = locales.of(player.getUniqueId());
            player.sendMessage(Component.text(
                    messages.format(locale, "smp.farm.warning", "minutes", minutes)));
        });
    }

    // ------------------------------------------------------------------ the reset

    private void performReset() {
        if (swapping) {
            return;
        }

        if (!readyToSwap()) {
            plugin.getLogger().warning("tomorrow's farm world is not finished - the reset is "
                    + "postponed rather than swapping in a half-built world. If this repeats, the "
                    + "configured farm world is too big for this host and the border is the number "
                    + "to lower.");
            forEachInFarmWorld(player -> player.sendMessage(Component.text(
                    messages.get(locales.of(player.getUniqueId()), "smp.farm.postponed"))));
            ensureStaging();
            scheduleNext();
            return;
        }

        swapping = true;
        try {
            final World nordtal = worlds.world(WorldRole.NORDTAL).orElse(null);
            if (nordtal == null) {
                plugin.getLogger().severe("Nordtal is not loaded - the farm world reset cannot move "
                        + "anybody to safety and is skipped");
                return;
            }

            final Location spawn = nordtal.getSpawnLocation();
            forEachInFarmWorld(player -> {
                player.teleport(spawn);
                player.sendMessage(Component.text(
                        messages.get(locales.of(player.getUniqueId()), "smp.farm.moved")));
            });

            swap.swap().ifPresent(this::settleNewWorld);
        } finally {
            swapping = false;
            ensureStaging();
            scheduleNext();
        }
    }

    /**
     * Whether tomorrow's world exists and Chunky has stopped working on it.
     *
     * <p>Both halves matter: a folder on its own could be a run that was cancelled halfway, and a
     * finished Chunky task with no folder is a world somebody deleted by hand.
     */
    private boolean readyToSwap() {
        return swap.stagingExists() && !pregen.isRunning(swap.stagingName());
    }

    private void settleNewWorld(final World farm) {
        farm.getWorldBorder().setCenter(0, 0);
        farm.getWorldBorder().setSize(config.farmWorldBorderDiameter());

        final Location landing = LandingSite.find(farm);
        farm.setSpawnLocation(landing);

        // Everything in the farm world is gone, and the things that merely POINT at it have to go
        // too. A point of interest naming terrain that no longer exists, or an arrow still confident
        // about a grave, is worse than nothing: it is wrong and it looks right.
        navigation.clearWorld(farm.getName());
        final String world = farm.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final int removed = dao.deletePoisIn(world);
            if (removed > 0) {
                plugin.getLogger().info("the farm world reset removed " + removed
                        + " point(s) of interest that pointed into it");
            }
        });
        plugin.getLogger().info("the farm world was replaced; the new arrival point is "
                + landing.getBlockX() + "/" + landing.getBlockY() + "/" + landing.getBlockZ());
    }

    /** Starts building tomorrow's world if it is not already there or already being built. */
    private void ensureStaging() {
        if (pregen.isRunning(swap.stagingName())) {
            return;
        }
        if (swap.stagingExists() && Bukkit.getWorld(swap.stagingName()) == null) {
            // Finished on a previous run and unloaded again - nothing to do until the swap.
            return;
        }
        swap.createStaging().ifPresent(staged -> {
            staged.getWorldBorder().setCenter(0, 0);
            staged.getWorldBorder().setSize(config.farmWorldBorderDiameter());
            pregen.start(staged.getName(), config.farmWorldBorderDiameter(),
                    () -> plugin.getLogger().info("tomorrow's farm world is pre-generated and waiting"));
        });
    }

    private void forEachInFarmWorld(final java.util.function.Consumer<Player> action) {
        worlds.world(WorldRole.FARM).ifPresent(farm -> {
            for (final Player player : List.copyOf(farm.getPlayers())) {
                action.accept(player);
            }
        });
    }
}
