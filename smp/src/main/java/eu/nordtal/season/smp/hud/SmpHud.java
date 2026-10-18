package eu.nordtal.season.smp.hud;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.hud.Bearing;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.navigate.NavigatePage;
import eu.nordtal.season.smp.navigate.Navigation;
import eu.nordtal.season.smp.navigate.NavigationTarget;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.world.WorldRole;
import eu.nordtal.season.smp.world.Worlds;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The two HUD lines of the SMP: dimension and milestone, and the target while {@code /navigate} is on.
 *
 * The second line is hidden when it has no target. The SMP has no season countdown.
 */
public final class SmpHud {

    private final Worlds worlds;
    private final SeasonState season;
    private final Navigation navigation;
    private final Messages messages;

    public SmpHud(final Worlds worlds, final SeasonState season, final Navigation navigation, final Messages messages) {
        this.worlds = worlds;
        this.season = season;
        this.navigation = navigation;
        this.messages = messages;
    }

    public void declareOn(final Hud hud) {
        hud.declare(this::statusLine);
        hud.declare(this::navigateLine);
    }

    List<Pill> statusLine(final Player player, final Locale locale) {
        final String dimension =
                worlds.roleOf(player.getWorld()).map(WorldRole::glyph).orElse(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD);

        // Reads once, so the name and the percentage belong to the same milestone.
        final SeasonState.Active active = season.active();
        if (active.key() == null) {
            return List.of(Pill.of(dimension, worldName(player, locale)));
        }

        final int percent = (int) Math.round(active.progress() * 100.0);
        return List.of(
                Pill.of(dimension, worldName(player, locale)),
                Pill.of(messages.format(
                        locale,
                        MESSAGES.smp().hud().milestone(new MilestoneContext(milestoneName(active.key(), locale))))),
                Pill.of(messages.format(locale, MESSAGES.smp().hud().progress(percent))));
    }

    List<Pill> navigateLine(final Player player, final Locale locale) {
        final Optional<NavigationTarget> wanted = navigation.of(player.getUniqueId());
        if (wanted.isEmpty()) {
            return List.of();
        }
        final NavigationTarget target = wanted.get();
        final String label = NavigatePage.label(target, messages, locale);

        // A target in another world has no direction.
        if (!target.isIn(player.getWorld().getName())) {
            return List.of(
                    Pill.of(Glyphs.BOSSBAR_ICON_COMPASS, label),
                    Pill.of(messages.format(locale, MESSAGES.smp().hud().navigateOtherWorld())));
        }

        final Location at = Objects.requireNonNull(player.getLocation());
        final int arrow = Bearing.arrowIndex(at.getX(), at.getZ(), at.getYaw(), target.x(), target.z());
        final long distance = Math.round(target.distanceFrom(at.getX(), at.getZ()));

        return List.of(
                Pill.of(Bearing.arrow(arrow), label),
                Pill.of(messages.format(locale, MESSAGES.smp().hud().distance(distance))));
    }

    private String worldName(final Player player, final Locale locale) {
        return worlds.roleOf(player.getWorld())
                .map(role -> messages.format(locale, MESSAGES.smp().world(role)))
                .orElse(player.getWorld().getName());
    }

    private String milestoneName(final String key, final Locale locale) {
        return MilestoneNames.of(messages, locale, key);
    }
}
