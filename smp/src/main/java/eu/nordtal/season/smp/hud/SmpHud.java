package eu.nordtal.season.smp.hud;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.hud.Bearing;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.smp.milestone.MilestoneNames;
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
 * The SMP's two lines on its {@code Hud}: dimension and milestone, and the target while {@code /navigate} is on.
 *
 * The second line is hidden rather than emptied; there is no season countdown, as the season has no end date.
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

    /** Declares the two lines, status above navigation. */
    public void declareOn(final Hud hud) {
        hud.declare(this::statusLine);
        hud.declare(this::navigateLine);
    }

    /** The world's pill, then the milestone's, or the world's alone once the track has run out. */
    List<Pill> statusLine(final Player player, final Locale locale) {
        final String dimension =
                worlds.roleOf(player.getWorld()).map(WorldRole::glyph).orElse(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD);

        // One read, so the name and the percentage are always the same milestone's.
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

    /** The target's pill, led by the arrow to it, then the distance's; nothing while no target is set. */
    List<Pill> navigateLine(final Player player, final Locale locale) {
        final Optional<NavigationTarget> wanted = navigation.of(player.getUniqueId());
        if (wanted.isEmpty()) {
            return List.of();
        }
        final NavigationTarget target = wanted.get();
        // Non-null exactly when kind() is POI: NavigationTarget.poi() is the only factory that supplies one.
        final String label = target.kind() == NavigationTarget.Kind.POI
                ? Objects.requireNonNull(target.label())
                : messages.format(locale, target.name());

        // A target in another world has no bearing worth drawing.
        if (!target.isIn(player.getWorld().getName())) {
            return List.of(
                    Pill.of(Glyphs.BOSSBAR_ICON_COMPASS, label),
                    Pill.of(messages.format(locale, MESSAGES.smp().hud().navigateOtherWorld())));
        }

        final Location at = Objects.requireNonNull(player.getLocation());
        final int arrow = Bearing.arrowIndex(at.getX(), at.getZ(), at.getYaw(), target.x(), target.z());
        final long distance = Math.round(Math.hypot(target.x() - at.getX(), target.z() - at.getZ()));

        return List.of(
                Pill.of(Glyphs.BOSSBAR_ARROWS.get(arrow), label),
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
