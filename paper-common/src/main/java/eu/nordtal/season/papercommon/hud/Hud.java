package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * A plugin's heads-up display: the boss bar lines it declares, kept per player, and the one clock that draws them.
 *
 * Main thread only. Surfaces that change more slowly, such as the boards, ride the same clock through {@link #every}.
 */
public final class Hud implements Listener {

    /** Four times a second: fast enough that an arrow tracks a turning player. */
    public static final Duration FRAME = Duration.ofMillis(250);

    private final Plugin plugin;
    private final Identities identities;

    /** Top to bottom, in the order declared. */
    private final List<HudLine> lines = new ArrayList<>();

    private final List<Cadence> cadences = new ArrayList<>();

    /** Each player's bar per line, null where the line is hidden for them. */
    private final Map<UUID, @Nullable BossBar[]> bars = new HashMap<>();

    private long frame;
    private Scheduler.@Nullable Task task;

    /** Work that runs every {@code frames}th frame. */
    private record Cadence(long frames, Runnable work) {}

    public Hud(final Plugin plugin, final Identities identities) {
        this.plugin = plugin;
        this.identities = identities;
    }

    /** Adds a line below every line declared before it. */
    public void declare(final HudLine line) {
        lines.add(line);
    }

    /** Runs {@code work} on this clock every {@code period}, rounded up to whole frames. */
    public void every(final Duration period, final Runnable work) {
        cadences.add(new Cadence(Math.max(1, Math.ceilDiv(period.toMillis(), FRAME.toMillis())), work));
    }

    /** Starts the clock, once the plugin declared what it shows; a plugin that declared nothing has none. */
    public void start() {
        if (task == null && (!lines.isEmpty() || !cadences.isEmpty())) {
            task = PaperScheduler.of(plugin).onMainEvery(FRAME, FRAME, this::frame);
        }
    }

    /** Stops the clock and takes every bar off the screen. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            hide(player, bars.get(player.getUniqueId()));
        }
        bars.clear();
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
    }

    private void frame() {
        frame++;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            draw(player);
        }
        for (final Cadence cadence : cadences) {
            if (frame % cadence.frames() == 0) {
                cadence.work().run();
            }
        }
    }

    private void draw(final Player player) {
        if (lines.isEmpty()) {
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());
        final @Nullable BossBar[] shown = bars.computeIfAbsent(player.getUniqueId(), key -> new BossBar[lines.size()]);
        for (int index = 0; index < lines.size(); index++) {
            final List<Pill> pills = lines.get(index).render(player, locale);
            final BossBar bar = shown[index];
            if (pills.isEmpty()) {
                if (bar != null) {
                    player.hideBossBar(bar);
                    shown[index] = null;
                }
                continue;
            }
            final BossBar drawn = bar == null ? BossBarLine.bar() : bar;
            BossBarLine.show(drawn, pills);
            player.showBossBar(drawn);
            shown[index] = drawn;
        }
    }

    private static void hide(final Player player, final @Nullable BossBar @Nullable [] shown) {
        if (shown == null) {
            return;
        }
        for (final BossBar bar : shown) {
            if (bar != null) {
                player.hideBossBar(bar);
            }
        }
    }
}
