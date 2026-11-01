package eu.nordtal.season.smp.hud;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.world.WorldRole;
import eu.nordtal.season.smp.world.Worlds;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;

/**
 * The HUD line of the SMP: dimension and milestone. The SMP has no season countdown.
 */
public final class SmpHud {

    record World(WorldRole world) {}

    record Progress(@Example("40") int percent) {}

    static final DocumentSpec<World> WORLD = line("hud-world", World.class).titled("World in the HUD");
    static final DocumentSpec<Progress> PROGRESS =
            line("hud-progress", Progress.class).titled("Progress in the HUD");

    private static <V extends Record> DocumentSpec<V> line(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", name, "HUD", DocumentSpec.Shape.BOSS_BAR, values);
    }

    private final Worlds worlds;
    private final SeasonState season;
    private final MessageRenderer renderer;

    public SmpHud(final Worlds worlds, final SeasonState season, final MessageRenderer renderer) {
        this.worlds = worlds;
        this.season = season;
        this.renderer = renderer;
    }

    public void declareOn(final Hud hud) {
        hud.declare(this::statusLine);
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
                Pill.of(MilestoneNames.of(renderer, locale, active.key())),
                Pill.of(renderer.plainLine(locale, PROGRESS.with(new Progress(percent)))));
    }

    private String worldName(final Player player, final Locale locale) {
        return worlds.roleOf(player.getWorld())
                .map(role -> renderer.plainLine(locale, WORLD.with(new World(role))))
                .orElse(player.getWorld().getName());
    }
}
