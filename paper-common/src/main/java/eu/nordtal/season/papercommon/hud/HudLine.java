package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;

/** One boss bar line a plugin declares on its {@link Hud}: what the line says to one player right now. */
@FunctionalInterface
public interface HudLine {

    /**
     * Returns the line's pills for {@code player}, in {@code locale}; none hides the bar. Main thread, every frame.
     */
    List<Pill> render(Player player, Locale locale);
}
