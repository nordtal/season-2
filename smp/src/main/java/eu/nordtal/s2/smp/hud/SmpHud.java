package eu.nordtal.s2.smp.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.hud.Bearing;
import eu.nordtal.s2.common.hud.BossBarWidth;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.navigate.NavigationTarget;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The SMP's two boss bar lines, drawn with the same technique as the hunger games': the vanilla bar
 * made invisible by the resource pack, and a background composed from power-of-two glyph segments.
 *
 * <table>
 *   <caption>the two lines</caption>
 *   <tr><th>line</th><th>when</th><th>shows</th></tr>
 *   <tr><td>1</td><td>always</td><td>the current dimension, and the current milestone with its
 *       progress - the dimension alone once the track has run out</td></tr>
 *   <tr><td>2</td><td>only while {@code /navigate} is active</td><td>the target, an arrow to it and
 *       the distance</td></tr>
 * </table>
 *
 * <p><b>There is no season countdown</b>, and there never will be one: the season has no fixed end
 * date, and nothing in this design may depend on knowing when it stops.
 *
 * <p>Line 2 is hidden rather than emptied when nobody is navigating. An empty bar still occupies its
 * strip of screen, and {@code /navigate} being off by default means most players would see that
 * strip most of the time.
 */
public final class SmpHud {

    /**
     * The background width in pixels. Same value as the hunger games' HUD, because it is the same
     * bar on the same screen and two widths would look like a mistake.
     */
    private static final int BACKGROUND_WIDTH = 182;

    /** Four times a second: fast enough that the navigation arrow tracks a turning player. */
    private static final long REFRESH_TICKS = 5L;

    /**
     * How long a status-bar announcement stays up before the ordinary line comes back.
     *
     * <p>Eight seconds is the answer to the shape of the mistake this could be. The farm reset warns
     * at 30, 10, 5 and 1 minutes, and a warning that occupied the bar between the first two would
     * hold a player's dimension and milestone hostage for twenty minutes to say something that has
     * not changed. What is wanted is a glance, four times - so the line takes the bar, is read, and
     * gives it back. (docs/smp.md's reset sequence says "chat + HUD"; the HUD half is the one that
     * reaches somebody who is not reading chat while mining, which is everybody in a farm world.)
     */
    private static final Duration ANNOUNCEMENT = Duration.ofSeconds(8);

    private final Plugin plugin;
    private final Worlds worlds;
    private final SeasonState season;
    private final Navigation navigation;
    private final Messages messages;
    private final PlayerLocales locales;

    private final Map<UUID, BossBar> statusBars = new HashMap<>();
    private final Map<UUID, BossBar> navigateBars = new HashMap<>();

    /**
     * Who is currently being told something, and until when.
     *
     * <p>Main thread only - written by {@link #announce} and read by the render tick, both of which
     * are Bukkit calls, so a plain {@link HashMap} is the honest type. A stale entry is dropped by
     * the tick that reads it rather than by a sweep: there is one entry per player at most, and the
     * quit path already clears the bars.
     */
    private final Map<UUID, Announcement> announcements = new HashMap<>();

    private BukkitTask task;

    /** One line, and the nanoTime it stops being shown. */
    private record Announcement(String line, long until) {
    }

    public SmpHud(final Plugin plugin, final Worlds worlds, final SeasonState season,
                  final Navigation navigation, final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.season = season;
        this.navigation = navigation;
        this.messages = messages;
        this.locales = locales;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Bukkit.getOnlinePlayers().forEach(this::hide);
        statusBars.clear();
        navigateBars.clear();
        announcements.clear();
    }

    /**
     * Takes the status line over for {@link #ANNOUNCEMENT}, keeping the dimension icon. Main thread.
     *
     * <p>The icon stays because the line is about the world the player is standing in and the icon
     * is what says which one that is - and because a bar that changes shape as well as text reads as
     * a glitch rather than as a message.
     *
     * @param line already rendered, in the player's own language, and short enough for the bar - it
     *             is composed rather than parsed, like every other boss bar line
     */
    public void announce(final Player player, final String line) {
        announcements.put(player.getUniqueId(),
                new Announcement(line, System.nanoTime() + ANNOUNCEMENT.toNanos()));
    }

    public void hide(final Player player) {
        announcements.remove(player.getUniqueId());
        final BossBar status = statusBars.remove(player.getUniqueId());
        if (status != null) {
            player.hideBossBar(status);
        }
        final BossBar navigate = navigateBars.remove(player.getUniqueId());
        if (navigate != null) {
            player.hideBossBar(navigate);
        }
    }

    private void renderAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            renderFor(player);
        }
    }

    private void renderFor(final Player player) {
        final Locale locale = locales.of(player.getUniqueId());

        final BossBar status = statusBars.computeIfAbsent(player.getUniqueId(), key -> emptyBar());
        status.name(bossBarText(BossBarWidth.compose(BACKGROUND_WIDTH) + " " + statusLine(player, locale)));
        player.showBossBar(status);

        final Optional<NavigationTarget> target = navigation.of(player.getUniqueId());
        if (target.isEmpty()) {
            final BossBar existing = navigateBars.remove(player.getUniqueId());
            if (existing != null) {
                player.hideBossBar(existing);
            }
            return;
        }

        final BossBar navigate = navigateBars.computeIfAbsent(player.getUniqueId(), key -> emptyBar());
        navigate.name(bossBarText(BossBarWidth.compose(BACKGROUND_WIDTH) + " "
                + navigateLine(player, locale, target.get())));
        player.showBossBar(navigate);
    }

    /**
     * Wraps a composed HUD line in a component that names {@link Glyphs#FONT_BOSSBAR}.
     *
     * <p>Without the font key the bar's own code points resolve against {@code minecraft:default},
     * where they are either undefined or - worse - defined as something else entirely; see the
     * note on {@link Glyphs#FONT_BOSSBAR}. The whole line goes in one component on purpose: the
     * bossbar font carries its own {@code nordtal:font/ascii.png} provider, so the readable text
     * beside the glyphs is drawn by that font too rather than falling out of the styling.</p>
     */
    private static Component bossBarText(final String line) {
        return Component.text(line).font(Key.key(Glyphs.FONT_BOSSBAR));
    }

    private static BossBar emptyBar() {
        return BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    }

    /** Dimension, then the milestone - or the dimension alone once there is no milestone left. */
    private String statusLine(final Player player, final Locale locale) {
        final String dimension = worlds.roleOf(player.getWorld())
                .map(WorldRole::glyph)
                .orElse(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD);

        final String announcement = announcementFor(player.getUniqueId());
        if (announcement != null) {
            return dimension + " " + announcement;
        }

        // One read: this line is a milestone's name and that milestone's percentage, and taking
        // them separately is how it comes to be neither.
        final SeasonState.Active active = season.active();
        if (active.key() == null) {
            return dimension + " " + worldName(player, locale);
        }

        final int percent = (int) Math.round(active.progress() * 100.0);
        return dimension + " " + worldName(player, locale) + "   "
                + messages.format(locale, "smp.hud.milestone",
                        "milestone", milestoneName(active.key(), locale), "percent", percent);
    }

    private String navigateLine(final Player player, final Locale locale, final NavigationTarget target) {
        final String label = target.kind() == NavigationTarget.Kind.POI
                ? target.label()
                : messages.get(locale, target.label());

        // A target in another world has no bearing worth drawing: the arrow would spin, and the
        // distance would be measured between two coordinate systems that share nothing but numbers.
        if (!target.isIn(player.getWorld().getName())) {
            return Glyphs.BOSSBAR_ICON_COMPASS + " " + label + "   "
                    + messages.get(locale, "smp.hud.navigate-other-world");
        }

        final Location at = player.getLocation();
        final int arrow = Bearing.arrowIndex(at.getX(), at.getZ(), at.getYaw(), target.x(), target.z());
        final long distance = Math.round(Math.hypot(target.x() - at.getX(), target.z() - at.getZ()));

        return Glyphs.BOSSBAR_ARROWS[arrow] + " " + label + "   "
                + messages.format(locale, "smp.hud.distance", "blocks", distance);
    }

    /** The live announcement for a player, or null - dropping it here rather than on a timer. */
    private String announcementFor(final UUID player) {
        final Announcement announcement = announcements.get(player);
        if (announcement == null) {
            return null;
        }
        if (System.nanoTime() - announcement.until() >= 0) {
            announcements.remove(player);
            return null;
        }
        return announcement.line();
    }

    private String worldName(final Player player, final Locale locale) {
        return worlds.roleOf(player.getWorld())
                .map(role -> messages.get(locale, "smp.world." + role.name().toLowerCase(Locale.ROOT)))
                .orElse(player.getWorld().getName());
    }

    private String milestoneName(final String key, final Locale locale) {
        final String messageKey = "smp.milestone." + key;
        return messages.hasTranslation(locale, messageKey) ? messages.get(locale, messageKey) : key;
    }
}
