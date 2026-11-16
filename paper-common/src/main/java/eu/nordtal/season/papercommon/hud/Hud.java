package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** A plugin's boss bar lines, kept per player, and the one clock that draws them. Call it on the main thread only. */
public final class Hud implements Listener {

    public static final Duration FRAME = Duration.ofMillis(250);

    private final Plugin plugin;
    private final Identities identities;

    private final List<HudLine> lines = new ArrayList<>();

    private final List<Cadence> cadences = new ArrayList<>();

    private final Map<UUID, @Nullable BossBar[]> bars = new HashMap<>();

    private final Set<UUID> held = new HashSet<>();

    private final Map<UUID, BossBar> covers = new HashMap<>();

    private long frame;
    private Scheduler.@Nullable Task task;

    private record Cadence(long frames, Runnable work) {}

    public Hud(final Plugin plugin, final Identities identities) {
        this.plugin = plugin;
        this.identities = identities;
    }

    public void declare(final HudLine line) {
        lines.add(line);
    }

    public void every(final Duration period, final Runnable work) {
        cadences.add(new Cadence(Math.max(1, Math.ceilDiv(period.toMillis(), FRAME.toMillis())), work));
    }

    /** Starts the clock. A plugin that declared no line has none. */
    public void start() {
        if (task == null && (!lines.isEmpty() || !cadences.isEmpty())) {
            task = PaperScheduler.of(plugin).onMainEvery(FRAME, FRAME, this::frame);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            hide(player, bars.get(player.getUniqueId()));
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            uncover(player);
        }
        bars.clear();
        held.clear();
    }

    /** Hides every line of {@code player} until {@link #release}, so a screen of its own can take the top. */
    public void hold(final Player player) {
        held.add(player.getUniqueId());
        hide(player, bars.remove(player.getUniqueId()));
    }

    /** Shows {@code line} as the one bar of a held {@code player}, or none on {@code null}. */
    public void cover(final Player player, final @Nullable Component line) {
        if (line == null || !held.contains(player.getUniqueId())) {
            uncover(player);
            return;
        }
        final BossBar bar = covers.computeIfAbsent(player.getUniqueId(), key -> BossBarLine.bar());
        BossBarLine.show(bar, line);
        player.showBossBar(bar);
    }

    /** Takes away the cover of {@code player} and draws its lines again from the next frame on. */
    public void release(final Player player) {
        uncover(player);
        held.remove(player.getUniqueId());
    }

    private void uncover(final Player player) {
        final BossBar cover = covers.remove(player.getUniqueId());
        if (cover != null) {
            player.hideBossBar(cover);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
        held.remove(event.getPlayer().getUniqueId());
        covers.remove(event.getPlayer().getUniqueId());
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
        if (lines.isEmpty() || held.contains(player.getUniqueId())) {
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());
        final @Nullable BossBar[] shown = bars.computeIfAbsent(player.getUniqueId(), key -> new BossBar[lines.size()]);
        for (int index = 0; index < lines.size(); index++) {
            final List<Pill.Content> pills = lines.get(index).render(player, locale);
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
